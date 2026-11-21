# Fase 6 · Estadísticas de puntualidad: informe

**Fecha:** 21-11-2026. **Rama:** `fase-6-estadisticas` (sin fusionar). **Resultado:** **hecho el backend; falta la web.** Están las definiciones (ADR 0022), los agregados diarios con su tarea nocturna (V6, ADR 0023), la API de estadísticas con OpenAPI, la fecha mínima de publicación, las pruebas y la comprobación con datos reales. Las páginas de React (punto 4 del encargo) esperan a que se fusione la fase 5, que se hace a la vez en otra sesión.

> Excepciones al encargo, por indicación de la sesión de control: no se espera a la fusión de la fase 5, no se toca `frontend/` y **no se fusiona en `main`**: todo queda en la rama con commits.

## 1. Especificación

Punto de partida: `docs/prompts/fase-6.md`, la fase 6 del §6 del plan y los puntos 3 y 8 de «Cambios tras la fase 0».

**Entra (backend):**

1. **Definiciones** antes del código, en este informe y en el [ADR 0022](../adr/0022-definiciones-de-puntualidad.md) (resumen en el §2).
2. **Agregados para siempre** (Flyway V6) por día de servicio: por núcleo, línea y hora; por línea y día; por estación; por tramo; y los cancelados explícitos. Solo sumas, recuentos e histograma. **Tarea nocturna** idempotente antes del cambio de horario, **recálculo a mano** por Actuator y cuidado con los cerrojos ([ADR 0023](../adr/0023-agregados-diarios-y-tarea-nocturna.md)).
3. **API** en el estilo de la fase 4: ranking del núcleo, línea (por hora, día de la semana, semana y tramos) y estación, con `desde`/`hasta` (por defecto 4 semanas), periodo, viajes, paradas, inferidas quitadas y cobertura; `ProblemDetail`, caché y OpenAPI con un ejemplo por endpoint en `docs/api/`.
4. **Publicación con fecha mínima** (solo el backend).
5. **Pruebas** del cálculo (Testcontainers, filas a mano), de la idempotencia y de cada endpoint.
6. **Comprobación con datos reales**: 3 cifras recalculadas con SQL directo y comparadas con la API.

**No entra:** las páginas de React, los enlaces desde el mapa y el e2e de Playwright (cuando se fusione la fase 5); la fusión en `main`, el diario y el despliegue.

**Aceptación:**

1. `./mvnw verify` en verde con las pruebas del cálculo (medias, porcentajes, horas en Madrid con un día de cambio de hora, día de servicio pasada la medianoche, inferidas, canceladas, muestra mínima), de la idempotencia y de los endpoints (contrato, errores, sin datos).
2. Tres cifras de la API (una media por línea y hora, un porcentaje y un tramo) iguales a las calculadas con SQL directo sobre `retrasos`.

## 2. Definiciones elegidas (ADR 0022)

- **Unidad:** la **parada** (el retraso al llegar a cada parada vista) como cifra principal; **por viaje**, el retraso en su **última parada vista**, como secundaria. Nunca se mezclan.
- **Cifras:** media de minutos (un decimal), **mediana** y **p90** (del histograma por minuto), y el % de paradas con **menos de 3 min** (adelantos incluidos), de 3 a 5, de 6 a 15 y 16 o más.
- **Qué entra:** solo paradas **vistas** de trenes vistos en directo, con hora programada. **Inferidas** y **sin hora** fuera, contadas aparte. **Autobuses** fuera. **Cancelados** explícitos aparte, nunca como retraso; un tren que no aparece baja la **cobertura** (vistos / programados por el horario de ese día).
- **Franja:** la hora **programada** de llegada en **hora de Madrid**. El día de cambio de hora, las dos 02:30 van a la franja de las 2. Las filas antiguas sin hora (anteriores a la V5) quedan fuera de todas las cifras, también de las diarias, para que el día sea la suma exacta de sus horas.
- **Día:** el **día de servicio** (`fecha_servicio`); día de la semana y semana ISO, también de servicio.
- **Muestra mínima:** **30 viajes** (no paradas) por cifra, y 30 pasos por tramo; por debajo, «datos insuficientes» con su número de viajes.
- **Publicación:** `trainwatch.estadisticas.publicar-desde`, en producción el **19-12-2026** (4 semanas completas desde el 21-11).

## 3. Qué se hizo

| Pieza | Detalle |
|---|---|
| **V6** | `estad_linea_hora` (día, núcleo, línea, hora: viajes, paradas, suma, tramos, histograma de 61 posiciones y la llegada a la última parada vista), `estad_linea_dia` (viajes distintos, viajes con datos, inferidas, sin hora, programados, cancelados), `estad_estacion_dia`, `estad_tramo_dia` y `estad_dias` (qué días están calculados y con qué fuentes). `CHECK` de que los tramos suman las paradas |
| `CalculoEstadisticas` | Un día en una transacción: `statement_timeout` 2 min y `lock_timeout` 5 s locales, `pg_advisory_xact_lock(6, día)`, copia en una tabla temporal de lo que no se puede volver a sacar (programados y cancelados), `DELETE` del día en las cinco tablas y cuatro `INSERT … SELECT` (`GROUP BY` con `FILTER`, histograma con `generate_series` y `array_agg`, `row_number` para la última parada vista y `LAG` para los tramos, con la parada anterior según el horario). Tarea `@Scheduled` a las **03:45** de Madrid: ayer y los dos días anteriores sin calcular |
| `EstadisticasEndpoint` | Actuator `GET /actuator/estadisticas` (últimos 14 días calculados) y `POST` con `{"desde", "hasta"}` (como mucho 400 días). Expuesto en `local`, en las pruebas y en producción, donde Actuator está en el 8081, que no sale de la máquina |
| `EstadisticasController` | `GET /api/estadisticas/nucleos/{nucleo}`, `/lineas/{nucleo}/{linea}` y `/estaciones/{stopId}`, con `desde` y `hasta`. 404 y 400 como `ProblemDetail`; `Cache-Control` 1 h y `ETag`. Antes de la fecha de publicación, el mismo formato con `publicacion` y el periodo y **ninguna cifra** |
| `Acumulado` | Suma de filas en Java: sumas, recuentos e histograma posición a posición; media y porcentajes con un decimal; mediana y p90 en enteros (igual que `percentile_disc`) |
| Configuración | `trainwatch.estadisticas.*` (`EstadisticasProperties`): cron, muestra mínima (30), publicar desde (19-12-2026), ventana (28 días), periodo máximo, días a recuperar, líneas destacadas (`51:RL4`), plazos. El programador pasa a 4 hilos |
| `docs/api/` | `puntualidadNucleo.json`, `puntualidadLinea.json` y `puntualidadEstacion.json`, sacados de la aplicación con datos reales (§5) |
| Documentación | ADR 0022 y 0023, este informe y la [guía de defensa](../defensa/fase-6.md) |

### Decisiones tomadas sin consultar

- **Histograma en `integer[]`** (61 posiciones, recortado a 0 y 60) además de la suma exacta, en vez de una fila por minuto: 5-10 veces menos filas; se suma en Java.
- **Tramos con el horario:** `stop_sequence` tiene huecos en el 1,5 % de los viajes de Renfe (1.894 de 129.867 en el ZIP del 20-11), así que la parada anterior se pregunta al horario de esa noche; sin horario (recálculo de un día viejo), la fila anterior.
- **Ranking** por % de menos de 3 min y luego por media; las líneas sin muestra suficiente, al final y sin puesto. La RL4 va marcada como `destacada`.
- **La línea en la URL** sin distinguir mayúsculas (como en la fase 4); la respuesta da el nombre tal como está en los datos.
- **Periodo:** por defecto, 28 días hasta **ayer**; como mucho 400 días. Un periodo sin datos responde 200 con ceros y `suficiente: false`, no 404.
- **Los nombres de estación** salen del horario actual (`null` si ya no está); los agregados guardan solo el `stop_id`.

## 4. Cifras

### Pruebas

PENDIENTE

### Lo que se vio por el camino

- **Docker Desktop no arrancaba**: se caía al no poder borrar sockets AF_UNIX viejos de la sesión anterior (`%LOCALAPPDATA%\Docker\run\dockerInference` y `docker-secrets-engine\engine.sock`). Se resolvió renombrando esas carpetas, sin tocar volúmenes ni configuración (nota en la carpeta compartida de las sesiones).
- **La primera importación del horario de la prueba real falló** con `NoSuchFileException` del ZIP temporal: `ProgramacionHorario.alArrancar` borra **todos** los `trainwatch-horario-*.zip` de `java.io.tmpdir`, así que cualquier otra instancia que arranque en la misma máquina (otras pruebas, otra sesión) le borra el ZIP a una importación en curso. En producción hay una sola instancia y no pasa; aquí se evitó con un `java.io.tmpdir` propio. Queda como seguimiento (§7).

## 5. Comprobación con datos reales

PENDIENTE

## 6. Revisión independiente

PENDIENTE

## 7. Qué quedó fuera o pendiente

- **La web (punto 4):** página por línea, página del núcleo con el ranking, la RL4 destacada, la puntualidad en la ficha de la estación y los enlaces desde el mapa y la lista, con tablas junto a los gráficos. También el e2e de Playwright con una página de línea. Cuando se fusione la fase 5.
- **Fusión, diario y despliegue:** los hace la sesión de control. Al desplegar, la V6 se aplica sola y la tarea empieza esa noche; para tener desde el primer día, recalcular a mano desde el 21-11 (`POST /actuator/estadisticas` en el 8081) mientras el horario y `posiciones` aún tengan esos días: **cuanto antes**, porque los cancelados del 21-11 se pierden a las 48-72 h y los programados con el horario del día.
- **Cancelados que solo vienen en `trip_updates`:** no dejan fila en `posiciones` y no se cuentan. Para contarlos, la ingesta tendría que apuntarlos en una tabla propia.
- **`alArrancar` borra los ZIP temporales de otras instancias** (§4).

## 8. Qué cambiaría del plan

- **§6, fase 6:** la aceptación («cifras comprobadas a mano contra los datos sin procesar en una muestra») se cumple con pocas horas de datos; las cifras **publicables** necesitan las cuatro semanas.
- **Despliegue:** hay que lanzar el recálculo del 21-11 en adelante en producción en cuanto esté la V6, por lo dicho en el §7.
