# Fase 4 · API y directo: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista: **REST, OpenAPI, SSE, control de la carga y cómo escalaría**. Este código no lo has escrito tú: repásalo con esta guía delante y abre cada clase que se cita (todas en `backend/src/main/java/com/ivanchesa/trainwatch/api/`).

## 1. La foto en 30 segundos

> «La API es Spring MVC, pública y de solo lectura. Lo que está en directo (los trenes, su posición, su retraso) no se consulta en la base de datos: sale del estado en memoria que mantiene la ingesta. Lo demás (estaciones, líneas, avisos, la evolución del retraso de un viaje) son consultas SQL a PostgreSQL con PostGIS. Las respuestas son records propios, documentados con OpenAPI, y los errores siguen el RFC 9457 (`ProblemDetail`). El mapa recibe el directo por Server-Sent Events: al conectarse, una instantánea con todos los trenes del núcleo; después, cada 7 segundos, solo lo que ha cambiado. La ingesta no escribe nunca en un socket: deja cada evento, serializado una vez, en la cola de cada cliente, y un hilo virtual por cliente lo envía. Hay un máximo de conexiones, una cola acotada para los clientes lentos y un latido cada 15 segundos. En local aguanta 200 clientes a la vez sin despeinarse.»

## 2. Decisiones y por qué

| Decisión | Por qué | Alternativa que descarté |
|---|---|---|
| **SSE para el directo** ([ADR 0015](../adr/0015-sse-para-el-directo.md)) | El navegador solo recibe; es HTTP normal (pasa por Cloudflare), `EventSource` se reconecta solo | WebSocket (bidireccional, más piezas), sondeo cada 7 s |
| **Instantánea + cambios; al reconectarse, instantánea nueva** | Casi todos los trenes cambian en cada ciclo: reenviar lo perdido ocupa como una instantánea y exige historial | Reenviar los cambios desde `Last-Event-ID` |
| **Un hilo virtual y una cola acotada por cliente** | Un cliente lento solo se retrasa a sí mismo; la ingesta nunca se bloquea | Enviar desde el hilo de la ingesta; WebFlux |
| **Directo desde memoria, resto desde PostGIS** ([ADR 0016](../adr/0016-api-rest-desde-el-estado-en-memoria.md)) | Lo vivo ya está en memoria y coincide con el SSE; lo estático es SQL con índices espaciales | Leer `posiciones` (más lento y desfasado) |
| **Records propios, no entidades** | El contrato no depende del esquema; hay campos calculados (próxima parada, hora estimada) | Spring Data REST |
| **`ProblemDetail` (RFC 9457)** | Estándar, viene en Spring, el cliente distingue errores por `status` y `title` | Un JSON de error propio |
| **OpenAPI con springdoc; ejemplos de `docs/api/`** | Un solo ejemplo por endpoint que sirve de contrato al frontend y de ejemplo en Swagger, comprobado por una prueba | Ejemplos escritos a mano en anotaciones |
| **Caché HTTP según lo que cambia** | Líneas y estaciones cercanas con `ETag` y `max-age`; lo vivo, segundos | Sin caché, o caché larga para todo |
| **Hora programada en `retrasos`** ([ADR 0014](../adr/0014-hora-programada-en-retrasos.md)) | La fase 6 agrupa por franja horaria y el horario de ese día se borra cada noche | Recalcularla en la fase 6 (imposible para días pasados) |

## 3. Cómo funciona por dentro

### Los endpoints

| Endpoint | De dónde sale | Caché |
|---|---|---|
| `GET /api/nucleos` | Rectángulo de las estaciones de cada núcleo (SQL, en memoria hasta el horario siguiente) + estado y cobertura del resumen de la ingesta | 10 s |
| `GET /api/nucleos/{id}/trenes` | La última foto del difusor SSE (la misma que la instantánea) | 5 s |
| `GET /api/trenes/{id}` | El tren en memoria + `retrasos` de ese viaje y día (la evolución) + avisos de su ruta | 5 s |
| `GET /api/estaciones/{id}` | La estación, sus líneas, los pasos del horario de ayer y hoy en la próxima hora cruzados con el directo, y sus avisos | 15 s |
| `GET /api/estaciones?cerca=lat,lon&radio=` | PostGIS: `ST_DWithin` filtra y `<->` ordena, sobre el índice GiST de `ubicacion::geography` | 1 h + `ETag` |
| `GET /api/lineas/{nucleo}/{linea}` | Rutas de la línea, sus recorridos con `ST_AsGeoJSON` y sus paradas en el orden del recorrido más usado (en memoria) + avisos | 1 min + `ETag` |
| `GET /api/avisos?nucleo=&linea=&estacion=` | `avisos` (lo que dejó el último `alerts.pb`), cada aviso entero | 30 s |
| `GET /api/directo?nucleo=` | SSE (`DifusorDirecto`) | — |
| `GET /api/docs` · `/api/docs/ui` | OpenAPI 3 (springdoc) · Swagger UI | — |

**Detalles que te pueden preguntar:**
- **La próxima parada** es la siguiente a la del feed, porque el `stop_id` de `vehicle_positions` es la parada actual o la última, no la próxima (fase 0). La **hora estimada** es la programada más el retraso actual: no es una predicción, Renfe solo da el retraso para la próxima parada.
- **Próximos trenes de una estación:** horario de **ayer y hoy** (un viaje de las 23:50 pasa a las 24:10 «de ayer»), de 30 min atrás a 60 min adelante. Un tren en directo que ya ha dejado atrás la estación no sale; uno que no está en directo sale hasta un minuto después de su hora.
- **Zoom del núcleo:** el que hace caber el rectángulo de sus estaciones en un mapa de 800 × 600 px. MapLibre usa teselas de 512 px: a zoom *z* el mundo mide 512 · 2^*z* px; el alto se cuenta en Mercator.

### Errores (`ErroresApi`, `Problemas`)

- Los propios son `ErrorResponseException` (404, 400 con la propiedad `parametro`, 503 con `Retry-After`); Spring los convierte en `ProblemDetail`.
- Los de Spring (parámetro que falta, tipo incorrecto, ruta inexistente) pasan por `ResponseEntityExceptionHandler`; se traduce el título y el detalle.
- El `Content-Type: application/problem+json` se fija en la respuesta: si no, a un `EventSource` (que solo acepta `text/event-stream`) no le llegaría el 503 con su cuerpo.

### El directo por SSE (`DifusorDirecto`)

1. La ingesta termina un ciclo y publica un **evento de Spring** (`CicloDirectoTerminado`). Así la ingesta no conoce la API.
2. `alTerminarCiclo` convierte los trenes a `TrenMapa` y, **por núcleo**, compara con la foto anterior: altas, cambios (el tren entero) y bajas. Si hay algo, sube la **secuencia**, serializa el evento **una vez** y lo deja en la cola de cada suscriptor.
3. Cada suscriptor tiene una `ArrayBlockingQueue` de 50 eventos y **un hilo virtual** que hace `take()` y `emitter.send(...)`. Si la cola se llena (cliente lento), se le desconecta; vuelve solo con una instantánea.
4. **Coherencia:** calcular y encolar los cambios, y dar de alta a un suscriptor (encolar su instantánea y apuntarlo), se hacen con el **cerrojo del canal** del núcleo. Así nadie recibe la instantánea 7 y luego los cambios 7, ni se pierde los 8.
5. **Latido** cada 15 s desde un hilo propio: mantiene viva la conexión (Cloudflare corta a los 100 s sin datos), detecta clientes muertos (el envío falla) y da la antigüedad del dato.
6. **Límites:** máximo de conexiones (503 + `Retry-After: 30`), duración máxima de 30 min (el navegador se reconecta solo, con un `retry:` de 3-6 s con azar), y al apagar se cierran todas antes del apagado ordenado del servidor web (`SmartLifecycle` con la fase más alta).
7. Las coordenadas se redondean a 5 decimales (~1 m) y el rumbo a grados: más precisión no se ve y haría «cambiar» trenes parados por ruido.

### Por qué un hilo virtual por cliente no es un problema

- Un hilo virtual cuesta unos cientos de bytes mientras espera en `take()`; 200 (o 10.000) no son nada. Se bloquea en el socket sin ocupar un hilo del sistema operativo.
- Las conexiones SSE son **asíncronas** en Tomcat (`SseEmitter`): el hilo de Tomcat vuelve al pool en cuanto el controlador devuelve el emisor.
- Desde Java 24 (JEP 491), un `synchronized` ya no «clava» el hilo virtual a su hilo del sistema.

### CORS

- Solo `https://trainwatch.ivanchesa.com` y `http://localhost:5173`, solo `GET`/`HEAD`/`OPTIONS`, sin credenciales. Admite la cabecera `Last-Event-ID`. Otro origen recibe un 403 en el *preflight*.
- En local, Vite hace de proxy de `/api` (mismo origen) y CORS ni se usa; en producción la web y la API están en dominios distintos y sí.

## 4. La prueba de carga

`backend/carga/CargaSse.java`: un programa de un fichero (JDK 25, sin herramientas nuevas) que abre N clientes SSE en hilos virtuales, mide la latencia de cada evento `cambios` (de su campo `generado` a la llegada), comprueba que las secuencias llegan seguidas y lee CPU, heap e hilos del servidor en `/actuator/metrics`. Las cifras están en el [informe](../informes/fase-4.md#3-cifras).

Qué decir: «200 clientes es el criterio del plan. Lo importante no es el número, sino que el coste por cliente es una cola y un hilo virtual, que el evento se serializa una vez y que un cliente lento no frena a nadie.»

## 5. Cómo escalaría a más usuarios

En orden, de lo más barato a lo más caro:

1. **Más conexiones en la misma instancia.** Subir `maximo-conexiones`. El coste por cliente es memoria de la cola y del socket; el ancho de banda (eventos × clientes) es el límite real antes que la CPU.
2. **Cloudflare delante** (PLAN §5): las respuestas REST con `Cache-Control: public` y `ETag` se sirven desde la CDN. El SSE no se cachea, pero pasa.
3. **Varias instancias.** El problema: cada instancia tiene su estado en memoria y su difusor.
   - Opción simple: **una ingesta por instancia** (cada una lee Renfe). Funciona, pero multiplica las peticiones a Renfe y dos instancias pueden diferir unos segundos.
   - Opción buena: **separar ingesta y API.** Una sola instancia de ingesta (o una elegida con un cerrojo en PostgreSQL) publica la foto de cada ciclo en un **bus**: Redis pub/sub, PostgreSQL `LISTEN/NOTIFY` (ya está, sin piezas nuevas) o Kafka si hiciera falta historial. Las instancias de la API se suscriben, guardan la última foto y difunden por SSE. El balanceador no necesita sesiones pegajosas: cualquier instancia sirve cualquier núcleo, y si una cae, el navegador se reconecta a otra y recibe una instantánea.
   - La **duración máxima** de 30 min reparte las conexiones entre instancias nuevas.
4. **Muchísimos clientes:** un servicio gestionado de *pub/sub* por HTTP (o un proxy SSE como Mercure o Nchan) y la API solo publica.

## 6. Preguntas que pueden hacerte

- **¿Por qué SSE y no WebSocket?** Comunicación en un sentido, HTTP normal, reconexión automática con `Last-Event-ID`, nada nuevo que configurar en Cloudflare. WebSocket aporta el canal de vuelta, que no se usa.
- **¿Qué pasa si un cliente va lento?** Se le acumulan eventos en su cola; a los 50 se le desconecta. Los demás no se enteran: cada uno tiene su hilo. Y quien lo desconecta no toca su socket: solo le deja una marca de fin (§8, el fallo que encontró la revisión).
- **¿Y si se reconecta?** Manda `Last-Event-ID`; recibe una instantánea nueva. ¿Por qué no lo que se perdió? Porque casi todos los trenes cambian en cada ciclo: ocuparía lo mismo y habría que guardar historial.
- **¿Cómo evitas que un cliente vea la instantánea y luego cambios duplicados o con hueco?** El alta del suscriptor y la publicación de cambios van con el mismo cerrojo del canal; la prueba de carga comprueba que no hay huecos de secuencia.
- **¿Qué es `ProblemDetail`?** El formato estándar de error HTTP (RFC 9457): `type`, `title`, `status`, `detail`, `instance` y propiedades propias.
- **¿Cómo documentas la API?** springdoc genera OpenAPI 3 de los controladores y los records; los ejemplos salen de `docs/api/` y una prueba comprueba que encajan.
- **¿Qué cachearías?** Lo que no cambia (líneas, estaciones cercanas) con `max-age` y `ETag`; lo vivo, segundos. Todo `public` porque no hay datos personales.
- **¿Por qué no hay autenticación?** Es pública y de solo lectura; el único endpoint que escribe (la importación manual) tiene su token. Para abuso: el máximo de conexiones, y en producción el *rate limiting* de Cloudflare.
- **¿Cómo sabe el mapa que los datos están viejos?** El latido trae `antiguedadS`; `/api/directo/estado` también.
- **¿Qué es la hora programada en `retrasos` y por qué ahora?** Para las estadísticas por franja; cuanto antes se guarde, antes hay histórico completo. Cuidado con las horas de más de 24:00 y el cambio de hora: se cuenta desde «mediodía menos 12 h».

## 7. Dónde mirar en el código

| Qué | Dónde |
|---|---|
| Endpoints | `NucleosController`, `TrenesController`, `EstacionesController`, `LineasController`, `DirectoSseController` |
| Contrato | `Respuestas` (records con `@Schema`) y `docs/api/*.json` |
| Del `Tren` al mapa | `VistaTrenes` |
| SQL y PostGIS | `ConsultasApi` |
| SSE | `DifusorDirecto` (y `CicloDirectoTerminado` en la ingesta) |
| Errores | `ErroresApi`, `Problemas`, `RespuestasDeError` |
| CORS y `ETag` | `ApiConfig` |
| OpenAPI | `OpenApiConfig` |
| Hora programada | `EstadoDirecto.cierre`, `RepositorioDirecto.guardarRetrasos`, `V5__retrasos_llegada_programada.sql` |
| Pruebas | `ApiIntegracionTests`, `EjemplosApiTests`, `DifusorDirectoTests`, `ProximosTrenesTests`, `EstadoDirectoTests.HoraProgramadaEnRetrasos`, `backend/carga/CargaSse.java` |

## 8. El fallo que encontró la revisión: un cliente lento bloqueaba la ingesta

**Qué pasaba.** Cada conexión tiene su cola y su hilo virtual, precisamente para que un cliente lento no frene a nadie. Pero al llenarse la cola de un cliente, la ingesta lo desconectaba llamando a `cerrar()`, y `cerrar()` llamaba a `emitter.complete()`. En Spring 7.1, `complete()` espera al mismo cerrojo que `send()`, y el hilo del cliente atascado (con la ventana TCP llena) tenía ese cerrojo cogido, bloqueado escribiendo en el socket. Resultado: **la ingesta se quedaba esperando** hasta que Tomcat agotara el tiempo de escritura, dentro del cerrojo del canal; con ella se paraban la grabación en la base de datos, las altas al directo de ese núcleo y `GET /api/nucleos/{id}/trenes`. Justo el caso para el que estaba la cola.

**Por qué no lo vieron las pruebas ni la de carga.** Los clientes de prueba leen siempre: la cola nunca se llena. Hace falta un cliente que deje de leer.

**Cómo se corrigió.** Una regla: **solo el hilo de la conexión toca su socket.** `cerrar()` (que puede llamar cualquiera: la ingesta, el latido, Tomcat) ya no llama a `complete()`; solo da de baja la conexión, vacía la cola y deja una marca de fin. El hilo virtual de la conexión, al salir de su bucle (por la marca o porque el envío falla), llama él mismo a `complete()`. Si ese hilo está atascado, el único que espera es él.

**La prueba.** `DifusorDirectoTests` usa un emisor cuyo `send` se queda atascado con un cerrojo cogido y cuyo `complete` espera ese cerrojo, como el de Spring. La ingesta publica más eventos de los que caben en la cola y tiene que terminar en menos de 2 s; el cliente queda desconectado; cuando el envío por fin falla, la respuesta se cierra. Con el código anterior, la prueba se queda bloqueada.

**Lo que hay que saber decir.** «Separar la cola por cliente no basta: hay que mirar qué operaciones toman el mismo cerrojo que la escritura. En `ResponseBodyEmitter`, `send` y `complete` lo comparten. Por eso solo el hilo dueño de la conexión la cierra, y los demás solo le dejan un mensaje.»

**Lo demás que corrigió la revisión:** un tren que ya había salido seguía como próximo en su estación (ahora solo cuenta «en la estación» con `STOPPED_AT`); la caché de líneas guardaba también las inexistentes con una clave del cliente (crecía sin límite); los errores inesperados no salían como `ProblemDetail` (ahora un 500 sin detalles internos); el directo aceptaba conexiones durante el apagado (ahora 503); y tres detalles (latido antes de la instantánea, contador que no bajaba si fallaba el alta, rumbo negativo). Todo en el [informe](../informes/fase-4.md#4-revisión-independiente).
