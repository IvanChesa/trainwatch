# ADR 0007 · Spring Batch con `COPY` por lotes, repositorio de trabajos en memoria y disparo manual con token

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 2

## Contexto

La importación del horario es un proceso por pasos (descargar, preparar, cargar siete ficheros, indexar, calcular, validar, cambiar) que tiene que:

- cargar 1,8 M filas de `stop_times` deprisa;
- parar en seco si algo falla, sin tocar el horario vivo ([ADR 0006](0006-cambio-atomico-del-horario-por-esquemas.md));
- dejar constancia de cada intento (cuándo, cuánto tardó, filas, resultado);
- poder lanzarse de noche, al arrancar y a mano.

El plan (§4) fija Spring Batch para esto.

## Decisión

1. **Un `Job` de Spring Batch** (`ImportacionJobConfig`) con un `Step` por fase:
   - pasos *tasklet* para lo que es una operación (descarga, preparar, índices, derivados, validar, cambio, limpieza);
   - pasos por lotes (*chunk-oriented*, el nuevo `ChunkOrientedStep` de Batch 6) para cada fichero: `LectorCsvZip` (*reader*, streaming desde el ZIP), la conversión y validación de cada fila (*processor*) y `EscritorCopy` (*writer*).
   - Un flujo condicional: si la descarga sale `SIN_CAMBIOS` (304 o el mismo ETag), el trabajo acaba ahí.
2. **`EscritorCopy` hace un `COPY … FROM STDIN (FORMAT csv)` por lote** de 10.000 filas, con la API `CopyManager` del driver de PostgreSQL y la conexión de la transacción del lote. `stop_times` (1,8 M filas) se carga en unos **4 s**. Por eso el driver pasa de `runtime` a `compile` en el `pom.xml`.
3. **Repositorio de trabajos en memoria** (el que trae `spring-boot-starter-batch` en Spring Boot 4), no `spring-boot-starter-batch-jdbc`. El historial que importa está en una tabla propia, `importacion_horario`, con lo que pide la aplicación (ETag, Last-Modified, vigencia, filas en JSON, error).
4. **Disparo manual: `POST /api/horario/importaciones`** con un token compartido en la cabecera `X-Trainwatch-Token` (`TRAINWATCH_ADMIN_TOKEN`), comparado en tiempo constante (`MessageDigest.isEqual`). Sin token configurado, el endpoint devuelve 404. Lanza la importación en segundo plano (202) y el resultado se ve en `GET /api/horario/estado`. `?forzar=true` ignora el ETag.
5. **Programación:** `@Scheduled(cron = "0 30 4,6 * * *", zone = "Europe/Madrid")`. Renfe publica hacia las 04:09 de Madrid; la segunda pasada es el reintento y, si no hay nada nuevo, solo cuesta un 304. Al arrancar (`ApplicationReadyEvent`), si el horario está vacío, se importa en segundo plano. Un `ReentrantLock` impide dos importaciones a la vez.

## Alternativas

1. **Un método `@Scheduled` con JDBC a pelo**, sin Spring Batch. Menos piezas, pero se pierde la estructura por pasos, el *chunking* con transacción por lote, los *listeners* y el estado de cada paso (filas leídas y escritas, duración) que Spring Batch registra solo. Y es lo que pide el plan.
2. **`JdbcBatchItemWriter`** (INSERT por lotes con `reWriteBatchedInserts`). Es lo estándar de Spring Batch y no fila a fila, pero `COPY` es varias veces más rápido para cargas masivas y no necesita configurar el driver.
3. **`FlatFileItemReader`** de Spring Batch. Sirve, pero hay que leer la cabecera para nombrar las columnas, recortar y abrir la entrada del ZIP a mano igualmente. `LectorCsvZip` son 130 líneas que hacen solo eso.
4. **`spring-boot-starter-batch-jdbc`** (las tablas `BATCH_*`). Permite reiniciar un trabajo fallido desde el paso que falló, pero aquí no tiene sentido: la importación empieza siempre desde cero en un esquema limpio, y 15 s no justifican reanudar. Seis tablas más que no aportan.
5. **Spring Security para el disparo manual.** Es lo correcto cuando haya más endpoints que escriban o usuarios. Para un único endpoint de administración, un token compartido y comparado en tiempo constante basta. Se revisa en la fase 7, al desplegar.
6. **Un comando** (`java -jar … --importar`) en vez de un endpoint. Con la aplicación ya en marcha en el servidor, arrancaría otra JVM contra la misma base de datos, y el cerrojo en memoria no la vería.

## Consecuencias

- Si la aplicación se para en mitad de una importación, la fila de `importacion_horario` queda `EN_CURSO`; al arrancar se marca como `FALLIDA` y el esquema de carga se borra en la siguiente.
- El cerrojo es de la JVM: con dos instancias de la aplicación, las dos importarían. Hoy hay una sola (fase 7); si llegan más, toca un *advisory lock* de PostgreSQL.
- Métricas: `trainwatch.horario.importacion` (timer por resultado) y `trainwatch.horario.filas` (gauge por tabla) en Micrometer. El endpoint `metrics` de Actuator no está expuesto por HTTP: lo público es `GET /api/horario/estado`.
