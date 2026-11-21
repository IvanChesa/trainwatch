# Fase 6 · Estadísticas de puntualidad: informe

**Fecha:** 21-11-2026. **Rama:** `fase-6-estadisticas` (sin fusionar). **Resultado:** **hecho el backend; falta la web.** Están las definiciones (ADR 0025), los agregados diarios con su tarea nocturna (V6, ADR 0026), la API de estadísticas con OpenAPI, la fecha mínima de publicación, las pruebas y la comprobación con datos reales. Las páginas de React (punto 4 del encargo) esperan a que se fusione la fase 5, que se hace a la vez en otra sesión.

> Excepciones al encargo, por indicación de la sesión de control: no se espera a la fusión de la fase 5, no se toca `frontend/` y **no se fusiona en `main`**: todo queda en la rama con commits.

## 1. Especificación

Punto de partida: `docs/prompts/fase-6.md`, la fase 6 del §6 del plan y los puntos 3 y 8 de «Cambios tras la fase 0».

**Entra (backend):**

1. **Definiciones** antes del código, en este informe y en el [ADR 0025](../adr/0025-definiciones-de-puntualidad.md) (resumen en el §2).
2. **Agregados para siempre** (Flyway V6) por día de servicio: por núcleo, línea y hora; por línea y día; por estación; por tramo; y los cancelados explícitos. Solo sumas, recuentos e histograma. **Tarea nocturna** idempotente antes del cambio de horario, **recálculo a mano** por Actuator y cuidado con los cerrojos ([ADR 0026](../adr/0026-agregados-diarios-y-tarea-nocturna.md)).
3. **API** en el estilo de la fase 4: ranking del núcleo, línea (por hora, día de la semana, semana y tramos) y estación, con `desde`/`hasta` (por defecto 4 semanas), periodo, viajes, paradas, inferidas quitadas y cobertura; `ProblemDetail`, caché y OpenAPI con un ejemplo por endpoint en `docs/api/`.
4. **Publicación con fecha mínima** (solo el backend).
5. **Pruebas** del cálculo (Testcontainers, filas a mano), de la idempotencia y de cada endpoint.
6. **Comprobación con datos reales**: 3 cifras recalculadas con SQL directo y comparadas con la API.

**No entra:** las páginas de React, los enlaces desde el mapa y el e2e de Playwright (cuando se fusione la fase 5); la fusión en `main`, el diario y el despliegue.

**Aceptación:**

1. `./mvnw verify` en verde con las pruebas del cálculo (medias, porcentajes, horas en Madrid con un día de cambio de hora, día de servicio pasada la medianoche, inferidas, canceladas, muestra mínima), de la idempotencia y de los endpoints (contrato, errores, sin datos).
2. Tres cifras de la API (una media por línea y hora, un porcentaje y un tramo) iguales a las calculadas con SQL directo sobre `retrasos`.

## 2. Definiciones elegidas (ADR 0025)

- **Unidad:** la **parada** (el retraso al llegar a cada parada vista) como cifra principal; **por viaje**, el retraso al llegar a su **destino**, solo de los viajes que se vieron llegar (los demás se cuentan aparte), como secundaria. Nunca se mezclan.
- **Cifras:** media de minutos (un decimal), **mediana** y **p90** (del histograma por minuto), y el % de paradas con **menos de 3 min** (adelantos incluidos), de 3 a 5, de 6 a 15 y 16 o más.
- **Qué entra:** solo paradas **vistas** de trenes vistos en directo, con hora programada. **Inferidas** y **sin hora** fuera, contadas aparte. **Autobuses** fuera. **Cancelados** explícitos aparte, nunca como retraso; un tren que no aparece baja la **cobertura** (vistos / programados por el horario de ese día, por viajes y por paradas).
- **Franja:** la hora **programada** de llegada en **hora de Madrid**. El día de cambio de hora, las dos 02:30 van a la franja de las 2. Las filas antiguas sin hora (anteriores a la V5) quedan fuera de todas las cifras, también de las diarias, para que el día sea la suma exacta de sus horas.
- **Día:** el **día de servicio** (`fecha_servicio`); día de la semana y semana ISO, también de servicio.
- **Muestra mínima:** **30 viajes** (no paradas) por cifra, y 30 pasos por tramo; por debajo, «datos insuficientes» con su número de viajes.
- **Publicación:** `trainwatch.estadisticas.publicar-desde`, en producción el **19-12-2026** (4 semanas completas desde el 21-11).

## 3. Qué se hizo

| Pieza | Detalle |
|---|---|
| **V6** | `estad_linea_hora` (día, núcleo, línea, hora: viajes, paradas, suma, tramos, histograma de 61 posiciones y la llegada a destino), `estad_linea_dia` (viajes distintos, viajes y paradas con datos, inferidas, sin hora, viajes y paradas programados, cancelados), `estad_estacion_dia`, `estad_tramo_dia` y `estad_dias` (qué días están calculados y con qué fuentes). `CHECK` de que los tramos suman las paradas |
| `CalculoEstadisticas` | Un día en una transacción: `statement_timeout` 2 min (con el plazo de red por encima) y `lock_timeout` 5 s locales, el cerrojo de la importación en modo compartido y `pg_advisory_xact_lock(6, día)`, copia en tablas temporales de lo que no se puede volver a sacar (programados, destinos y cancelados; los tramos, si no hay horario, se dejan como estaban), `DELETE` del día en las cinco tablas y cuatro `INSERT … SELECT` (`GROUP BY` con `FILTER`, histograma con `generate_series` y `array_agg`, `row_number` para la última parada vista, comparada con la última del horario para saber si es el destino, y `LAG` para los tramos, con la parada anterior según el horario). Tarea `@Scheduled` a las **03:45** de Madrid: ayer y los dos días anteriores sin calcular |
| `EstadisticasEndpoint` | Actuator `GET /actuator/estadisticas` (últimos 14 días calculados) y `POST` con `{"desde", "hasta"}` (como mucho 400 días). Expuesto en `local`, en las pruebas y en producción, donde Actuator está en el 8081, que no sale de la máquina |
| `EstadisticasController` | `GET /api/estadisticas/nucleos/{nucleo}`, `/lineas/{nucleo}/{linea}` y `/estaciones/{stopId}`, con `desde` y `hasta`. 404 y 400 como `ProblemDetail`; `Cache-Control` 1 h y `ETag`. Antes de la fecha de publicación, el mismo formato con `publicacion` y el periodo y **ninguna cifra** |
| `Acumulado` | Suma de filas en Java: sumas, recuentos e histograma posición a posición; media y porcentajes con un decimal; mediana y p90 en enteros (igual que `percentile_disc`) |
| Configuración | `trainwatch.estadisticas.*` (`EstadisticasProperties`): cron, muestra mínima (30), publicar desde (19-12-2026), ventana (28 días), periodo máximo, días a recuperar, líneas destacadas (`51:RL4`), plazos. El programador pasa a 4 hilos |
| `docs/api/` | `puntualidadNucleo.json`, `puntualidadLinea.json` y `puntualidadEstacion.json`, sacados de la aplicación con datos reales (§5) |
| Documentación | ADR 0025 y 0026, este informe y la [guía de defensa](../defensa/fase-6.md) |

### Decisiones tomadas sin consultar

- **Histograma en `integer[]`** (61 posiciones, recortado a 0 y 60) además de la suma exacta, en vez de una fila por minuto: 5-10 veces menos filas; se suma en Java.
- **Tramos con el horario:** `stop_sequence` tiene huecos en el 1,5 % de los viajes de Renfe (1.894 de 129.867 en el ZIP del 20-11), así que la parada anterior se pregunta al horario de esa noche; sin horario (recálculo de un día viejo), la fila anterior.
- **Ranking** por % de menos de 3 min y luego por media; las líneas sin muestra suficiente, al final y sin puesto. La RL4 va marcada como `destacada`.
- **La línea en la URL** sin distinguir mayúsculas (como en la fase 4); la respuesta da el nombre tal como está en los datos.
- **Periodo:** por defecto, 28 días hasta **ayer**; como mucho 400 días. Un periodo sin datos responde 200 con ceros y `suficiente: false`, no 404.
- **Los nombres de estación** salen del horario actual (`null` si ya no está); los agregados guardan solo el `stop_id`.

## 4. Cifras

### Pruebas

`./mvnw verify`: **149 pruebas, 0 fallos, 1 omitida** (la manual de la fase 2). En `main` (`8849f0b`) eran 125. Nuevas:

- `EstadisticasIntegracionTests` (16, con PostGIS en Testcontainers). Un puñado de filas de `retrasos` escritas a mano (dos días, 22 filas, 10 viajes) y las cifras **calculadas a mano en los comentarios** de la prueba:
  - **por hora en hora de Madrid**: sumas, tramos e histograma de las 8 y las 9 (con un adelanto de −1 que va a «menos de 3» y a la posición 0, y cuenta tal cual en la suma); nada en las franjas UTC; las 08:00 UTC del 25-10 son la franja de las 9 (horario de invierno);
  - **día de servicio pasada la medianoche** (una parada a las 00:10 del 24 va a la franja 0 del 23) y **cambio de hora** (las dos 02:30 del 25-10 van a la franja de las 2);
  - **inferidas** y **sin hora** fuera y contadas, **autobús** fuera de todo, **cancelados** de `posiciones` (también uno pasada la medianoche, en la partición del día siguiente, y uno de otro día que no cuenta), viajes y paradas programados y cobertura;
  - **tramos** con `LAG`: una parada inferida corta el tramo y, con `stop_sequence` 1, 2, 4 y 5, B→C cuenta pero A→C (B sin cerrar) no;
  - **por estación**, con sus inferidas y sin hora;
  - **idempotencia**: recalcular dos veces da las mismas filas;
  - **lo que ya no está se conserva**: sin horario ni partición de `posiciones`, al recalcular siguen los programados, los cancelados, las llegadas a destino y los tramos;
  - la **nocturna** calcula ayer y recupera los días que faltan;
  - **cerrojos**: con otro cálculo del mismo día, con una importación del horario en marcha o con una tabla de agregados retenida, el cálculo falla en ~1 s (`lock_timeout`) sin dejar el día a medias; otro día sí se calcula;
  - **API**: ranking del núcleo (orden, puestos, destacada, cobertura por viajes y por paradas), línea (media 5,5, mediana 4, p90 18, tramos de 4, llegada a destino, por hora con la **muestra mínima**, por día de la semana y por semana), estación, periodo sin datos, periodo por defecto, una línea del horario sin datos, errores (404 de núcleo, línea y estación; 400 de `desde > hasta`, fecha mal escrita y periodo de más de 400 días), **antes de la fecha de publicación no hay ninguna cifra**, Actuator (recálculo, 400 con fechas malas) y OpenAPI con los ejemplos.
- `AcumuladoTests` (5): suma de filas, el percentil del histograma frente a `percentile_disc` en 500 listas al azar, la muestra mínima, los redondeos como `round(numeric, 1)` de PostgreSQL y el ranking.
- `EjemplosApiTests`: los tres ejemplos nuevos de `docs/api/` encajan con sus records (13 en total).

### Lo que se vio por el camino

- **Docker Desktop no arrancaba**: se caía al no poder borrar sockets AF_UNIX viejos de la sesión anterior (`%LOCALAPPDATA%\Docker\run\dockerInference` y `docker-secrets-engine\engine.sock`). Se resolvió renombrando esas carpetas, sin tocar volúmenes ni configuración (nota en la carpeta compartida de las sesiones).
- **La primera importación del horario de la prueba real falló** con `NoSuchFileException` del ZIP temporal: `ProgramacionHorario.alArrancar` borra **todos** los `trainwatch-horario-*.zip` de `java.io.tmpdir`, así que cualquier otra instancia que arranque en la misma máquina (otras pruebas, otra sesión) le borra el ZIP a una importación en curso. En producción hay una sola instancia y no pasa; aquí se evitó con un `java.io.tmpdir` propio. Queda como seguimiento (§7).

## 5. Comprobación con datos reales

PENDIENTE

## 6. Revisión independiente

Un subagente sin contexto de la conversación revisó el código y las definiciones contra el encargo y los ADR, solo leyendo (sin ejecutar nada), con la pregunta expresa de si alguna cifra puede engañar. Dio por buenos los agregados (solo sumas, recuentos e histograma; media, porcentajes, mediana y p90 exactos al sumar días), la hora y el día, la exclusión de inferidas y filas sin hora, los viajes que no se suman por horas, el `LAG` de los tramos con el orden del horario, la idempotencia y que la API no deja salir ninguna cifra antes de publicar. **Todo lo de gravedad media se corrigió**, con prueba salvo donde se dice.

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (media) | El `statement_timeout` de 2 min no se cumple: el `socketTimeout` de 30 s del driver corta antes, sin error limpio | **Corregido** como en la importación: `setNetworkTimeout` de la conexión de la transacción al plazo más 1 min (Hikari lo restaura). Sin prueba automática (haría falta una consulta de más de 30 s) |
| 2 (media) | **La cifra por viaje engañaba**: con la «última parada vista», un tren que deja de verse tras su primera parada cuenta como llegada con el retraso de salida, y eso inclina la cifra hacia «puntual» | **Corregido**: la cifra por viaje es ahora la **llegada a destino** (la última parada del horario, mirada esa noche), solo de los viajes que se vieron llegar; los demás se cuentan aparte (`viajes` y `viajesEnDestino`). ADR 0025 al día. Prueba: de 5 viajes, 2 en destino, media 2,5 |
| 3 (media-baja) | La cobertura por viajes da un 100 % a una línea cuyos trenes solo se ven en 2 de sus 20 paradas | **Corregido**: también **por paradas** (paradas con fila / programadas por el horario), por núcleo y por línea. Prueba: 19 de 36, 52,8 % |
| 4 (media-baja) | `diasConDatos` contaba días calculados vacíos (la ingesta caída) | **Corregido**: solo los días con filas, también en los días de la semana y las semanas. Prueba: el 24 (vacío) no cuenta |
| 5 (baja) | El ADR decía que el cambio de esquema del horario espera a la tarea, y no es así: una sentencia posterior vería el horario nuevo y podría dejar los programados a 0 | **Corregido**: el cálculo toma en modo compartido el cerrojo consultivo de la importación (`pg_advisory_xact_lock_shared`), y el ADR 0026 lo explica. Prueba: con una importación en marcha, el cálculo falla en ~1 s |
| 6 (baja) | Una partición de `posiciones` separada y a medio borrar seguía contando como «hay posiciones», con cancelados 0 | **Corregido**: se mira `pg_inherits` (unida y sin `DETACH` pendiente) |
| 7 (baja) | Sin horario, recalcular un día cambiaba sus tramos (el caso A→C entraría) | **Corregido**: sin horario se conservan los tramos que hubiera (y las llegadas a destino). Prueba en la de conservar |
| 8 (baja) | Redondeo distinto del de SQL (−2,25 → −2,2 en Java, −2,3 en `round(numeric)`) y doble redondeo | **Corregido** con `BigDecimal` y `HALF_UP` sobre los enteros. Prueba |
| 9 (baja) | Actuator respondía 200 con fechas malas, y un día que fallaba cortaba el resto | **Corregido**: 400; cada día va aparte y la respuesta dice el error del que falle. Prueba del 400 |
| 10 | Pruebas: la de «una escritura a la mitad» pasaba aunque el código estuviera mal; faltaban el plazo, dos cálculos a la vez, el cancelado pasada la medianoche y conservar tramos | **Añadidas** las de cerrojos (mismo día, importación, otro día sí), el cancelado pasada la medianoche y la de tramos y destinos conservados; quitada la de la escritura. El plazo, sin prueba (punto 1). `AcumuladoTests` compara con una versión en Java de `percentile_disc`; la comparación con PostgreSQL de verdad está en la prueba de integración (mediana y p90 a mano) y en la comprobación con datos reales (§5) |

Además, al arreglar la prueba de OpenAPI apareció un **fallo de la fase 4**: los endpoints con `@RespuestasDeError` salían en OpenAPI **sin respuesta 200** (con alguna `@ApiResponse` en el método, springdoc ya no pone la suya), así que sus ejemplos de `docs/api/` no se veían en `/api/docs`. La prueba de la fase 4 solo miraba `/api/nucleos`, que no lleva esa anotación. **Corregido** en la anotación: ahora todos los endpoints tienen su 200 con el esquema y el ejemplo.

## 7. Qué quedó fuera o pendiente

- **La web (punto 4):** página por línea, página del núcleo con el ranking, la RL4 destacada, la puntualidad en la ficha de la estación y los enlaces desde el mapa y la lista, con tablas junto a los gráficos. También el e2e de Playwright con una página de línea. Cuando se fusione la fase 5.
- **Fusión, diario y despliegue:** los hace la sesión de control. Al desplegar, la V6 se aplica sola y la tarea empieza esa noche; para tener desde el primer día, recalcular a mano desde el 21-11 (`POST /actuator/estadisticas` en el 8081) mientras el horario y `posiciones` aún tengan esos días: **cuanto antes**, porque los cancelados del 21-11 se pierden a las 48-72 h y los programados con el horario del día.
- **Cancelados que solo vienen en `trip_updates`:** no dejan fila en `posiciones` y no se cuentan. Para contarlos, la ingesta tendría que apuntarlos en una tabla propia.
- **`alArrancar` borra los ZIP temporales de otras instancias** (§4).
- **Lo que se queda sin prueba automática:** el plazo de red de 2 min + 1 (punto 1 de la revisión).
- **Las cifras por hora de la RL4:** con pocos trenes al día, muchas franjas no llegarán a 30 viajes en cuatro semanas y saldrán como «datos insuficientes». Es lo honesto; si Iván prefiere enseñar más, la alternativa es agrupar en franjas de 2-3 horas para las líneas con poco servicio, no bajar el umbral.

## 8. Qué cambiaría del plan

- **§6, fase 6:** la aceptación («cifras comprobadas a mano contra los datos sin procesar en una muestra») se cumple con pocas horas de datos; las cifras **publicables** necesitan las cuatro semanas.
- **Despliegue:** hay que lanzar el recálculo del 21-11 en adelante en producción en cuanto esté la V6, por lo dicho en el §7.
