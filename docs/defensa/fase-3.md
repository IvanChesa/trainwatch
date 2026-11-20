# Fase 3 · Ingesta en directo: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista. Es la fase que más preguntarán en un puesto de Java: **concurrencia, HTTP, resiliencia, estado y base de datos** en 2.000 líneas. Este código no lo has escrito tú: repásalo con esta guía delante y abre cada clase que se cita.

## 1. La foto en 30 segundos

> «Cada 7 segundos un `@Scheduled` pide a Renfe los tres feeds GTFS Realtime con peticiones condicionales: si no hay nada nuevo, Renfe contesta 304 y no se hace nada. Cada descarga va envuelta en un reintento con espera creciente y un circuit breaker de Resilience4j. Lo que llega se decodifica con la librería oficial de protobuf y se cruza con el horario importado: un mapa concurrente en memoria guarda el estado de cada tren (en origen, en marcha, terminado o cancelado) y su posición estimada sobre el recorrido. A la base de datos solo va el historial: las posiciones que cambian, en particiones diarias que se borran a las 48 h, y el retraso final de cada tren en cada parada, en particiones mensuales, que es la base de las estadísticas.»

## 2. Decisiones y por qué

| Decisión | Por qué | Alternativa que descarté |
|---|---|---|
| **Estado en memoria** ([ADR 0008](../adr/0008-estado-del-directo-en-memoria.md)) | La API lo leerá muchas veces por segundo y cada muestra se compara con la anterior | Una tabla `trenes_ahora` con UPSERT cada 7 s; Redis |
| **Un escritor, lectores sin bloqueo, `Tren` inmutable** | Sin carreras por diseño, no por cerrojos | Mapa mutable con `synchronized` en cada lectura |
| **Peticiones condicionales cada 7 s** | El feed cambia cada ~20 s: así el dato llega antes sin bajar 30 KB cada vez | Descargar a ciegas cada 20 s (llega hasta 20 s tarde) |
| **Resilience4j a mano, sin anotaciones** | Se ve el orden (reintento fuera, circuito dentro) y no depende de AOP ni de un *starter* para Boot 4 | `@Retry` y `@CircuitBreaker` con `resilience4j-spring-boot3` |
| **Particiones gestionadas por la aplicación** ([ADR 0009](../adr/0009-particiones-diarias-y-mensuales.md)) | `DROP` de un día es instantáneo; sin extensiones | `DELETE` + `VACUUM`; `pg_partman` |
| **Posición estimada con horario + retraso + recorrido** ([ADR 0010](../adr/0010-posicion-estimada-sobre-el-recorrido.md)) | El feed salta de estación en estación (fase 0) | Interpolar entre posiciones del feed |
| **Retraso final al pasar a la siguiente parada** | Renfe solo da el retraso de la próxima | Guardar todos los retrasos de cada muestra (~1 M filas al día) |
| **3 muestras nuevas y completas para desaparecer** | Una muestra llega a veces con un tercio de trenes menos (fase 0) | Quitar el tren en cuanto falta |

## 3. Cómo funciona por dentro

### El ciclo (`IngestaDirecto.ciclo`)

1. Refresca la caché del horario si hay una importación nueva o ha cambiado el día, y crea o borra particiones si toca (una vez por hora).
2. **`trip_updates`** → `EstadoDirecto.procesarRetrasos` → los retrasos finales cerrados van a `retrasos`.
3. **`vehicle_positions`** → `procesarPosiciones` → los trenes que han cambiado van a `posiciones`.
4. **`alerts`** → se sustituyen todos los avisos en una transacción.
5. Se reestima la posición de todos los trenes y se publica el resumen por núcleo.

Primero los retrasos, para que las posiciones usen el retraso de la misma muestra. Cada paso va en su `try/catch`: si falla un feed, los otros siguen; si cae la base de datos, el estado en memoria sigue y se cuenta el error.

### Peticiones condicionales (`ClienteFeeds`)

- La primera respuesta trae `ETag: "abc"` y `Last-Modified`. Se guardan por feed.
- Las siguientes peticiones mandan `If-None-Match: "abc"` e `If-Modified-Since`. Si el feed no ha cambiado, Renfe contesta **304 Not Modified sin cuerpo**: no se descarga ni se decodifica nada.
- Los validadores **solo se guardan si la respuesta se ha decodificado bien**: si llega un fichero roto, la próxima petición lo vuelve a pedir.
- Además, una muestra solo es «nueva» si la marca de la **cabecera** del feed es posterior a la anterior. Un 200 con datos viejos no cuenta.

**Pregunta típica:** *¿y si el servidor ignora `If-None-Match`?* Contesta 200 con el mismo `ETag`: el cliente lo compara y lo trata como sin cambios. Y si no hubiera `ETag`, la comprobación de la cabecera evita procesar la misma muestra dos veces.

### Tiempos de espera

- `connectTimeout(3 s)` para conectar y `sendAsync(...).get(5 s)` para toda la respuesta. **`HttpRequest.timeout` solo cubre hasta las cabeceras**: un servidor que manda las cabeceras y luego se queda colgado bloquearía el hilo para siempre. Lo descubrió la revisión de la fase 2.
- Si salta el tiempo, se cancela la petición (`futura.cancel(true)`).

### Resilience4j (reintento + circuit breaker)

```
Retry( CircuitBreaker( petición HTTP ) )
```

- **Reintento:** hasta 3 intentos, con espera de 0,5 s y luego 1 s (`IntervalFunction.ofExponentialBackoff`). **No reintenta** `CallNotPermittedException` (el circuito abierto) ni `InterruptedException`.
- **Circuit breaker**, uno por feed. Mira las últimas 10 llamadas (ventana por cuenta) y, desde 5, si falla el 50 % o más, **se abre**.
  - **Abierto:** durante 60 s ni se intenta: la llamada falla al momento con `CallNotPermittedException`. Renfe no recibe peticiones mientras está caído, y el hilo no espera 3 × 5 s en cada ciclo.
  - **Semiabierto:** a los 60 s deja pasar **una** llamada de prueba. Si va bien, se cierra; si no, vuelve a abrirse.
- **Por qué el reintento va fuera:** así cada intento cuenta en el circuito y, cuando se abre a mitad, el reintento se para en vez de seguir insistiendo.
- La prueba `ClienteFeedsTests.elCircuitoSeAbreTrasVariosFallosYDejaDeLlamar` lo demuestra: tras 5 peticiones fallidas, las 5 descargas siguientes no llegan al servidor.

**Pregunta típica:** *¿para qué el circuit breaker si ya hay reintentos?* El reintento resuelve fallos sueltos (un 502 de un segundo). Si Renfe está caído 10 minutos, reintentar 3 veces cada 7 s solo añade carga a un servidor que ya está mal y tiempo perdido en nuestro hilo. El circuito corta eso.

### El estado en memoria y la concurrencia (`EstadoDirecto`)

- `ConcurrentHashMap<String, Tren>`: la clave es el `trip_id`.
- **`Tren` es un `record` inmutable.** Cada muestra crea un `Tren` nuevo y hace `put`. Nunca se modifica uno que ya está en el mapa.
- **Un solo escritor:** el hilo de `@Scheduled` con `fixedDelay` (el siguiente ciclo empieza 7 s después de que **acabe** el anterior, así que nunca hay dos a la vez). Además, `procesarPosiciones`, `procesarRetrasos` y `reestimar` son `synchronized`, y `ciclo()` también, por si una prueba o un disparo manual los llama desde otro hilo.
- **Los lectores** (la API, el resumen, las métricas) no se bloquean. `ConcurrentHashMap` garantiza que un `get` ve un valor publicado entero (relación *happens-before* entre el `put` y el `get`). Como el `Tren` es inmutable y sus campos son finales, nadie ve uno a medio construir.
- **Lo que puede pasar:** durante el procesado, un lector puede ver unos trenes de la muestra anterior y otros de la nueva. Para un mapa que se refresca cada 7 s es aceptable. El resumen por núcleo se calcula al final del ciclo y se publica de golpe con un `AtomicReference`, así que su foto sí es coherente.
- **Lo que solo toca el escritor:** el seguimiento de `trip_updates` (un `HashMap` normal, siempre dentro del `synchronized`) y los detectores de muestras incompletas.
- **`volatile`:** la lista de cancelados y las marcas de la última muestra, que se leen desde otros hilos.
- **Dos hilos en el programador** (`spring.task.scheduling.pool.size=2`): por defecto Spring usa uno solo y la ingesta compartiría hilo con la importación nocturna.

**Preguntas típicas:**

- *¿Por qué no `Collections.synchronizedMap`?* Bloquea a los lectores mientras se escribe, y al iterar hay que sincronizar a mano.
- *¿Qué pasa si iteras el `ConcurrentHashMap` mientras se escribe?* Nada malo: sus iteradores son *weakly consistent*, no lanzan `ConcurrentModificationException` y pueden ver o no los cambios hechos después de empezar.
- *¿Y con dos instancias de la aplicación?* Cada una tendría su estado y las dos escribirían filas. Hoy es una sola instancia (ADR 0008). Con varias, haría falta elegir un líder (un *advisory lock* de PostgreSQL, como en la importación).

### Estados de un tren (`EstadoDirecto.clasificar`)

| Estado | Regla |
|---|---|
| `CANCELADO` | `trip_updates` lo marca `CANCELED`. **Solo eso**: un tren que no aparece no está cancelado |
| `EN_MARCHA` (sin horario) | Su viaje no está en el horario: no se sabe más. Se guarda con lo que trae (línea de la etiqueta `C3-17907`, núcleo de las dos primeras cifras del `trip_id`) |
| `TERMINADO` | Su parada (la actual o la última) es la última del viaje |
| `EN_ORIGEN` | Está en la primera parada (o no se sabe en cuál) y aún no ha llegado su hora de salida **más el retraso** |
| `EN_MARCHA` | Todo lo demás |

### Muestras incompletas y desapariciones

- `DetectorMuestrasIncompletas`: si una muestra trae menos del **80 %** de las entidades de la última completa, es incompleta. Sus trenes se actualizan, pero **los que faltan no suman ausencias**.
- Si llegan **3 incompletas seguidas**, ese nivel bajo se acepta como el nuevo normal (un núcleo que deja de emitir, el final del servicio).
- Un tren desaparece tras faltar en **3 muestras nuevas y completas seguidas** (unos 60 s). Si vuelve a aparecer, el contador se reinicia.
- Una muestra repetida (misma cabecera) no cuenta.

### El retraso final por parada (lo más delicado)

Renfe da el retraso **solo de la próxima parada**, en minutos, y sin `stop_sequence`. El algoritmo, por viaje:

1. Se guarda `(próxima parada, índice en el viaje, último retraso)`.
2. En cada muestra nueva:
   - si la próxima parada **es la misma**, se sustituye el retraso por el nuevo;
   - si **ha pasado a una parada posterior**, el último retraso conocido de la anterior es su **retraso final** → una fila en `retrasos`.
3. **El índice** se busca por `stop_id` empezando por la parada que ya se tenía, porque en una línea circular la misma estación sale dos veces.
4. **La última parada** no tiene «siguiente»: se cierra cuando el viaje desaparece de `trip_updates` (3 muestras completas) estando en ella.

**Casos límite que hay que saber contar:**

- **Un viaje desaparece a mitad de recorrido:** no se cierra nada; no se inventa un retraso de una parada por la que no se sabe si pasó.
- **La parada vuelve atrás** (pasa en el feed real): no se cierra; se cuenta en `trainwatch.directo.retrasos.sin.cerrar`.
- **Se salta paradas** (el feed estuvo caído): solo se cierra la que se conocía; las intermedias quedan sin dato.
- **Cancelado:** lo pendiente se descarta.
- **Sin horario:** no hay `stop_sequence`, así que no se guarda (son unos pocos `SPECIAL_…`).
- **Reinicio:** se pierde lo pendiente. Si una parada se cerrara dos veces, la clave primaria `(fecha_servicio, trip_id, stop_sequence)` con `ON CONFLICT DO UPDATE` deja la última.
- **Fecha de servicio:** sale del calendario. Renfe publica un `service_id` por núcleo y día, así que la fecha es la de su calendario, no la del reloj: un tren de las 23:50 que llega a las 00:20 sigue siendo del día anterior.

### Posición estimada y rumbo (`EstimadorPosicion`)

Ver el [ADR 0010](../adr/0010-posicion-estimada-sobre-el-recorrido.md). En una frase: *«con la hora, sé qué parte del tramo entre su última parada y la siguiente lleva recorrida; con la proyección de las paradas sobre el recorrido, convierto esa parte en un punto del recorrido y en un rumbo»*. Detalles:

- **Recorrido al revés** (`forma_sentido = -1`): el avance se mide en el sentido del viaje y se convierte a fracción del dibujo con `1 − avance`; el rumbo se gira 180°.
- **Circular** (`shapes.cerrado`): de 0,9 a 0,1 hay 0,2 de avance (módulo 1).
- **Rumbo**: el ángulo inicial del círculo máximo entre los dos puntos del tramo, `atan2(sen Δλ · cos φ2, cos φ1 · sen φ2 − sen φ1 · cos φ2 · cos Δλ)`, en grados desde el norte.

### Particiones

Ver el [ADR 0009](../adr/0009-particiones-diarias-y-mensuales.md). Lo que hay que saber decir:

- **Particionado declarativo:** la tabla madre no guarda filas; PostgreSQL manda cada `INSERT` a la partición cuyo rango contiene el valor.
- **Por qué `DROP` y no `DELETE`:** `DELETE` marca filas como muertas, que luego tiene que limpiar `VACUUM`, y los índices se hinchan. `DROP TABLE` de una partición borra sus ficheros: instantáneo.
- **Por qué no hay partición por defecto:** si existe una `DEFAULT` con filas de mañana, PostgreSQL no deja crear la partición de mañana sin moverlas. Se prefiere que falle y se vea.
- **La clave primaria tiene que incluir la columna de partición.** Por eso `retrasos` tiene `(fecha_servicio, trip_id, stop_sequence)`.

### Métricas

| Métrica | Qué mide |
|---|---|
| `trainwatch.directo.antiguedad{feed}` | Segundos desde la cabecera de la última muestra nueva. Si sube de ~60 s, Renfe ha dejado de publicar |
| `trainwatch.directo.descargas{feed,resultado}` | 200, 304, `error`, `circuito_abierto` |
| `trainwatch.directo.errores{feed,tipo}` | `descarga`, `circuito_abierto`, `procesado`, `base_de_datos`, `resumen` |
| `trainwatch.directo.trenes{estado}` · `trenes.sin.horario` | Trenes por estado y sin viaje en el horario |
| `trainwatch.directo.filas{tabla}` | Filas escritas en `posiciones`, `retrasos` y `avisos` |
| `trainwatch.directo.muestras{feed,completa}` | Muestras nuevas, completas o no |
| `resilience4j_circuitbreaker_state` · `resilience4j_retry_calls` | Las de Resilience4j, con `name=feed-…` |

## 4. Preguntas que pueden hacerte

- **¿Qué pasa si Renfe tarda 30 s en contestar?** A los 5 s se corta. Se reintenta dos veces y, si sigue igual, el circuito se abre y no se vuelve a llamar en 60 s. El mapa sigue con lo último y `antiguedad` sube: la web (fase 5) dirá «datos de hace X min».
- **¿Por qué `fixedDelay` y no `fixedRate`?** Con `fixedRate`, si un ciclo tarda más que el intervalo, el siguiente sale enseguida y se acumulan. Con `fixedDelay` la pausa cuenta desde el final del anterior.
- **¿Cómo pruebas el circuit breaker?** Con un servidor HTTP local (`com.sun.net.httpserver.HttpServer`) que contesta 500, y contando cuántas peticiones le llegan.
- **¿Cómo pruebas sin depender de Renfe?** Con 4 muestras reales grabadas en `src/test/resources/gtfsrt/`, servidas por ese servidor local, y un reloj que la prueba mueve a mano (`Clock` inyectado).
- **¿Por qué guardas las posiciones si ya tienes el estado en memoria?** Para la ficha del tren (su recorrido de hoy), para depurar y para comprobar las estadísticas contra los datos sin procesar (fase 6).
- **¿Cuánto ocupa?** ~0,26 M filas al día de posiciones y ~51.000 de retrasos (fase 0). La prueba real de 10 minutos lo confirma a escala (ver el informe).
- **¿Qué cambiarías?** El estado se pierde al reiniciar (se podría reconstruir desde `posiciones`). Y con varias instancias, un líder con *advisory lock*.

## 5. Dónde mirar en el código

| Qué | Dónde |
|---|---|
| El ciclo, las métricas y el resumen por núcleo | `directo/IngestaDirecto.java` |
| HTTP, 304, tiempos y Resilience4j | `directo/ClienteFeeds.java` |
| Estado, estados, desapariciones y retraso final | `directo/EstadoDirecto.java` |
| Muestras incompletas | `directo/DetectorMuestrasIncompletas.java` |
| Posición estimada y rumbo | `directo/EstimadorPosicion.java`, `directo/Recorrido.java` |
| Horario y cachés | `directo/HorarioDirectoJdbc.java` |
| Particiones | `directo/GestorParticiones.java`, `db/migration/V3__…sql` |
| Pruebas | `EstadoDirectoTests` (unitarias), `ClienteFeedsTests` (HTTP), `DirectoIntegracionTests` (Testcontainers) |
