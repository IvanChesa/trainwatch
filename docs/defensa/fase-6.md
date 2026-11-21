# Fase 6 · Estadísticas de puntualidad: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista: **qué significa cada cifra, de dónde sale, por qué se precalcula y qué sesgos tiene**. Es la parte que más luce en LinkedIn y la que más preguntas va a traer («¿cómo sabes que la R2 Sud llega con 4 min?»). Este código no lo has escrito tú: repásalo con esta guía delante y abre cada fichero que se cita (en `backend/src/main/java/com/ivanchesa/trainwatch/estadisticas/` y `V6__estadisticas.sql`).

> Estado: está hecho el **backend** (definiciones, agregados, tarea nocturna, API y pruebas). Las páginas de React llegan cuando se fusione la fase 5.

## 1. La foto en 30 segundos

> «La ingesta guarda el retraso final de cada tren en cada parada: unas 50.000 filas al día, en minutos enteros. Cada noche, a las 03:45, una tarea resume el día de ayer en unas 2.000 filas: por línea y hora, por estación, por tramo entre dos paradas y por línea y día. Solo guardo sumas, recuentos y un histograma por minuto, nunca medias, así que sumar cuatro semanas da exactamente lo mismo que calcular sobre las 1,4 millones de filas originales. La API suma esas filas y responde en milisegundos. Cada cifra va con su periodo, cuántos viajes y paradas la forman, lo que he quitado (las paradas que no se vieron y se rellenaron) y la cobertura: qué parte de los trenes programados vi en directo. Por debajo de 30 viajes no enseño la cifra.»

## 2. Las definiciones (ADR 0025)

Si te preguntan «¿qué es puntual?», contesta con esto:

| Pregunta | Respuesta |
|---|---|
| **¿Unidad?** | La **parada**: el retraso con que el tren llega a cada parada vista. Es lo que vive el viajero en su andén. Además, **por viaje**: el retraso al llegar a su **destino**, solo de los viajes que se vieron llegar (los demás se cuentan, no entran) |
| **¿Puntual?** | **Menos de 3 min**, incluidos los adelantos. Tramos: < 3, 3-5, 6-15, ≥ 16 (los colores del mapa) |
| **¿Media, mediana, p90?** | Las tres. La media sola engaña con unos pocos trenes muy tarde; la mediana dice lo típico y el p90 lo malo |
| **¿Qué hora?** | La **programada** de llegada, en hora de Madrid. No la hora en que se cerró el retraso: un tren 20 min tarde caería en otra franja |
| **¿Qué día?** | El **día de servicio**: el tren de las 23:50 que llega a las 00:30 cuenta en el día en que salió |
| **¿Qué entra?** | Paradas **vistas** de trenes en directo, con hora programada. Fuera: paradas **inferidas** (se cuentan aparte), filas sin hora (antes de la V5), **autobuses**. Los **cancelados** explícitos se cuentan aparte, nunca como retraso; un tren que no aparece no es un cancelado: baja la cobertura |
| **¿Muestra mínima?** | **30 viajes** (no paradas: las paradas de un mismo tren no son independientes). Con σ ≈ 4 min, el error típico de la media con 30 viajes es ~0,7 min, por debajo de la resolución de los datos (1 min) |
| **¿Cuándo se publica?** | Desde el **19-12-2026** (`trainwatch.estadisticas.publicar-desde`): cuatro semanas completas de datos. Antes, la API dice «recogiendo datos desde el …, cifras a partir del …» y no da ninguna cifra |

## 3. El SQL de los agregados

Todo está en `CalculoEstadisticas` como `INSERT … SELECT`. Lo que hay que saber contar:

### La base: qué filas entran

```sql
SELECT r.*, (NOT r.inferido AND r.llegada_programada IS NOT NULL) AS valida,
       extract(hour FROM r.llegada_programada AT TIME ZONE 'Europe/Madrid')::smallint AS hora
FROM retrasos r
WHERE r.fecha_servicio = :f AND r.nucleo IS NOT NULL AND r.linea IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM horario.routes b WHERE b.route_id = r.route_id AND b.es_autobus)
```

- `fecha_servicio = :f` usa la clave primaria y lee **una sola partición** mensual (*partition pruning*, ADR 0009).
- `AT TIME ZONE 'Europe/Madrid'` sobre un `timestamptz` da la hora local: el día de octubre, las dos 02:30 caen en la franja de las 2. Está probado.

### Por línea y hora: `GROUP BY` con `FILTER`

```sql
SELECT nucleo, linea, hora, count(DISTINCT trip_id) AS viajes, count(*) AS paradas, sum(minutos),
       count(*) FILTER (WHERE minutos < 3), count(*) FILTER (WHERE minutos BETWEEN 3 AND 5), …
FROM vistas GROUP BY nucleo, linea, hora
```

- `FILTER (WHERE …)` es SQL estándar: cuenta solo las filas que cumplen, en la misma pasada. Equivale a `sum(CASE WHEN … THEN 1 ELSE 0 END)`.
- **Por qué sumas y no medias:** la media de un mes no es la media de las medias diarias (un domingo con 20 trenes pesaría como un lunes con 200). Con suma y recuento, `sum(suma) / sum(paradas)` es exacto para cualquier periodo.
- **Viajes no se puede sumar por horas:** un tren que pasa por las 8 y las 9 contaría dos veces. Por eso `estad_linea_dia` guarda los viajes distintos del día; entre días sí se suman (un `trip_id` es un viaje de un día).

### El histograma y los percentiles

```sql
por_minuto AS (SELECT nucleo, linea, hora, least(greatest(minutos, 0), 60) AS m, count(*) AS n … GROUP BY …),
histogramas AS (
  SELECT k.nucleo, k.linea, k.hora, array_agg(coalesce(p.n, 0) ORDER BY g.m) AS histograma
  FROM (SELECT DISTINCT nucleo, linea, hora FROM base WHERE valida) k
  CROSS JOIN generate_series(0, 60) AS g(m)
  LEFT JOIN por_minuto p ON …)
```

- Un `integer[]` de 61 posiciones: cuántas paradas con 0 (o menos), 1, 2… 60 (o más) minutos. `generate_series` asegura las 61 aunque estén a cero.
- La mediana de un periodo sale **sumando los histogramas posición a posición** y buscando el primer minuto en que el acumulado llega al 50 % (`Acumulado.percentil`). Es exactamente `percentile_disc(0.5) WITHIN GROUP (ORDER BY least(greatest(minutos, 0), 60))` sobre las filas originales; una prueba lo compara con 500 listas al azar.
- Los percentiles **no se pueden sumar** como las medias: por eso se guarda el histograma y no la mediana de cada día.

### Los tramos: `LAG`

```sql
SELECT …, lag(stop_sequence) OVER w AS seq_anterior, lag(minutos) OVER w AS minutos_anterior, lag(valida) OVER w …,
       (SELECT max(s.stop_sequence) FROM horario.stop_times s
        WHERE s.trip_id = b.trip_id AND s.stop_sequence < b.stop_sequence) AS seq_anterior_horario
FROM base b WINDOW w AS (PARTITION BY trip_id ORDER BY stop_sequence)
…
WHERE valida AND valida_anterior AND (NOT en_horario OR seq_anterior = seq_anterior_horario)
GROUP BY nucleo, linea, stop_anterior, stop_id    -- sum(minutos - minutos_anterior)
```

- `LAG` mira la fila anterior **del mismo viaje** (`PARTITION BY trip_id`) en orden de parada. La diferencia de retraso entre las dos es lo que el tren **gana o pierde** en ese tramo.
- **Las inferidas entran en el `LAG` pero no en el resultado:** así una parada inferida corta el tramo en vez de unir dos paradas que no son seguidas.
- **Por qué se mira el horario:** `stop_sequence` de Renfe tiene huecos (1, 2, 4, 5) en el 1,5 % de los viajes, así que «la anterior» no es «la secuencia menos 1»; y si una parada no se cerró, la fila anterior de `retrasos` sería dos paradas atrás. La subconsulta pregunta al horario cuál es de verdad la anterior.

## 4. Por qué agregados precalculados y no consultas al vuelo

1. **Las fuentes desaparecen.** El horario se sustituye cada noche (ADR 0006) y `posiciones` se borra a las 48-72 h (ADR 0009). La cobertura (viajes programados) y los cancelados **solo se pueden calcular esa noche**. Por eso la tarea va a las 03:45: después del último tren (~01:30) y antes del horario nuevo (04:30).
2. **Coste.** Cuatro semanas de un núcleo son ~1,4 M filas por petición, en un PostgreSQL B1ms con 15 s de plazo. Los agregados son ~2.000 filas por día.
3. **Exactitud.** Con sumas, recuentos e histograma no se pierde nada (ver arriba).

**Lo que te preguntarán:**
- **«¿Y si la tarea falla una noche?»** Al día siguiente recupera los dos días anteriores que no estén calculados (`estad_dias`), y hay un endpoint de Actuator (`POST /actuator/estadisticas` con `desde` y `hasta`) que solo se alcanza desde la máquina.
- **«¿Es idempotente?»** Cada día va en una transacción que borra lo de ese día y lo vuelve a escribir. Hay una prueba que calcula dos veces y compara las tablas. Lo que vino del horario o de `posiciones` y ya no está se **conserva** de la versión anterior (tabla temporal `estad_anterior`).
- **«¿Y si coincide con la importación del horario?»** El cambio de esquema (`ALTER SCHEMA … RENAME`) no espera a quien lee, así que a mitad del cálculo podría aparecer el horario nuevo. El cálculo toma en modo compartido el cerrojo consultivo de la importación: si hay una en marcha, falla en 5 s; si llega una, se descarta y se repite a las 06:30.
- **«¿No compite con la ingesta?»** Solo lee `retrasos` y `posiciones` (`ACCESS SHARE`, compatible con `INSERT`) y escribe en sus propias tablas. Lleva `lock_timeout` de 5 s (si algo retiene sus tablas, falla en vez de hacer cola: está probado), `statement_timeout` de 2 min (con el plazo de red de la conexión por encima: si no, el `socketTimeout` de 30 s del driver cortaría antes y sin un error limpio) y un `pg_advisory_xact_lock` por día para que la nocturna y un recálculo manual no escriban el mismo día a la vez. El programador pasa a 4 hilos para no quitarle el suyo a la ingesta.
- **«¿Por qué no vistas materializadas?»** `REFRESH` recalcula todo el histórico, y no podría conservar lo que vino de fuentes que ya no existen.

## 5. Los sesgos y cómo se cubren

| Sesgo | Qué pasa | Cómo se cubre |
|---|---|---|
| **Paradas inferidas** | El tren se saltó la parada entre dos muestras; su retraso es una copia del de la anterior (ADR 0012) | Fuera de las cifras; cada respuesta dice cuántas se han quitado |
| **Trenes no vistos** | Si Renfe no emite un tren, no está en ninguna cifra. Si justo faltan los que van mal, la cifra sale mejor | **Cobertura** en cada respuesta (vistos / programados, por núcleo y por línea). No se corrige: se avisa |
| **Núcleos con poca cobertura** | Una cifra de Cádiz con un 50 % no vale lo mismo que una de Madrid con un 93 % | La cobertura va al lado de la cifra; la web la enseñará siempre |
| **Minutos enteros** | Renfe da el retraso en múltiplos de 60 s: 2 min 59 s reales pueden salir como 2 o 3 | Por eso se publican medias y porcentajes, no «el tren X llegó con 2 min 30 s». Una media de minutos enteros sí puede tener decimales y es exacta |
| **Muestras pequeñas** | Una franja con 5 trenes baila de una semana a otra | Muestra mínima de 30 viajes: por debajo, «datos insuficientes» |
| **Cancelados** | Solo se ven los que llegan a `vehicle_positions` con `CANCELED`; los que solo están en `trip_updates` no dejan fila | Se dicen como «cancelados explícitos» (cota inferior) y nunca entran en el retraso |
| **Viajes que dejan de verse** | Un tren que deja de verse a mitad ha hecho menos recorrido: si su última parada vista contara como «llegada», la cifra por viaje saldría mejor | La cifra por viaje es **solo de los que se vieron en su destino** (la última parada del horario, que se mira esa noche); los demás se cuentan aparte |
| **Cobertura por viajes** | Un tren visto en 2 de sus 20 paradas cuenta entero como «visto» | La cobertura también va **por paradas** (con fila / programadas) |

## 6. Cómo explicar de dónde sale una cifra

Ejemplo: «la RL4 llega con 2,4 min de media a las 8:00» (cifra inventada para el ejemplo; las reales, a partir del 19-12).

1. **Qué es:** la media del retraso con que los trenes de la RL4 llegaron a cada parada programada entre las 8:00 y las 8:59 (hora de Madrid), en las últimas 4 semanas, contando solo las paradas en que el tren se vio en directo.
2. **Con cuánto:** «son 214 paradas de 41 viajes» (sale en `porHora[].cifras.viajes` y `paradas`).
3. **Lo que no entra:** «quité 12 paradas que no se vieron y se rellenaron» (`muestra.paradasInferidasQuitadas`), «vi el 88 % de los trenes que daba el horario» (`coberturaLinea`).
4. **Cómo se comprueba:** la misma cifra sale con una consulta directa sobre `retrasos`:

```sql
SELECT round(avg(minutos), 1), count(*), count(DISTINCT (fecha_servicio, trip_id))
FROM retrasos
WHERE nucleo = 51 AND linea = 'RL4' AND NOT inferido AND llegada_programada IS NOT NULL
  AND fecha_servicio BETWEEN '2026-11-21' AND '2026-12-18'
  AND extract(hour FROM llegada_programada AT TIME ZONE 'Europe/Madrid') = 8;
```

El informe de la fase (`docs/informes/fase-6.md`, §5) tiene tres cifras comprobadas así contra la API con datos reales.

## 7. Preguntas que te pueden hacer

- **«¿Por qué parada y no viaje?»** Es lo que vive el viajero en su andén y tiene más muestra; el viaje (última parada vista) va como cifra secundaria. Lo que no hago es mezclarlas.
- **«¿Por qué 3 minutos?»** Es el primer color del mapa y un umbral habitual para cercanías; con 5 min casi todo sería puntual.
- **«¿Por qué 30 viajes?»** Error típico de la media de unos 0,7 min con la dispersión vista; con 10 serían 1,3 min, más que la resolución de los datos.
- **«¿Cómo pruebas que las cifras están bien?»** Con Testcontainers: un PostgreSQL de verdad con 20 filas escritas a mano y las cifras calculadas a mano en los comentarios de la prueba (medias, porcentajes, mediana, p90, el día de cambio de hora, un tren pasada la medianoche, inferidas, autobús, cancelado, muestra mínima, tramos con un hueco en `stop_sequence`). Y con datos reales: tres cifras recalculadas con SQL directo contra la API.
- **«¿Qué harías con más tiempo?»** Contar los cancelados que solo vienen en `trip_updates` (hoy se pierden), intervalos de confianza en vez de un umbral fijo, y comparar con las cifras oficiales de Renfe cuando las publique.
