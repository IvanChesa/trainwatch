# Fase 2 · GTFS estático: informe

**Fecha:** 20-11-2026. **Rama:** `fase-2-gtfs-estatico`. **Resultado:** hecha. El horario real de Renfe (ZIP del 20-11, publicado a las 03:08:54 GMT) se importa en **15,2 s** con la base vacía y en **16,8 s** sustituyendo a uno ya cargado. Las filas coinciden **exactamente** con las de la fase 0. 29 pruebas en el backend: 28 pasan y 1 se omite (la manual con el ZIP real, que no corre en CI).

## 1. Especificación

**Entra:**

- **Esquema (Flyway V2)** en `horario`: `stops` (con `geometry(Point, 4326)` generado), `routes` (con `nucleo` y `es_autobus`), `trips`, `stop_times` (horas en segundos), `calendar`, `shapes` (un `LineString` por recorrido), `parada_forma` y `transfers`. Además, `public.importacion_horario` para el registro. La V2 borra `prueba_cimientos`.
- **Índices** para tres consultas:
  - próximas salidas de una estación: `(stop_id, salida_s) INCLUDE (trip_id)`;
  - paradas de un viaje en orden: la clave primaria `(trip_id, stop_sequence)`;
  - estaciones cercanas: GiST sobre `(ubicacion::geography)`.
- **Importación con Spring Batch:** descarga condicional (`If-None-Match` / `If-Modified-Since`), lectura del ZIP en streaming y `COPY` por lotes.
- **Cambio atómico** renombrando esquemas en una transacción, y validación previa.
- **Paradas proyectadas sobre el recorrido** y comprobación del orden por viaje, con su medida.
- **Programación:** cada noche a las 04:30 y a las 06:30 de Madrid, al arrancar si el horario está vacío, y a mano con `POST /api/horario/importaciones` protegido por token.
- **Registro y métricas:** `GET /api/horario/estado` y Micrometer.
- **Pruebas:** GTFS de ejemplo hecho a mano, integración con Testcontainers y la prueba manual con el ZIP real.

**No entra:**

- consultas de API sobre el horario (próximas salidas, ficha de estación…), que llegan en la fase 4;
- `agency.txt`: una fila, sin uso;
- reanudar una importación a medias: se empieza siempre de cero.

**Aceptación:**

1. `./mvnw verify` pasa con las pruebas de integración de importación completa, idempotencia, fallo a media importación sin tocar el horario anterior, y proyección.
2. Con el ZIP real, las filas por tabla coinciden con la fase 0.
3. La carga completa tarda **menos de 5 minutos**. El plan dejaba la «X» abierta: este es el valor que fijo, holgado para un servidor pequeño.

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| `V2__horario_gtfs_estatico.sql` | Esquema `horario` con 8 tablas y 13 índices o claves, y la tabla `importacion_horario`. Sin claves foráneas que entren o salgan de `horario` ([ADR 0006](../adr/0006-cambio-atomico-del-horario-por-esquemas.md)) |
| `ImportacionJobConfig` | El `Job` de Spring Batch: `descarga` → (si `SIN_CAMBIOS`, fin) → `preparar` → 7 pasos de carga → `indices` → `derivados` → `validar` → `cambio` → `limpieza` |
| `DescargaHorario` | `HttpClient` con petición condicional, tiempo máximo total de 5 min (`sendAsync().get`) y tamaño máximo de 200 MB. 304, o 200 con el mismo ETag (o con el mismo Last-Modified si no trae ETag), cuentan como sin cambios |
| `LectorCsvZip`, `FilaCsv`, `TablaGtfs` | Lectura en streaming desde `ZipFile`, columnas por nombre de cabecera, recorte de los rellenos, BOM y comillas. Conversión y validación por fila: si falta un dato obligatorio o hay una hora mal escrita, la importación falla |
| `EscritorCopy` | Un `COPY … FROM STDIN (FORMAT csv)` por lote de 10.000 filas, con la conexión de la transacción del lote |
| `EsquemaCarga` | Todo el SQL: clonar las tablas con `LIKE … INCLUDING ALL EXCLUDING INDEXES`, recrear claves e índices desde el catálogo, recorridos, proyección, validación y cambio |
| `ImportadorHorario` | Lanza el trabajo, lo registra y publica las métricas. Una importación a la vez: un `AtomicBoolean` en la JVM y un *advisory lock* de PostgreSQL entre instancias |
| `ProgramacionHorario` | El `@Scheduled` con `zone = "Europe/Madrid"` y el arranque: cierra los intentos interrumpidos, borra los ZIP temporales huérfanos e importa si el horario está vacío |
| `HorarioController` | `GET /api/horario/estado` y `POST /api/horario/importaciones` (token en `X-Trainwatch-Token`; sin token configurado, 404) |
| Pruebas | `HorarioIntegracionTests` (16), `DescargaYControladorTests` (4) e `ImportacionRealManualTests` (manual), con el GTFS de ejemplo de `src/test/resources/gtfs/ejemplo/` y `ServidorGtfs`, un servidor HTTP local que hace de Renfe |
| Documentación | ADR 0005-0007, README, esta guía y la [guía de defensa](../defensa/fase-2.md) |

**El GTFS de ejemplo** está hecho a mano, con los mismos rellenos de espacios que el de Renfe:

- 5 paradas y 3 líneas, una de ellas de autobús;
- 2 recorridos: uno recto y otro circular;
- 7 viajes, cada uno con un caso:
  - en el sentido del recorrido;
  - al revés;
  - en autobús;
  - desordenado;
  - con una parada a 40 km;
  - por el circular, pasando del final al principio;
  - de una sola parada;
- 18 horas de paso, una de ellas a las 24:10:00.

## 3. Cifras con el ZIP real (20-11-2026)

Salida completa en [`fase-2-importacion-real.txt`](fase-2-importacion-real.txt). Medido en el portátil de desarrollo (Windows 11, Docker Desktop, PostGIS en Testcontainers).

| Tabla | Filas (fase 2) | Fase 0 |
|---|---:|---:|
| `stops` | 1.167 | 1.167 |
| `routes` | 277 | 277 |
| `calendar` | 450 | 450 |
| `trips` | 130.297 | 130.297 |
| `stop_times` | 1.793.333 | 1.793.333 |
| `shapes` | 144 (de 123.734 puntos) | 144 |
| `transfers` | 19 | 19 |
| `parada_forma` | 2.513 | — |

**Integridad:** 0 horas de paso sin viaje y 0 sin parada. Hay 16.809 viajes de autobús y 113.488 de tren, y todos los de tren tienen un recorrido que existe en `shapes.txt`.

**Tiempos por paso** (segunda importación, con un horario ya cargado):

| Paso | Tiempo |
|---|---:|
| Descarga (servidor local) | 0,08 s |
| Carga de `stop_times` (`COPY`, 1,8 M filas) | 4,3 s |
| Carga del resto | 0,6 s |
| Índices y `ANALYZE` | 3,3 s |
| Derivados (recorridos, proyección y encaje) | 6,0 s |
| Validación | 0,9 s |
| **Cambio de esquema** | **0,004 s** |
| Borrado del horario anterior | 1,7 s |
| **Total** | **16,8 s** (15,2 s con la base vacía) |

La importación sin cambios (304) tarda 18 ms. El tiempo de la descarga desde Renfe no está medido (la prueba sirve el ZIP desde un servidor local); son 15,6 MB. El horario ocupa **313 MB** con índices; durante el cambio conviven dos.

**Consultas** (`EXPLAIN ANALYZE` sobre el horario real):

| Consulta | Plan | Tiempo |
|---|---|---:|
| Próximas salidas de Lleida Pirineus entre las 08:00 y las 10:00 (965 filas, los 30 días) | Bitmap Index Scan en `stop_times_salidas_idx` | 1,2 ms |
| Paradas de un viaje en orden | Index Scan en `stop_times_pkey` | 0,2 ms |
| Las 5 estaciones más cercanas a la plaza de Catalunya | Index Scan KNN en `stops_ubicacion_geog_idx` | 0,1 ms |

### Proyección sobre el recorrido

**Encajan 106.111 de 113.488 viajes de tren (93,5 %).** Un viaje encaja si sus paradas avanzan siempre en el mismo sentido sobre el recorrido, con 25 m de tolerancia, y ninguna está a más de 200 m.

| Núcleo | Viajes de tren | Encajan | % |
|---|---:|---:|---:|
| 10 Madrid | 35.903 | 35.301 | 98,3 |
| 20 Asturias | 12.327 | 10.557 | 85,6 |
| 30 Sevilla | 4.104 | 3.881 | 94,6 |
| 31 Cádiz | 3.573 | 3.573 | 100 |
| 32 Málaga | 3.882 | 3.762 | 96,9 |
| 40 Valencia | 8.500 | 5.776 | **68,0** |
| 41 Murcia/Alicante | 1.968 | 1.838 | 93,4 |
| 45 Cartagena · 46 Ferrol · 47 León | 1.132 · 835 · 787 | todos | 100 |
| **51 Rodalies** | **20.755** | **19.507** | **94,0** |
| 60 Bilbao | 9.780 | 9.520 | 97,3 |
| 61 San Sebastián | 2.231 | 2.231 | 100 |
| 62 Santander | 5.504 | 5.222 | 94,9 |
| 70 Zaragoza | 2.207 | 2.189 | 99,2 |

**Por qué no encajan los demás** (mirado viaje a viaje en la salida de la prueba):

- **5.766 tienen alguna parada a más de 200 m del recorrido.** Son recorridos que no cubren toda la línea:
  - la C6 de Valencia (`40_C6` y `40_C6_INV`, unos 2.570 viajes): el recorrido acaba en Castelló de la Plana y los trenes siguen hasta Vinaròs, a 69 km;
  - la R2 de Rodalies, con estaciones de los extremos a 2-37 km del recorrido;
  - la C2 y la C5 de Asturias, con una parada a 350-420 m.
- **1.004 no avanzan siempre en el mismo sentido.** Por ejemplo, un R11 que llega a la última estación y vuelve a la anterior.
- **607 tienen una sola parada.**

**El caso circular:** la C4 de Sevilla es una línea circular, con un recorrido que empieza y acaba en la misma estación. Sin tratarla aparte encajaba el 79,0 % de los viajes de Sevilla. Ahora `shapes.cerrado` marca los recorridos así y el avance se cuenta módulo 1: Sevilla sube al **94,6 %**.

## 4. Revisión independiente

Un subagente sin contexto de la conversación revisó el código contra la especificación y ejecutó `./mvnw verify`. Comprobó que el cambio atómico y el `COPY` van dentro de la transacción de su paso (el `PlatformTransactionManager` inyectado es el de JDBC) y que el clonado de índices funciona.

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (alta) | `HttpRequest.timeout` no limita la descarga del cuerpo. Lo reprodujo con un servidor que se queda parado: con un servidor de Renfe atascado, la importación quedaría ocupada para siempre, y con ella el hilo del programador | **Corregido.** Tiempo total con `sendAsync().get(5 min)` y cancelación. La nocturna solo encola, así que el hilo del programador queda libre. Hay una prueba con un servidor atascado (falla en ~1 s, no en 15) |
| 2 | Comprobar y encolar no era atómico: dos `POST` seguidos encolaban dos importaciones | **Corregido** con un `AtomicBoolean` que se toma antes de encolar. Hay una prueba |
| 3 | El cerrojo solo valía dentro de la JVM: dos instancias pisarían `horario_carga`, y una marcaría como interrumpida la importación de la otra | **Corregido** con un *advisory lock* de PostgreSQL alrededor de la importación y del cierre de interrumpidas. Hay una prueba. El caso de una migración que cambie `horario` durante una carga queda como regla de despliegue en el ADR 0006 |
| 4 | La tolerancia de 25 m se calcula con una fracción en el plano de grados por una longitud geodésica: es una aproximación | **Se deja y se documenta** (guía de defensa). Basta para una tolerancia de metros entre estaciones a kilómetros |
| 5 | El estado público enseñaba los mensajes de excepción completos (SQL, rutas) | **Corregido.** Solo se enseñan los errores del fichero de Renfe; el resto, «error interno». El detalle sigue en `importacion_horario`. Hay pruebas |
| 6 | Faltaban pruebas de «200 con el mismo ETag», del 404 sin token y de la concurrencia | **Corregido.** Falta una prueba de un fallo *dentro* del paso `cambio`: hoy lo garantiza la transacción del paso, y queda como deuda |
| 7 | Los ZIP temporales de una importación cortada en seco se quedaban en disco | **Corregido.** Se borran al arrancar. Si falla la escritura del registro después de un cambio correcto, la fila queda `EN_CURSO`: al arrancar pasa a `FALLIDA` y la noche siguiente se reimporta. Es un fallo seguro |
| 8 | Las cifras de calidad se publicaban en el *gauge* de filas por tabla | **Corregido.** Ahora hay un *gauge* aparte, `trainwatch.horario.calidad` |
| 9 | Un 200 sin ETag y con el mismo Last-Modified se reimportaba; no había tamaño máximo | **Corregido** las dos cosas |

## 5. Decisiones tomadas sin consultar (por indicación de la tarea)

- **«X min» de la aceptación = 5 min.** Se tarda 17 s.
- **Disparo manual por endpoint con token**, no por comando ([ADR 0007](../adr/0007-spring-batch-con-copy-y-repositorio-en-memoria.md)).
- **Dos pasadas nocturnas,** a las 04:30 y a las 06:30: la segunda es el reintento y solo cuesta un 304.
- **Sin `spring-boot-starter-batch-jdbc`:** el historial va en una tabla propia.
- **Nombres:** los campos del GTFS conservan su nombre en inglés (`stop_id`); los nuestros van en español (`nucleo`, `ubicacion`, `forma_encaja`).
- **Horas como segundos** (`llegada_s`, `salida_s`), porque el GTFS admite 24:10:00.
- **Rechazar un horario con menos del 50 % de los viajes del actual** (configurable).

## 6. Qué quedó fuera o pendiente

- **La CI sigue sin ejecutarse en GitHub** (no hay repo todavía). Las pruebas nuevas usan Testcontainers y un servidor HTTP en `127.0.0.1`: deberían ir igual en `ubuntu-latest`.
- **Falta probar un fallo dentro del paso `cambio`** (punto 6 de la revisión).
- **Las métricas no se exponen por HTTP:** el endpoint `metrics` de Actuator sigue cerrado. Lo público es `GET /api/horario/estado`.
- **Los viajes que no encajan** (6,5 %) necesitan otra forma de dibujarse en la fase 3: en línea recta entre estaciones, o con el tramo del recorrido que sí cubren.

## 7. Qué cambiaría del plan

- **§6, fase 2:** la aceptación «menos de X min» pasa a **«menos de 5 min; medido, 17 s»**.
- **§6, fase 3:**
  - interpolar sobre el recorrido solo los viajes con `forma_encaja`;
  - en los cerrados (`shapes.cerrado`), la posición da la vuelta de 1 a 0;
  - en los viajes con `forma_sentido = -1`, el avance es `1 - posicion`.
- **§6, fase 3:** el programador de Spring tiene un solo hilo por defecto. La ingesta cada 20 s y la importación no deben compartirlo; la importación ya va en su propio hilo.
- **§6, fase 7:**
  - nada de desplegar migraciones que toquen `horario` durante la ventana de importación (04:25-07:00);
  - definir `TRAINWATCH_ADMIN_TOKEN` en el servidor;
  - contar con unos 630 MB libres durante la importación.
- **§3, datos:** la Valencia de la C6 y la R2 de Rodalies tienen recorridos que no cubren toda la línea. Conviene enseñarlo en el estado por núcleo, como la cobertura del directo.
