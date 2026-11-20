# ADR 0013 · Cada muestra se calcula primero y se aplica al final; las escrituras esperan en una cola

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 3b · **Completa:** ADR 0008

## Contexto

La revisión de la fase 3 encontró que, si la base de datos fallaba **a mitad** de una muestra (`horario.viaje()` se consulta dentro del bucle), el estado quedaba a medias:

- la cabecera de la muestra y el `ETag` ya habían avanzado, así que la muestra no se volvía a procesar;
- el seguimiento de los viajes anteriores en la muestra ya se había movido;
- la lista local de retrasos cerrados se perdía (y el estado ya no los volvería a cerrar);
- los cambios de posición ya aplicados en memoria no se escribían.

Además, sin tiempos máximos en la conexión, una base de datos colgada podía dejar el hilo de la ingesta parado, y `/api/directo/estado` enseñaba la antigüedad calculada en el último ciclo: una ingesta parada no se notaba.

## Decisión

1. **Tres pasos por muestra** en `EstadoDirecto`:
   - **resolver** los viajes en el horario (la única parte que lee la base de datos), con un `try` por viaje: el que falla **se salta** (cuenta como visto, sin cambios, con las ausencias a cero) y se cuenta en `trainwatch.directo.viajes.sin.resolver`. **Tras el primer fallo, en esa muestra ya no se pregunta a la base de datos:** los viajes en caché se resuelven y el resto se da por no resuelto. Si no, con la base de datos colgada, cada viaje sin caché esperaría su plazo y la ingesta se pararía minutos (lo vio la revisión de la fase 3b);
   - **calcular** el estado nuevo sobre copias (seguimiento, trenes, retirados, evaluación del detector de incompletas), también con un `try` por viaje: un viaje con datos raros se salta y se cuenta, en vez de bloquear el feed. Si el viaje cambió de versión en el horario, el seguimiento se vuelve a situar por parada;
   - **aplicar** al final, de golpe: estado, detector y cabecera. Si algo falla antes, el estado queda como estaba.
2. **El `ETag` se confirma al final:** `ClienteFeeds.descargar` ya no guarda los validadores; lo hace `ClienteFeeds.confirmar`, que la ingesta llama tras procesar la muestra. Si el procesado falla, la petición siguiente vuelve a traer la misma muestra y, como la cabecera no avanzó, se procesa.
3. **Las escrituras esperan en una cola** si la base de datos falla: `retrasos` (como mucho 100.000) y, ahora también, `posiciones` (50.000, unas 2 h a mediodía). Se reintentan en cada ciclo, **en una transacción** (sin duplicados al reintentar). Si se llena, se descartan las más viejas; si una fila no cabe en ninguna partición, se aparta. Las dos cosas se cuentan (`trainwatch.directo.filas.descartadas`). Los avisos no se dan por recibidos (ni se confirma su `ETag`) hasta escribirlos.
4. **Tiempos máximos en todos los perfiles** (`application.properties`): Hikari espera una conexión 5 s; el driver, 5 s para conectar y 30 s de `socketTimeout`; PostgreSQL, `statement_timeout` de 15 s en cada sesión. La importación del horario los amplía (15 min) solo en sus pasos pesados.
5. **La antigüedad se calcula al responder** (`ResumenDirecto.alInstante`), con el último dato del estado en memoria: si la ingesta se para, `antiguedadS` sigue creciendo.
6. **Flyway** quita el `statement_timeout` en sus conexiones (`spring.flyway.init-sqls`): una migración pesada no tiene que chocar con los 15 s.

## Alternativas

- **Si falla un solo viaje, rechazar la muestra entera** (y reprocesarla): más «puro», pero con la base de datos caída la ingesta se pararía del todo, también para los ~400 trenes cuyo viaje ya está en caché. El mapa en directo se congelaría justo cuando más se nota.
- **Transacción de base de datos alrededor de cada muestra:** no resuelve nada, porque el estado vive en memoria (ADR 0008).
- **No aplicar el estado hasta escribir las filas:** igual que la primera alternativa, congela el mapa mientras la base de datos esté caída. La cola deja el mapa al día y las filas a salvo.
- **`statement_timeout` corto también en la importación:** rompería la importación en un servidor pequeño (crear índices sobre 1,8 M horas de paso).

## Consecuencias

- **Saltar un viaje no pierde datos:** en la muestra siguiente, el viaje se pone al día y las paradas que pasó mientras tanto se cierran (ADR 0012).
- **Un reinicio con filas en la cola las pierde**, como antes con los retrasos pendientes. Es memoria, no disco.
- **Los lectores pueden ver** unos trenes de la muestra anterior y otros de la nueva mientras se aplica (como antes), nunca un tren a medias. Hay una prueba con cuatro lectores y un escritor.
- **Una consulta de la aplicación que pase de 15 s falla.** Si la fase 4 o la 6 necesitan una más larga (un informe, una estadística), la amplían en su transacción, como la importación.
