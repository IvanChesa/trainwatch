# Fase 2 · GTFS estático: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista. Es la primera con lógica de verdad: una tubería de datos que carga 1,8 M filas cada noche sin que la aplicación se entere.

## 1. La foto en 30 segundos

> «Cada noche, un trabajo de Spring Batch descarga el horario de Renfe con una petición condicional (si no ha cambiado, Renfe contesta 304 y no se hace nada). Lee el ZIP en streaming, sin descomprimirlo a disco, y carga cada fichero con `COPY` de PostgreSQL por lotes en un esquema aparte. Al terminar crea los índices, proyecta cada parada sobre el recorrido de su línea con PostGIS, valida el resultado y cambia el esquema nuevo por el viejo renombrándolos en una transacción. Si algo falla, el horario anterior sigue intacto. Con el fichero real son unos 15 s para 1,8 M horas de paso.»

## 2. Decisiones y por qué

| Decisión | Por qué | Alternativa que descarté |
|---|---|---|
| **Spring Batch, un paso por fase** ([ADR 0007](../adr/0007-spring-batch-con-copy-y-repositorio-en-memoria.md)) | Pasos con nombre, transacción por lote, flujo condicional (`SIN_CAMBIOS`) y el estado de cada paso registrado solo | Un `@Scheduled` con JDBC a pelo |
| **`COPY` por lotes de 10.000 filas** | Es la vía más rápida de PostgreSQL para cargas masivas: `stop_times` (1,8 M filas) en ~4 s | `JdbcBatchItemWriter` (INSERT por lotes): bien, pero varias veces más lento |
| **Esquema de carga + renombrado** ([ADR 0006](../adr/0006-cambio-atomico-del-horario-por-esquemas.md)) | El cambio es una fila del catálogo: milisegundos, atómico y sin bloquear a los lectores durante la carga | `TRUNCATE` + carga (bloquea 15 s), `DELETE` + carga (hincha las tablas), columna de versión |
| **Índices después de cargar** | Construir un índice de una vez es mucho más rápido que mantenerlo fila a fila (3,3 s para todos) | Tablas con índices desde el principio |
| **Flyway define el esquema; la importación lo copia** (`LIKE … INCLUDING ALL`, `pg_get_indexdef`) | Una sola definición: una migración futura se propaga sola a la siguiente carga | Repetir el DDL en un fichero aparte para la carga |
| **`geometry(…, 4326)` + índice sobre `::geography`** ([ADR 0005](../adr/0005-geometry-4326-con-indice-geography.md)) | `ST_LineLocatePoint` solo existe para `geometry`; las distancias en metros usan el índice de expresión | Todo `geography`, o UTM (España tiene tres husos) |
| **Repositorio de Batch en memoria + tabla propia** | La importación siempre empieza de cero (15 s): no hay nada que reanudar. El historial útil (ETag, vigencia, filas) va en `importacion_horario` | `spring-boot-starter-batch-jdbc` y sus seis tablas `BATCH_*` |
| **Token en cabecera para el disparo manual** | Un único endpoint que escribe; comparación en tiempo constante; sin token, 404 | Spring Security (cuando haya más), o un comando (arrancaría otra JVM entera junto a la que ya está en marcha) |
| **Validar antes de cambiar** | Un fichero cortado no sustituye a uno bueno: se rechaza si trae < 50 % de los viajes actuales o alguna tabla vacía | Confiar en el fichero |

## 3. Cómo funciona por dentro

### Spring Batch en cuatro conceptos

- **`Job`**: el trabajo entero (`importacionHorario`). Se lanza con `JobOperator.start(job, parametros)`; los parámetros (ETag anterior, `forzar`, id del registro) lo identifican.
- **`Step`**: cada fase. Dos tipos:
  - **tasklet**: una operación que se ejecuta una vez dentro de una transacción (descargar, crear índices, el cambio de esquema);
  - **por lotes** (*chunk-oriented*): un `ItemReader` lee de uno en uno, un `ItemProcessor` convierte y un `ItemWriter` escribe cada lote de N elementos en una transacción. Aquí: `LectorCsvZip` → `TablaGtfs.mapeo` → `EscritorCopy`.
- **`ExecutionContext`**: un mapa por trabajo y por paso donde los pasos se dejan datos. La descarga deja la ruta del ZIP; el lector la recoge en `beforeStep`; la validación deja las filas y la vigencia, que lee `ImportadorHorario` para el registro.
- **`ExitStatus` y flujo**: la descarga sale con `SIN_CAMBIOS` si Renfe contesta 304, y el flujo (`.on("SIN_CAMBIOS").end("SIN_CAMBIOS")`) termina ahí. Cualquier excepción en un paso deja el trabajo `FAILED` y los pasos siguientes no se ejecutan.

Ojo con Spring Batch 6 (Spring Boot 4): los paquetes cambiaron (`org.springframework.batch.core.job.Job`, `…core.step.Step`, `org.springframework.batch.infrastructure.item.*`), `JobOperator` sustituye a `JobLauncher` y `chunk(n).transactionManager(tm)` crea el nuevo `ChunkOrientedStep`.

### El cambio atómico

1. `preparar`: `DROP SCHEMA IF EXISTS horario_carga CASCADE` (restos de un fallo anterior) y `CREATE TABLE horario_carga.x (LIKE horario.x INCLUDING ALL EXCLUDING INDEXES)` por cada tabla.
2. Carga, índices, derivados y validación, todo en `horario_carga`. La aplicación sigue leyendo `horario` sin enterarse.
3. `cambio`, en una transacción: `ALTER SCHEMA horario RENAME TO horario_anterior; ALTER SCHEMA horario_carga RENAME TO horario;`.
4. `limpieza`, aparte: `DROP SCHEMA horario_anterior CASCADE`.

**Lo que tienes que saber decir:**

- Renombrar es cambiar una fila de `pg_namespace`: no mueve datos y tarda 3-8 ms.
- El catálogo también es MVCC: las demás sesiones ven el nombre viejo hasta el `COMMIT` y el nuevo después.
- Una consulta que ya estaba en marcha termina con las tablas viejas (las tiene por OID); por eso el `DROP` va aparte y puede esperar.
- `lock_timeout` de 30 s: si algo tuviera bloqueado el esquema, el cambio falla en vez de colgarse, y el horario viejo sigue.
- La consecuencia: **no puede haber claves foráneas hacia `horario`** desde otras tablas. Posiciones y retrasos guardarán `trip_id` como texto.

### La proyección sobre el recorrido

Para qué: el feed en directo pone los trenes en las estaciones (fase 0). Para dibujar el tren entre dos estaciones, la fase 3 necesita saber dónde cae cada parada sobre la línea dibujada (`shapes`), y así interpolar por el recorrido con las horas programadas más el retraso.

1. `shapes.txt` → un `LineString` por recorrido: `ST_MakeLine(punto ORDER BY secuencia)`.
2. Para cada pareja distinta (recorrido, parada) de los viajes (2.513, frente a 1,8 M horas de paso), `ST_LineLocatePoint(recorrido, parada)`: la fracción 0..1 del recorrido del punto más cercano a la estación. Y la distancia de la estación al recorrido en metros (`ST_Distance` sobre `geography`). Se guarda en `parada_forma`.
3. Para cada viaje, con una función de ventana (`lag() OVER (PARTITION BY trip_id ORDER BY stop_sequence)`), el avance en metros de cada parada a la siguiente:
   - si nunca retrocede más de 25 m, `forma_sentido = 1`; si nunca avanza más de 25 m, `-1` (el viaje va al revés del dibujo: los recorridos van por línea, no por sentido);
   - en un recorrido **cerrado** (circular, como la C4 de Sevilla), el avance se toma módulo 1: de 0,9 a 0,1 es avanzar 0,2;
   - `forma_encaja` = sentido conocido, al menos dos paradas, todas proyectadas y todas a 200 m o menos del recorrido.

**Resultado con el horario real del 20-11: encajan 106.111 de 113.488 viajes de tren (93,5 %).** Los que no encajan, y por qué (lo comprobé viaje a viaje):

- **Recorridos que no cubren toda la línea** (5.766 viajes con alguna parada a más de 200 m). Por ejemplo, la C6 de Valencia: el recorrido acaba en Castelló de la Plana y los trenes siguen hasta Vinaròs, a 69 km. O la R2 de Rodalies, cuyo recorrido no llega a varias estaciones del extremo.
- **Viajes que vuelven sobre sus pasos** (1.004). Por ejemplo, un R11 que llega a Cerbère y vuelve a Portbou.
- **Viajes de una sola parada** (607): no hay orden que comprobar.

La fase 3 usará la interpolación por recorrido solo si `forma_encaja`; si no, en línea recta entre estaciones.

## 4. Preguntas probables

- **«¿Qué pasa si se cae la aplicación en mitad de la importación?»** El horario vivo no se ha tocado. Al arrancar, la fila `EN_CURSO` se marca como `FALLIDA`, y la siguiente importación borra el `horario_carga` que quedó.
- **«¿Y si Renfe publica un fichero cortado?»** Si le falta un fichero obligatorio, una fila está mal formada o trae menos de la mitad de los viajes, la importación falla antes del cambio. Hay una prueba para cada caso.
- **«¿Qué significa idempotente aquí?»** Que importar el mismo fichero dos veces deja exactamente lo mismo: cada carga empieza en un esquema vacío y sustituye entero al anterior. No hay `INSERT` sobre datos existentes que pueda duplicar nada.
- **«¿Por qué no descomprimes el ZIP?»** No hace falta: `ZipFile.getInputStream(entrada)` descomprime al vuelo mientras se lee línea a línea. En memoria solo hay un lote (10.000 filas) cada vez. El ZIP comprimido (15,6 MB) sí se guarda en un temporal, para poder abrir sus ficheros uno detrás de otro, y se borra al acabar.
- **«¿Cómo funciona la descarga condicional?»** Mando `If-None-Match` con el ETag de la última importación correcta (y `If-Modified-Since`). Si no ha cambiado, el servidor contesta 304 sin cuerpo. Si el servidor ignorara la cabecera y devolviera 200 con el mismo ETag, también se trata como sin cambios. Un intento fallido no cuenta: el siguiente vuelve a descargar.
- **«¿Por qué a las 04:30 y a las 06:30?»** Renfe publica hacia las 04:09 hora de Madrid (Last-Modified 03:08:54 GMT del 20-11). La zona va explícita en el `@Scheduled` (`Europe/Madrid`): el servidor puede estar en UTC y los cambios de hora no la mueven. La segunda pasada es el reintento barato.
- **«¿Por qué `COPY` y no `saveAll` de un repositorio?»** Un `INSERT` por fila son 1,8 M idas y vueltas. `COPY` manda las filas en un flujo y PostgreSQL las escribe sin planificar una sentencia por fila.
- **«¿Qué pasa con las horas después de medianoche?»** El GTFS escribe `24:10:00` para un tren que llega pasada la medianoche de su día de servicio. Se guarda en segundos desde la medianoche del día de servicio (87.000), no como `TIME`, que no admite 24:10.
- **«¿Y los espacios de relleno?»** Todos los campos se recortan al leer (`FilaCsv.get`), y un campo solo con espacios es `NULL`. La prueba usa un GTFS de ejemplo con los mismos rellenos que el de Renfe.
- **«¿Y si hay dos instancias de la aplicación?»** Un *advisory lock* de PostgreSQL (`pg_try_advisory_lock`) en una conexión propia rodea la importación: la segunda instancia ve que está cogido y no importa. Dentro de la JVM, un `AtomicBoolean` que se toma antes de encolar, para que dos `POST` seguidos no encolen dos importaciones.
- **«¿Qué pasa si Renfe se queda colgado a mitad de la descarga?»** `HttpRequest.timeout` solo limita la espera de las cabeceras. Por eso la descarga va con `sendAsync(…).get(5 min)` y se cancela al agotarse. Lo encontró la revisión independiente y hay una prueba con un servidor que se queda parado.
- **«¿Los 25 m de tolerancia son metros de verdad?»** Aproximadamente: `ST_LineLocatePoint` sobre `geometry` en grados da la fracción en el plano de grados, y se multiplica por la longitud geodésica. En tramos este-oeste un grado de longitud mide unos 0,77 de uno de latitud. Para una tolerancia de unos pocos metros, sobre estaciones a kilómetros, da igual; si hiciera falta precisión, se proyectaría a UTM.
- **«¿Cómo pruebas la descarga sin depender de Renfe?»** Con un servidor HTTP del JDK (`com.sun.net.httpserver`) dentro de la prueba, que sirve el ZIP de ejemplo con su ETag y contesta 304. La prueba comprueba qué cabecera recibió.
