# Fase 4 · API y directo: informe

**Fecha:** 20-11-2026. **Rama:** `fase-4-api`. **Resultado:** hecha. La API REST pública (núcleos, trenes, ficha del tren, estaciones, líneas y avisos) está documentada con OpenAPI en `/api/docs`, los errores son `ProblemDetail` y el directo va por SSE: una instantánea al conectarse y después solo los cambios. Con la aplicación leyendo los feeds reales de Renfe, **200 clientes SSE a la vez durante 90 s**: latencia p99 de 42 ms, heap entre 71 y 91 MB, CPU del proceso por debajo del 5 %, 0 errores y 0 huecos. Además, `retrasos` ya guarda la **hora programada** de cada parada (para la fase 6). **121 pruebas** en el backend: 120 pasan y 1 se omite (la manual de la fase 2); 9 en el frontend.

> Este fichero sustituye al informe de la parada anterior de la fase 4 («falta la fase 3»), que sigue en el historial de git (`f514d17`). Las fases 3, 3b y 4r ya están fusionadas y `./mvnw verify` estaba en verde en `main` (`42803a0`) al empezar.

## 1. Especificación

Punto de partida: `docs/prompts/fase-4.md`, la fase 4 del §6 del plan y el §3 (SSE frente a WebSocket).

**Entra:**

1. **Hora programada en `retrasos`** (prioridad 1): migración V5 con `llegada_programada TIMESTAMPTZ`, rellenada por la ingesta al cerrar cada parada (también las inferidas), con el horario del viaje y su fecha de servicio; `NULL` en las filas antiguas.
2. **API REST** (Spring MVC), con records propios y OpenAPI (springdoc) en `/api/docs`:
   - `GET /api/nucleos`: nombre, centro y zoom del mapa, estado y cobertura;
   - `GET /api/nucleos/{id}/trenes`: id, línea, estado, posición estimada, rumbo, retraso, próxima parada y salida si está en origen;
   - `GET /api/trenes/{id}`: línea, origen y destino, paradas con hora programada y estimada, retraso y su evolución;
   - `GET /api/estaciones/{id}`: próximos trenes con su retraso, líneas y avisos;
   - `GET /api/estaciones?cerca=lat,lon&radio=`: las más cercanas, con PostGIS;
   - `GET /api/lineas/{nucleo}/{linea}`: recorrido en GeoJSON, paradas y avisos;
   - `GET /api/avisos?nucleo=&linea=&estacion=`;
   - errores con `ProblemDetail` (RFC 9457).
3. **Directo por SSE** (`GET /api/directo?nucleo=`): instantánea, cambios (altas, cambios y bajas), latido cada ~15 s y `Last-Event-ID` (con ADR).
4. **CORS** solo para `https://trainwatch.ivanchesa.com` y `http://localhost:5173`; caché HTTP en lo que no cambia.
5. **Límites:** máximo de conexiones SSE configurable con rechazo claro; tiempos de espera. Sin autenticación.
6. **Pruebas:** cada endpoint (contrato, errores y sin datos) con la ingesta alimentada con los `.pb` de la fase 3; SSE (instantánea y cambios); carga en local con 200 clientes.
7. **Contrato para el frontend:** un JSON de ejemplo por endpoint en `docs/api/`.

**No entra:** estadísticas (fase 6), el mapa (fase 5), autenticación, varias instancias, un histórico por tren (la ficha solo existe mientras el tren está en directo).

**Aceptación:**

1. `./mvnw verify` en verde con pruebas de cada endpoint, de errores, sin datos y del SSE.
2. La prueba de carga aguanta 200 clientes SSE a la vez, con memoria, CPU y latencia apuntadas.
3. La hora programada se guarda en el cierre normal, en el inferido, en un viaje que pasa la medianoche, y es `NULL` en un tren sin horario.

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| **V5 y cierre** | `retrasos.llegada_programada` (sin `DEFAULT`: no reescribe particiones). `EstadoDirecto.cierre` la calcula con `Viaje.instante(fecha, llegada_s)`, que cuenta desde «mediodía menos 12 h» (horas > 24:00 y cambio de hora). `ON CONFLICT` conserva la que hubiera si llega una nula. [ADR 0014](../adr/0014-hora-programada-en-retrasos.md) |
| `Respuestas` | Los records de la API, con `@Schema` en español. Horas en hora de Madrid con desfase; retrasos en minutos; lo que no se sabe, `null` |
| `VistaTrenes` | Del `Tren` en memoria a `TrenMapa`: posición estimada (o la del feed) redondeada a ~1 m, rumbo en grados, próxima parada (la siguiente a la del feed) con hora programada y estimada (programada + retraso), salida si está en origen y destino (la última parada: Renfe deja vacío `trip_headsign`) |
| `ConsultasApi` | SQL con `JdbcClient`: extensión de cada núcleo, estación, líneas que paran, pasos del horario por estación y día, cercanas (`ST_DWithin` + `<->` sobre el índice `geography`), línea con `ST_AsGeoJSON` (en memoria hasta el horario siguiente), evolución del retraso del viaje y avisos agrupados |
| Controladores | `NucleosController` (centro y zoom calculados del rectángulo de las estaciones para un mapa de 800 × 600 px), `TrenesController`, `EstacionesController` (horario de ayer y de hoy, de 30 min atrás a 60 min adelante, cruzado con el directo), `LineasController` (líneas y avisos) y `DirectoSseController` |
| `DifusorDirecto` | El SSE: un canal por núcleo con su foto y su secuencia; al final de cada ciclo de la ingesta (evento de Spring `CicloDirectoTerminado`) calcula altas, cambios y bajas, los serializa una vez y los encola a cada cliente. Un hilo virtual y una cola de 50 eventos por cliente; latido cada 15 s; máximo de conexiones (503 + `Retry-After`); 30 min por conexión; cierre de todas antes del apagado ordenado. [ADR 0015](../adr/0015-sse-para-el-directo.md) |
| Errores | `ErroresApi` (sobre `ResponseEntityExceptionHandler`) y `Problemas`: 400, 404 y 503 como `application/problem+json` con título y detalle en español; el tipo se fija en la respuesta para que llegue también a `EventSource` |
| CORS y caché | `ApiConfig`: CORS de solo lectura para los dos orígenes; `ETag` (filtro `ShallowEtagHeaderFilter`) en líneas y estaciones, nunca en el SSE. `Cache-Control` por endpoint (de 5 s a 1 h). [ADR 0016](../adr/0016-api-rest-desde-el-estado-en-memoria.md) |
| OpenAPI | springdoc 3.2.0: `/api/docs` y Swagger UI en `/api/docs/ui`. `OpenApiConfig` cuelga de cada 200 el ejemplo de `docs/api/` (Maven los copia al classpath) |
| `docs/api/` | 11 ejemplos sacados de la aplicación con los feeds reales y recortados, con su README (incluye cómo consumir el directo) |
| Prueba de carga | `backend/carga/CargaSse.java`, un programa de un fichero (JDK 25): N clientes en hilos virtuales, latencia de cada evento, huecos de secuencia, conexión de más y métricas del servidor por Actuator |

### Decisiones tomadas sin consultar

- **Al reconectarse, una instantánea nueva**, no los cambios perdidos (el encargo lo permite con un ADR). Medido: un evento `cambios` ocupa de media el 60 % de una instantánea (18,7 KB frente a 30,9 KB), porque casi todos los trenes en marcha cambian en cada ciclo; con dos eventos perdidos ya sale más caro reenviarlos. `Last-Event-ID` se lee y se cuenta (`trainwatch.api.directo.reconexiones`).
- **El directo manda un evento por ciclo de la ingesta (~7 s)**, no por muestra nueva de Renfe (~20 s): la posición estimada avanza entre muestras.
- **Límites por defecto:** 500 conexiones, cola de 50 eventos (~6 min), 30 min por conexión, latido de 15 s, `retry:` de 3-6 s con azar. Todo en `trainwatch.api.directo.*`.
- **Caché:** 5 s (trenes), 10 s (núcleos), 15 s (estación), 30 s (avisos), 1 min + `ETag` (línea: lleva avisos), 1 h + `ETag` (cercanas).
- **Próximos trenes de una estación:** como mucho 15, de 30 min atrás (los retrasados que aún no han pasado) a 60 min adelante.
- **La ficha de un tren solo existe en directo** (404 si no). **Los núcleos sin horario pero con trenes en el feed** salen en `/api/nucleos` sin centro ni zoom.
- **springdoc 3.2.0** (24-10-2026, la rama para Boot 4, compilada contra Boot 4.1): funciona con Boot 4.2 en todas las pruebas.
- **Swagger UI activado** también fuera de local: la API es pública y su documentación también. springdoc avisa al arrancar; si la fase 7 prefiere quitarla, es `springdoc.swagger-ui.enabled=false`.

## 3. Cifras

### Pruebas

`./mvnw verify`: **121 pruebas, 0 fallos, 1 omitida** (antes, 94); `npm test` en el frontend, 9 de 9. Nuevas:

- `EstadoDirectoTests.HoraProgramadaEnRetrasos` (5): cierre normal (la llegada de las 10:10, no la salida de las 10:11 ni la hora del cierre), cierre inferido, viaje que pasa la medianoche con calendario de varios días (fecha deducida: el 20, llegada el 21 a las 00:10), día del cambio de hora (29-03-2026) y tren sin horario (no cierra nada).
- `DirectoIntegracionTests`: la fila real del C1 de Valencia guarda 10:24 (hora de Madrid) y una fila sin hora queda en `NULL`.
- `ApiIntegracionTests` (6, con PostGIS en Testcontainers y la ingesta alimentada con los `.pb` de la fase 3):
  - **sin datos:** núcleos con mapa y «sin datos en directo», listas vacías, instantánea vacía; errores 404 (núcleo, tren, estación, línea, ruta inexistente) y 400 (tipo incorrecto, parámetro que falta, formato y rango de `cerca`, `radio`), todos `application/problem+json` con título en español; el SSE de un núcleo inexistente, 404 como `ProblemDetail`;
  - **con datos:** cada endpoint con su contrato (C1 en marcha con próxima parada y hora estimada = programada + retraso; ficha con paradas, situación y evolución; estación con el C1 en directo y el de Bilbao sin directo, y sin el tren que ya pasó; cercanas ordenadas y dentro del radio; línea en GeoJSON con paradas en orden; avisos filtrados por núcleo y estación);
  - **caché, `ETag` (304) y CORS** (los dos orígenes sí; otro origen y `POST`, 403);
  - **OpenAPI** con todos los endpoints, sus 404/503 como `problem+json` y los ejemplos de `docs/api/`;
  - **SSE:** instantánea igual que la REST y con tildes bien (UTF-8), después un evento `cambios` con la secuencia siguiente y menos trenes que la instantánea, ningún evento si nada cambia, latido con la antigüedad, y reconexión con `Last-Event-ID` que recibe una instantánea al día;
  - **máximo de conexiones:** con 3, la cuarta recibe 503 + `Retry-After: 30` + `ProblemDetail`, y al cerrarse las conexiones se liberan.
- `EjemplosApiTests` (10): cada ejemplo de `docs/api/` se lee con su record y se vuelve a escribir igual.
- Tras la revisión (§4): `DifusorDirectoTests` (3: cliente atascado con la ingesta y con el latido, apagado), `ProximosTrenesTests` (2: tren que ya salió, viajes de ayer pasada la medianoche) y el 500 como `ProblemDetail` en `ApiIntegracionTests` (que pasa a 7).

### Prueba de carga (20-11-2026, de 14:54:54 a 14:56:24)

Aplicación con `./mvnw spring-boot:run`, perfil `local`, `-Xmx512m`, `maximo-conexiones=200`, leyendo los **feeds reales de Renfe** (466 trenes en memoria) y la base local. Cliente: `java backend/carga/CargaSse.java http://localhost:8080 200 90 51,10,40,20,60` en el mismo portátil (los núcleos, por turnos: Rodalies, Madrid, Valencia, Asturias y Bilbao).

| Medida | Valor |
|---|---:|
| Clientes a la vez | **200** durante 90 s; conectados en 217 ms |
| Conexión 201 | **503**, `Retry-After: 30`, `ProblemDetail` «Directo completo» |
| Eventos recibidos | 200 instantáneas, 2.600 cambios (13 por cliente, uno por ciclo de ~7 s), 1.200 latidos; 56,5 MB |
| Tamaño medio | instantánea 30,9 KB · cambios 18,7 KB |
| Correcto | 0 errores, 0 cortes, 0 huecos de secuencia, todas empiezan por la instantánea |
| **Latencia** de `cambios` (del final del ciclo a la llegada) | **p50 10 ms · p95 30 ms · p99 42 ms · máx 44 ms** |
| **Heap** del servidor | 63 MB sin clientes; **71-91 MB** con 200 (de 512 MB) |
| **CPU** del proceso (`process.cpu.usage`, todos los núcleos) | 5,1 % al conectarse los 200; luego **0,1-0,3 %** |
| Hilos de plataforma | 33 sin clientes; 240 mientras Tomcat atendía las conexiones; 84 al final (los hilos virtuales de los clientes no cuentan aquí) |
| Ancho de banda | ~3 KB/s por cliente, ~0,6 MB/s en total |

El servidor apenas nota los 200 clientes: lo que más crece es el ancho de banda. El registro de la aplicación no tiene ni un `WARN` ni un `ERROR` de la API durante la prueba.

### Tiempos de respuesta con datos reales (primera llamada · siguientes)

`/api/nucleos` 761 ms · 6 ms (la primera calcula la extensión de cada núcleo sobre 1,8 M horas de paso) · `/api/nucleos/51/trenes` 22 ms (54 KB) · `/api/trenes/{id}` 42 ms · `/api/estaciones/71801` 137 ms · 91 ms · cercanas 53 ms · `/api/lineas/51/R4` 134 ms · 14 ms (45 KB) · `/api/avisos` 12 ms · `/api/docs` 525 ms (la primera vez genera la especificación).

### Lo que se vio con los datos reales

- **Barcelona-Sants a las 14:55:** 12 líneas y 15 próximos trenes, casi todos en directo; un R1 a Blanes con 29 min y un R4 con 25 min de retraso salían todavía como próximos (la ventana de 30 min atrás funciona). Cobertura por núcleo entre el 66,7 % (León) y el 100 %.
- **`trip_updates` y `vehicle_positions` no siempre coinciden:** en 4 de 99 trenes en marcha con horario, `retrasos` ya tenía cerradas (como inferidas) paradas por delante de donde `vehicle_positions` sitúa al tren. Por ejemplo, un R1 con 27 min en Pl. de Catalunya tenía cerradas hasta El Masnou: `trip_updates` saltó seis paradas en una muestra. No es sistemático (no pasa con los trenes muy retrasados en general), pero en la ficha se ven paradas pendientes con retraso final. Es el caso del ADR 0012 y refuerza la propuesta de la fase 3b: publicar las estadísticas sin inferidos y contarlos aparte.

## 4. Revisión independiente

Un subagente sin contexto de la conversación revisó el diff (`main...fase-4-api`) contra el encargo, solo leyendo el código (y el bytecode de `spring-webmvc` 7.1 con `javap`), sin ejecutar nada. Dio por buenas la hora programada en `retrasos` (horas > 24:00, cambio de hora, `ON CONFLICT` con `coalesce`, inferidas) y el SQL (sin inyección, listas vacías protegidas, índices que encajan). **Todo lo de gravedad alta y media se corrigió**, con prueba, en `bcba121`.

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (alta) | **Un cliente SSE que no lee podía bloquear la ingesta.** En Spring 7.1, `ResponseBodyEmitter.complete()` espera al mismo cerrojo que un `send()` en curso. Al llenarse la cola de un cliente atascado (ventana TCP llena), la ingesta llamaba a `cerrar()` → `complete()` dentro del cerrojo del canal y se quedaba esperando a que Tomcat agotara el tiempo de escritura: se paraban la ingesta, las altas al directo de ese núcleo y `GET /api/nucleos/{id}/trenes` | **Corregido.** `cerrar()` ya no toca el socket: da de baja la conexión, vacía la cola y deja la marca de fin; `complete()` lo hace solo el hilo virtual de la conexión al salir. Prueba: `DifusorDirectoTests` con un emisor cuyo `send` se atasca con el cerrojo cogido (como el de Spring): la ingesta publica más eventos de los que caben y termina en < 2 s, el cliente queda desconectado y la respuesta se cierra cuando el envío falla; lo mismo con el latido. **Con el código anterior, la prueba se queda bloqueada** |
| 2 (media) | La ficha de estación enseñaba como próximo un tren que ya había salido: la parada del feed es la actual **o la última**, y con esa secuencia se saltaba el filtro de la hora | **Corregido:** solo está «en la estación» si el feed dice `STOPPED_AT`. Prueba: `ProximosTrenesTests` (`IN_TRANSIT_TO` a las 10:12 con salida estimada 10:07: no sale; `STOPPED_AT`: sí) |
| 3 (media) | Caché de líneas sin límite: guardaba también las que no existen, con una clave que viene del cliente; y consultaba la base de datos dentro de `computeIfAbsent` | **Corregido:** no se guardan los fallos, la consulta va fuera del mapa y solo se guarda si la versión del horario no ha cambiado mientras tanto |
| 4 (media) | Un error inesperado (base de datos caída, consulta de más de 15 s) salía con el JSON de error de Boot, no como `ProblemDetail` | **Corregido:** `ErroresApi` atrapa `Exception` y responde un 500 `ProblemDetail` sin detalles internos (van al registro). Prueba en `ApiIntegracionTests`: con la tabla `avisos` renombrada, `/api/avisos` da un 500 `problem+json` sin SQL ni nombres de tablas |
| 5 (media) | El directo aceptaba conexiones durante el apagado, que el apagado ordenado esperaría | **Corregido:** después de `stop()`, 503 + `Retry-After: 5`. Prueba en `DifusorDirectoTests` |
| 6 | Un latido podía llegar antes que la instantánea | **Corregido:** el suscriptor entra en la lista de los latidos después de encolar su instantánea |
| 7 | Si algo fallaba tras contar la conexión (al serializar la instantánea), el contador no bajaba | **Corregido:** se deshace el alta |
| 8 | `EventSource` no reintenta tras un 503 e ignora `Retry-After` | **Documentado** en `docs/api/README.md` y en el §5: el frontend (fase 5) tiene que reabrir el directo |
| 9 | Un tren con más de 30 min de retraso no sale en los próximos de una estación | **Se deja y se documenta** en la descripción OpenAPI del endpoint: la ventana hacia atrás es una decisión (§2) |
| 10 | `/api/avisos` decía «en vigor» sin filtrar por fechas | **Corregido el texto:** son los avisos del último `alerts.pb` tal cual, con su inicio y fin en cada uno |
| 11 | Las cercanas no excluyen paradas de autobús | **Se deja:** `stops.txt` de Renfe solo trae estaciones (los autobuses de sustitución usan las mismas) |
| 12 | El rumbo podía salir negativo; la prueba admitía 360 | **Corregido:** `Math.floorMod`; la prueba ya solo admite 0-359 |
| 13 | springdoc 3.2.0 está compilado contra Boot 4.1 | **Se deja y se vigila:** todas las pruebas de OpenAPI pasan con Boot 4.2 (§2) |
| T | Pruebas que faltaban: cliente lento, apagado, próximos pasada la medianoche, 500 | **Añadidas** las cuatro (filas 1, 2, 4 y 5, y `pasadaLaMedianocheSalenLosViajesDelDiaAnterior`). Quedan como deuda: la prueba del filtro `linea` de `/api/avisos`, que la de avisos por estación dependa de que la muestra traiga alguno, y que `EjemplosApiTests` use su propio `JsonMapper` (con la misma configuración que la aplicación hoy) |

La prueba de carga (§3) se hizo antes de la corrección del hallazgo 1. La corrección no cambia el camino normal: solo quién llama a `complete()` al desconectar a un cliente.

## 5. Qué quedó fuera o pendiente

- **Varias instancias:** el directo y el estado viven en memoria (ADR 0008 y 0015). La guía de defensa explica cómo separar ingesta y API con un bus.
- **Ancho de banda del directo:** ~3 KB/s por cliente. Si crece, mandar solo los campos que cambian o comprimir.
- **La hora estimada no es una predicción:** es la programada más el retraso actual.
- **Un histórico por tren** (ficha de un tren que ya no está en directo).
- **`EventSource` no reintenta un 503:** el frontend tiene que volver a abrir el directo pasado el `Retry-After` (está en `docs/api/README.md`).

## 6. Qué cambiaría del plan

- **§6, fase 5:** el frontend tiene su contrato en `docs/api/` (ejemplos con datos reales, comprobados contra los records) y la forma de consumir el directo. Debe reabrir el `EventSource` tras un 503 y enseñar la antigüedad del latido.
- **§6, fase 6:** `retrasos.llegada_programada` está desde la V5 (hoy). Agrupar por `extract(hour from llegada_programada AT TIME ZONE 'Europe/Madrid')` y excluir las filas anteriores (`NULL`). Contar aparte los inferidos (ver §3).
- **§6, fase 7:** en el servidor, vigilar `trainwatch.api.directo.conexiones`, `.rechazadas`, `.desconectados` y `.reconexiones`; poner el *rate limiting* de Cloudflare delante de la API; y decidir si Swagger UI se queda público.
