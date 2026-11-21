# ADR 0023 · Agregados diarios con sumas, recuentos e histograma, calculados cada noche antes del horario nuevo

**Fecha:** 21-11-2026 · **Estado:** aceptada · **Fase:** 6 · **Se apoya en:** ADR 0006 (horario por esquemas), 0009 (particiones) y 0022 (definiciones)

## Contexto

Las cifras del ADR 0022 salen de `retrasos` (~51.000 filas al día, para siempre), pero dos cosas que hacen falta **desaparecen**:

- el **horario** se sustituye entero cada noche a las 04:30 (ADR 0006): con él se va cuántos viajes había programados (la cobertura) y qué parada va antes de cuál en cada viaje (los tramos: `stop_sequence` tiene huecos en el 1,5 % de los viajes de Renfe, así que «la anterior» no es «la secuencia menos uno»);
- `posiciones` se borra a las 48-72 h (ADR 0009): con ella se van los `CANCELED` explícitos.

Y la API tiene que responder en milisegundos para cuatro semanas de una línea o un núcleo entero, en una máquina con 1 vCPU y un PostgreSQL B1ms (ADR 0017).

## Decisión

### Agregados que se guardan para siempre (V6)

Una fila por día de servicio y clave, con **sumas y recuentos, nunca medias**: sumar días y semanas da el resultado exacto, como si se calculara sobre las filas originales.

| Tabla | Clave | Qué guarda |
|---|---|---|
| `estad_linea_hora` | día, núcleo, línea, hora | viajes, paradas, suma de minutos, paradas por tramo (< 3, 3-5, 6-15, ≥ 16), **histograma** por minuto, y la llegada a la última parada vista de cada viaje (viajes, suma, < 3) |
| `estad_linea_dia` | día, núcleo, línea | viajes con alguna parada vista, viajes con alguna fila (cobertura), paradas inferidas y sin hora, **viajes programados** (del horario) y **cancelados** (de `posiciones`) |
| `estad_estacion_dia` | día, estación, núcleo, línea | lo mismo que por hora (sin la llegada final) y las inferidas |
| `estad_tramo_dia` | día, núcleo, línea, parada de salida, parada de llegada | pasos, suma de la diferencia de retraso, pasos que pierden 2 min o más y que ganan 2 min o más |
| `estad_dias` | día | cuándo se calculó, filas leídas y si había horario y `posiciones` para ese día |

- **El histograma** es un `integer[]` de 61 posiciones: la *i* cuenta las paradas con *i* minutos; la 0, también las de 0 o menos (adelantos), y la 60, las de 60 o más. De él salen la mediana y el p90 de cualquier periodo, sumando los histogramas posición a posición. La suma de minutos va aparte, sin recortar, para que la media sea exacta.
- **Los tramos** se calculan con `LAG` sobre las filas del viaje en orden de `stop_sequence` (las inferidas también, para que una parada inferida corte el tramo en vez de unir dos que no son seguidas). Un par cuenta si las dos paradas son vistas y, según el horario de ese día, la primera es justo la anterior de la segunda. Si el horario ya no tiene el viaje (al recalcular un día viejo), vale la fila anterior de `retrasos`.
- **Volumen:** unas 2.000 filas por día entre todas las tablas, frente a las ~51.000 de `retrasos`. Sin particiones: en diez años son ~7 M filas pequeñas.

### Cálculo: cada noche, idempotente, sin molestar a la ingesta

- **Cuándo:** `@Scheduled` a las **03:45 de Madrid** (`trainwatch.estadisticas.cron`). A esa hora ha terminado el día de servicio de ayer (los últimos trenes llegan hacia las 01:30) y aún no ha llegado el horario nuevo (04:30). Calcula **ayer** y, por si la aplicación estuvo parada a esa hora, los dos días anteriores que no estén en `estad_dias`.
- **Idempotente:** cada día va en **una transacción** que borra lo de ese día en las cinco tablas y lo vuelve a escribir con `INSERT … SELECT`. Recalcular da lo mismo; quien lee la API ve el día viejo o el nuevo, nunca uno a medias (MVCC).
- **Lo que ya no está no se pierde:** si al recalcular el horario ya no cubre ese día, los viajes programados se conservan de la fila anterior; si la partición de `posiciones` ya se borró, se conservan los cancelados. Lo que sale de `retrasos` se sustituye siempre.
- **A mano:** un endpoint de Actuator, `POST /actuator/estadisticas` con `{"desde": "2026-11-21", "hasta": "2026-11-27"}`, recalcula un intervalo (como mucho 400 días), y `GET /actuator/estadisticas` enseña los últimos cálculos. No es público: en producción Actuator escucha en el 8081, que no publican ni Caddy ni el grupo de seguridad y solo se alcanza desde la máquina (`application-prod.properties`).
- **Cerrojos:** la tarea **solo lee** `retrasos`, `posiciones` y el horario (`ACCESS SHARE`, compatible con los `INSERT` de la ingesta) y solo escribe en sus tablas, que la ingesta no toca. Cada transacción lleva `lock_timeout` de 5 s (si algo retiene sus tablas, falla y se reintenta la noche siguiente o a mano, en vez de hacer cola) y `statement_timeout` de 2 min (el de la aplicación es 15 s). Un `pg_advisory_xact_lock` por día evita que la nocturna y un recálculo manual escriban el mismo día a la vez. El programador pasa de 3 a **4 hilos**: la tarea no le quita el suyo a la ingesta.
- **Con el horario:** el `ALTER SCHEMA … RENAME` de la importación espera a que termine la transacción de la tarea (unos segundos) y no al revés; las dos tareas van a horas distintas.

## Alternativas

- **Consultas al vuelo sobre `retrasos`:** sin tablas nuevas, pero cuatro semanas de un núcleo son ~1,4 M filas por petición, en un B1ms y con 15 s de plazo; y la cobertura y los cancelados no se podrían calcular porque sus fuentes ya no existen.
- **Vistas materializadas:** `REFRESH` recalcula todo el histórico cada noche (o hace falta `CONCURRENTLY` con índices únicos) y no permite conservar lo que vino del horario y de `posiciones`.
- **Guardar medias por día:** la media de cuatro semanas no es la media de las medias diarias (un domingo con 20 trenes pesaría como un lunes con 200). Por eso, sumas y recuentos.
- **Histograma en filas** (una por día, clave y minuto): igual de exacto y se suma con `GROUP BY` puro, pero multiplica las filas por 5-10. El `integer[]` se suma en Java sobre unas cientos de filas por petición.
- **Calcular en Java leyendo las filas del día:** igual de rápido con 51.000 filas, pero mueve los datos por la red y el SQL con `GROUP BY` y `LAG` se puede comprobar a mano con la misma consulta (ADR 0022, fase 6 del plan).
- **Recalcular al terminar cada viaje:** cifras al minuto, pero la tarea competiría con la ingesta todo el día por la misma máquina, y la cobertura del día no se sabe hasta que acaba.

## Consecuencias

- Las cifras de hoy no salen hasta mañana a las 03:45: es una página de estadísticas, no el directo.
- Si la aplicación está parada a las 03:45 tres noches seguidas, el cuarto día viejo ya no se recalcula solo (hay que hacerlo a mano) y su cobertura y sus cancelados se pierden si para entonces el horario y `posiciones` ya no lo tienen.
- Los cancelados solo cuentan los trenes que llegaron a `posiciones` con estado `CANCELADO` (es decir, que estaban en `vehicle_positions`); un `CANCELED` que solo aparece en `trip_updates` no deja fila y no se cuenta. Es una cota inferior, y así se dice.
