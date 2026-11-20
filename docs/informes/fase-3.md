# Fase 3 · Ingesta en directo: informe

**Fecha:** 20-11-2026. **Rama:** `fase-3-ingesta`. **Resultado:** hecha. La ingesta lee los tres feeds de Renfe cada 7 s con peticiones condicionales y Resilience4j, mantiene en memoria el estado de ~400 trenes cruzado con el horario, estima su posición sobre el recorrido y guarda `posiciones`, `retrasos` y `avisos` en tablas particionadas. **63 pruebas** en el backend: 62 pasan y 1 se omite (la manual de la fase 2). La prueba real de 10 minutos ha ido **sin un solo error**.

## 1. Especificación

El punto de partida es el punto 6 de «Cambios tras la fase 0» y la fase 3 del §6 del plan.

**Entra:**

- **Cliente de los feeds:**
  - `@Scheduled` con `fixedDelay` de 7 s;
  - `If-None-Match` e `If-Modified-Since`;
  - decodificación con `gtfs-realtime-bindings` 0.2.0;
  - Resilience4j 2.4.0, con reintento exponencial y un circuit breaker por feed;
  - 3 s para conectar y 5 s para toda la respuesta.
- **Estado en memoria:**
  - un `ConcurrentHashMap` de `Tren` inmutables, cruzados con el horario;
  - estados `EN_ORIGEN`, `EN_MARCHA`, `TERMINADO` y `CANCELADO`;
  - un tren desaparece tras 3 muestras nuevas y completas sin él;
  - detección de muestras incompletas;
  - los trenes sin horario se guardan con lo que traen.
- **Posición estimada:** fracción del recorrido, coordenadas y rumbo, corregida por el feed. Sin recorrido, la posición del feed.
- **Persistencia (Flyway V3):**
  - `posiciones`: solo los cambios, en particiones diarias que se borran con `DROP` a las 48 h;
  - `retrasos`: el retraso final por parada, en particiones mensuales;
  - `avisos`: el estado actual, que se sustituye en cada muestra.
- **Estado por núcleo:** trenes en directo, trenes que el horario da en marcha, cobertura y «sin datos en directo».
- **Métricas Micrometer** y `GET /api/directo/estado`.
- **Pruebas:** con muestras reales, unitarias, de integración con Testcontainers y del cliente.
- **Prueba real** de 10 minutos.

**No entra:**

- la API de trenes y estaciones y el SSE, que llegan en la fase 4;
- la captura de 24 h (ver el §6);
- reconstruir el estado tras un reinicio;
- varias instancias.

**Aceptación:**

1. `./mvnw verify` pasa con las pruebas de la lista.
2. La prueba real deja filas en las tres tablas, cobertura por núcleo y ningún error sin explicar.

La aceptación del plan («24 h seguidas sin errores») no cabe en esta sesión; queda pendiente (§6).

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| `ClienteFeeds` | `HttpClient` con `sendAsync().get(5 s)`. Guarda `ETag` y `Last-Modified` por feed solo si la muestra se decodifica bien, y trata un 200 con el mismo `ETag` como sin cambios. `Retry(CircuitBreaker(llamada))`: 3 intentos con 0,5 s y 1 s de espera; el circuito mira 10 llamadas y se abre con el 50 % de fallos durante 60 s. Métricas de Resilience4j con `resilience4j-micrometer` |
| `EstadoDirecto` | El estado. Procesa `trip_updates` (seguimiento de la próxima parada y cierre del retraso final) y `vehicle_positions` (estado y cambios). Una muestra solo cuenta si su cabecera es posterior; si retrocede 3 veces seguidas, se acepta. Ver el [ADR 0008](../adr/0008-estado-del-directo-en-memoria.md) |
| `DetectorMuestrasIncompletas` | Una muestra con menos del 80 % de las entidades de la última completa es incompleta. Con 3 seguidas, ese nivel pasa a ser el normal |
| `EstimadorPosicion` · `Recorrido` | Interpola sobre el recorrido entre la última parada y la siguiente con la hora y el retraso. Contempla los recorridos al revés y los circulares ([ADR 0010](../adr/0010-posicion-estimada-sobre-el-recorrido.md)) |
| `HorarioDirectoJdbc` | Viajes, recorridos e intervalos del día, en cachés que se vacían si hay un horario nuevo o cambia el día. La fecha de servicio sale del calendario: Renfe publica un `service_id` por núcleo y día |
| `RepositorioDirecto` | `batchUpdate` por lotes de 500. `retrasos` con `ON CONFLICT DO UPDATE`; avisos con `DELETE` + `INSERT` en una transacción |
| `GestorParticiones` | Particiones diarias en hora de Madrid y mensuales. Se crean al arrancar y en la ingesta (una vez por hora o al cambiar el día); se borran con `DROP TABLE` ([ADR 0009](../adr/0009-particiones-diarias-y-mensuales.md)) |
| `IngestaDirecto` | El ciclo (retrasos, posiciones, avisos, reestimación y resumen), las métricas y una cola de retrasos pendientes si falla la base de datos |
| `DirectoController` | `GET /api/directo/estado`: última muestra, antigüedad, trenes por estado, sin horario, núcleos y estado de los circuitos |
| Configuración | `trainwatch.directo.*` (`DirectoProperties`) y dos hilos para `@Scheduled`. Las métricas por HTTP solo en `local`. En las pruebas, la ingesta está desactivada |
| Pruebas | `EstadoDirectoTests` (28), `ClienteFeedsTests` (4), `DirectoIntegracionTests` (2, con Testcontainers) y `ServidorFeeds`, un servidor HTTP local que hace de Renfe |
| Muestras | 9 ficheros en `backend/src/test/resources/gtfsrt/` (244 KB): 4 muestras de posiciones y retrasos, de 09:28:15 a 09:29:15 UTC, más los avisos. Hay trenes que cambian de parada (el `4023V24219C1` pasa de la 69103 a la 69104) y la última muestra es la incompleta de la fase 0. Llevan un README con la cita CC BY 4.0 |
| Documentación | ADR 0008-0010, README y la [guía de defensa](../defensa/fase-3.md) |

**Sobre las muestras en el repo:** el `CLAUDE.md` deja los `.pb` fuera del repo. La tarea pedía copiar unas pocas a las pruebas, así que `.gitignore` tiene una excepción solo para esa carpeta. Los 8,2 MB de `spike/datos/` siguen fuera.

### Lo que se descubrió por el camino

- **`trip_updates` no trae `stop_sequence`.** La secuencia se deduce del horario por `stop_id`, buscando desde la parada que ya se tenía, porque en una circular la misma estación sale dos veces. Sin horario, no hay secuencia y no se guarda el retraso.
- **El núcleo va en el `trip_id`** (`4023V24219C1` → 40) y la línea, en la etiqueta del vehículo (`C3-17907` → C3). Así se rellenan los trenes sin horario.
- **`HttpRequest.timeout` puede saltar antes que el plazo total**, con `HttpTimeoutException`. Las dos vías cortan la petición.

## 3. Cifras

### Pruebas

`./mvnw verify`: **63 pruebas, 0 fallos, 1 omitida**. De ellas, 34 son nuevas:

- `EstadoDirectoTests` (28):
  - estados: en origen, en origen con retraso, terminado, cancelado solo con `CANCELED`, desaparecido que no es cancelado, sin horario y sin coordenadas;
  - tolerancia: 3 muestras nuevas para desaparecer, la muestra repetida que no cuenta, la incompleta y el nivel nuevo tras 3 incompletas;
  - retraso final: cierre al pasar a la siguiente, última parada al desaparecer, viaje que desaparece a mitad, muestra incompleta, retroceso, cancelado y sin horario;
  - posición y rumbo: entre paradas, con retraso, avance con la hora sin pasar de la siguiente, corrección del feed, recorrido al revés (270°), circular (180°), sin recorrido (rumbo del movimiento) y solo los cambios a `posiciones`;
  - lo que encontró la revisión: reclasificación con la hora, «00000», reloj del feed que retrocede y cambio de hora;
  - las 4 muestras reales.
- `ClienteFeedsTests` (4): 304 con `If-None-Match`, reintento que se recupera, circuito que se abre tras 5 fallos y deja de llamar, y tiempo agotado.
- `DirectoIntegracionTests` (2):
  - de los `.pb` a filas en `posiciones`, `retrasos` (la 69103 con 3 min, `retrasos_p202611`) y `avisos`;
  - tres 304 que no procesan nada y la muestra incompleta sin desapariciones;
  - `/api/directo/estado` (Valencia con un 100 % de cobertura, Bilbao «sin datos en directo»);
  - las métricas;
  - particiones creadas, y luego borradas con `DROP` tres días después.

### Prueba real (20-11-2026, de 12:18:19 a 12:28:19, hora de Madrid)

`./mvnw spring-boot:run` contra los feeds de verdad, con la base local vacía. Al arrancar importó el horario: 35,6 s con la descarga real del ZIP desde Renfe, con las mismas filas que en la fase 2.

| Medida | Valor |
|---|---:|
| Ciclos por feed | 85: **31 con 200 y 54 con 304** (el 64 % de las peticiones, sin descargar nada) |
| Errores de descarga, de procesado y de base de datos | **0**. Los tres circuitos, cerrados todo el rato |
| Muestras nuevas de posiciones | 31: 30 completas y **1 incompleta** (detectada, sin desapariciones) |
| Trenes en memoria al final | **404**: 172 en origen, 230 en marcha, 0 terminados y 2 cancelados. El resumen cuenta 3 cancelados: el tercero solo estaba en `trip_updates` |
| Trenes sin viaje en el horario | 11 (2,7 %) |
| Trenes desaparecidos (3 muestras completas sin ellos) | 37 |
| Filas en `posiciones` | **3.830** de 435 viajes, todas en `posiciones_p20261120`. 3.055 con fracción del recorrido |
| Filas en `retrasos` | **423** de 207 viajes (446 escrituras: 23 cierres repetidos se resolvieron con `ON CONFLICT`). Media de 8,3 min; el 33 % con menos de 3 min |
| Paradas que se dejaron atrás sin poder cerrarse | 81 (sin horario, o con la parada nueva por detrás de la anterior) |
| `avisos` | 74 avisos distintos; 17.340 filas escritas en 31 sustituciones |
| Antigüedad del último dato | Entre −2 y 16 s (−2: la cabecera de Renfe iba 2 s por delante del reloj del portátil) |

**Cobertura por núcleo a las 12:28** (en marcha según el horario y vistos en el feed):

| Núcleo | En directo | En marcha (horario) | Vistos | Cobertura |
|---|---:|---:|---:|---:|
| 10 Madrid | 103 | 56 | 52 | 92,9 % |
| 20 Asturias | 27 | 26 | 19 | 73,1 % |
| 30 Sevilla | 11 | 4 | 4 | 100 % |
| **31 Cádiz** | **10** | 6 | 3 | **50,0 %** |
| 32 Málaga | 10 | 4 | 4 | 100 % |
| 40 Valencia | 37 | 20 | 17 | 85,0 % |
| 41 Murcia/Alicante | 11 | 0 | 0 | — |
| 45 Cartagena · 46 Ferrol · 47 León | 4 · 4 · 5 | 2 · 1 · 2 | todos | 100 % |
| **51 Rodalies** | **111** | 65 | 55 | **84,6 %** |
| **60 Bilbao** | **34** | 14 | 14 | **100 %** |
| 61 San Sebastián · 62 Santander · 70 Zaragoza | 11 · 22 · 2 | 5 · 9 · 1 | todos | 100 % |

**Bilbao y Cádiz emiten hoy.** En la fase 0 (de 10:00 a 10:30, también el 20-11) no emitían nada; a las 12:18 Bilbao trae 34 trenes y Cádiz 10. El estado «sin datos en directo» no está escrito a mano: sale del cálculo y se comprueba en la prueba de integración con un viaje de prueba de Bilbao sin trenes en el feed. Habrá que ver en la captura de 24 h si esto es estable.

**Volumen.** En 10 minutos se escribieron 3.426 filas de cambios en `posiciones` (sin contar la primera foto de 404), unas 340 por minuto. A ese ritmo de mediodía serían ~0,49 M filas al día; la fase 0 estimaba 0,26 M como media del día entero. Hay que confirmarlo con 24 h, pero el orden de magnitud cuadra y las particiones lo absorben sin problema. Los retrasos, a 42 por minuto, quedan en la misma escala que los ~51.000 al día de la fase 0.

## 4. Revisión independiente

Un subagente sin contexto de la conversación revisó el código contra la especificación, sobre todo la concurrencia y el cierre del retraso. **Concurrencia: sin fallos.** Dio por correctos el escritor único con `synchronized`, el `ConcurrentHashMap` con `Tren` inmutables, el `fixedDelay` y los dos hilos del programador.

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (alta) | El estado avanza antes de escribir. Si falla la base de datos, los retrasos cerrados de ese ciclo se pierden. Además, una marca de tren absurda caería fuera de las particiones y tumbaría el lote | **Corregido.** Los retrasos cerrados van a una cola que se reintenta en cada ciclo (como mucho 100.000). Una marca a más de 1 h de la cabecera se sustituye por la cabecera. Las posiciones de un ciclo con la base de datos caída sí se pierden: el siguiente cambio de cada tren se escribe igual |
| 2 (alta) | Si el procesado falla a mitad, el `ETag` ya está guardado y la muestra no se vuelve a pedir | **Se deja y se documenta.** El procesado solo falla si cae la base de datos al leer el horario. La siguiente muestra llega en ~20 s y trae el estado completo; se pierde, como mucho, el cierre de alguna parada |
| 3 (alta) | Si el reloj del feed retrocede o falta, la ingesta ignora todas las muestras hasta reiniciar | **Corregido.** Sin marca (0), manda el `ETag`. Si la marca retrocede en 3 muestras seguidas, se acepta. Hay una prueba |
| 4 | Con `stop_id` «00000», el tren pierde su parada y puede cambiar de estado | **Corregido:** conserva la última parada conocida. Hay una prueba |
| 5 | Entre muestras, un tren en origen no pasa a en marcha con la hora | **Corregido:** `reestimar` vuelve a clasificar. Hay una prueba |
| 6 | Se usa la hora del principio del ciclo después de descargas que pueden tardar | **Corregido:** se toma la hora después de cada descarga y al final del ciclo |
| 7 | La fecha de servicio se fija la primera vez, aunque el viaje aún no estuviera en el horario | **Corregido:** se recalcula cuando aparece el viaje |
| 8 | En una circular, tras un reinicio, un tren en la estación repetida del final se sitúa en el principio | **Se deja y se documenta.** Solo pasa en el primer ciclo tras un reinicio y en la última parada de una circular (C4 de Sevilla) |
| 9 | Una muestra de avisos vacía borraría todos los avisos | **Corregido** con el mismo detector de muestras incompletas |
| 10 | Un cancelado que faltaba en una muestra dejaba de contar como cancelado | **Corregido:** los cancelados salen del seguimiento |
| 11 | Las horas GTFS cuentan desde «mediodía menos 12 h»; con la medianoche, los dos días de cambio de hora irían 1 h desplazados | **Corregido** en `Viaje.origenDelDia`. Hay una prueba con el 29-03-2026. Las particiones siguen en la medianoche de Madrid, que es lo que se quiere |
| 12 | La cobertura no tiene en cuenta `calendar_dates` | **Se deja:** Renfe no lo publica (fase 0) |
| 13 | Las particiones no se creaban al arrancar, y una con nombre raro rompía la pasada | **Corregido:** se crean en `ApplicationReadyEvent` y cada borrado va en su `try` |
| 14 | El reintento sin *jitter* reintenta los 4xx y pierde la marca de interrupción | **Corregido** lo de la interrupción. Lo demás **se deja**: un solo cliente cada 7 s no necesita *jitter*, y un 4xx de Renfe, tres veces, es barato |
| 15 | Tras un reinicio se escribe una fila por tren aunque no haya cambiado | **Se deja** ([ADR 0008](../adr/0008-estado-del-directo-en-memoria.md)): son ~400 filas por reinicio |
| 16 | Faltan pruebas: espera creciente, circuito semiabierto, parada saltada, concurrencia, fallo de la base de datos | **En parte:** se añadieron 4 pruebas. Quedan como deuda la del semiabierto, la de la cola de retrasos con la base de datos caída y una de lectores concurrentes |

## 5. Decisiones tomadas sin consultar

- **Intervalo de 7 s** (la tarea dejaba 5-10 s) y `fixedDelay`.
- **Umbral de muestra incompleta: 80 %** de la última completa, con 3 seguidas para aceptar el nivel nuevo.
- **La clave del tren es el `trip_id`**, no el vehículo: un vehículo hace varios viajes al día.
- **Resilience4j sin anotaciones** (`resilience4j-circuitbreaker`, `-retry` y `-micrometer`), sin el *starter*: no hay uno para Spring Boot 4 y así se ve el orden de los decoradores.
- **La última parada** se cierra cuando el viaje desaparece de `trip_updates` estando en ella. Un viaje que desaparece a mitad no cierra nada.
- **Retención:** se borra la partición cuyo día entero tiene más de 48 h, así que hay entre 48 y 72 h.
- **Estado del núcleo:** `EN_DIRECTO`, `SIN_DATOS_EN_DIRECTO` (el horario da trenes y el feed ninguno) o `SIN_SERVICIO` (el horario no da ninguno).
- **Métricas por HTTP** solo en `local`; en producción, que decida la fase 7.

## 6. Qué quedó fuera o pendiente

- **La captura de 24 h** del plan (punto 6 de «Cambios tras la fase 0» y aceptación de la fase 3): hacen falta 24 h con la aplicación en marcha. Lo natural es hacerla en el servidor de la fase 7, o dejando el portátil encendido una noche. Servirá para ver la noche, la hora punta, los volúmenes reales y si Bilbao y Cádiz siguen emitiendo.
- **El estado no sobrevive a un reinicio.** Se podría reconstruir desde la última fila de cada tren en `posiciones`.
- **Las pruebas pendientes** del punto 16 de la revisión.
- **Una sola instancia** ([ADR 0008](../adr/0008-estado-del-directo-en-memoria.md)).

## 7. Qué cambiaría del plan

- **§6, fase 3, aceptación:** «24 h seguidas sin errores» pasa a la fase 7, en el servidor. Aquí queda una prueba de 10 minutos con 0 errores.
- **§1 y punto 4 de «Cambios tras la fase 0»:** Bilbao y Cádiz **sí emiten** a mediodía del 20-11. El texto público no debe decir que no tienen directo: el estado por núcleo lo calcula en cada momento.
- **§3:** `posiciones` puede rondar 0,5 M filas al día y no 0,26 M; hay que medirlo con 24 h. Con particiones diarias no cambia nada.
- **Fase 4:** la API puede leer `EstadoDirecto.trenes()` directamente. Cada `Tren` lleva su estado, su posición estimada y su rumbo, y `IngestaDirecto.resumen()` ya es el estado de los datos («datos de hace X s»).
