-- Comprobación a mano de la fase 6: tres cifras recalculadas con SQL directo sobre retrasos (21-11-2026, C5 de Madrid).
\pset footer off
-- Las filas que vio el cálculo: las registradas antes de que se calculara el día (la ingesta sigue escribiendo).
SELECT calculado AS corte FROM estad_dias WHERE fecha_servicio = '2026-11-21' \gset
\echo 'Corte:' :corte

\echo '1) Media de la C5 a las 13:00 (hora programada de Madrid)'
SELECT round(avg(minutos), 1) AS media, count(*) AS paradas, count(DISTINCT trip_id) AS viajes
FROM retrasos
WHERE fecha_servicio = '2026-11-21' AND nucleo = 10 AND linea = 'C5' AND registrado <= :'corte'
  AND NOT inferido AND llegada_programada IS NOT NULL
  AND extract(hour FROM llegada_programada AT TIME ZONE 'Europe/Madrid') = 13;

\echo '2) Porcentajes de la C5 en todo el día: menos de 3 min y 5 min o menos; mediana y p90'
SELECT round(100.0 * count(*) FILTER (WHERE minutos < 3) / count(*), 1) AS menos_de_3,
       round(100.0 * count(*) FILTER (WHERE minutos <= 5) / count(*), 1) AS hasta_5,
       round(avg(minutos), 1) AS media,
       percentile_disc(0.5) WITHIN GROUP (ORDER BY least(greatest(minutos, 0), 60)) AS mediana,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY least(greatest(minutos, 0), 60)) AS p90,
       count(*) AS paradas, count(DISTINCT trip_id) AS viajes
FROM retrasos
WHERE fecha_servicio = '2026-11-21' AND nucleo = 10 AND linea = 'C5' AND registrado <= :'corte'
  AND NOT inferido AND llegada_programada IS NOT NULL;

\echo '3) Tramo Embajadores (35609) -> Laguna (35608) de la C5: minutos perdidos de media'
WITH filas AS (
    SELECT r.trip_id, r.stop_id, r.stop_sequence, r.minutos,
           (NOT r.inferido AND r.llegada_programada IS NOT NULL) AS valida,
           lag(r.stop_id) OVER w AS stop_anterior,
           lag(r.stop_sequence) OVER w AS seq_anterior,
           lag(r.minutos) OVER w AS minutos_anterior,
           lag(NOT r.inferido AND r.llegada_programada IS NOT NULL) OVER w AS valida_anterior
    FROM retrasos r
    WHERE r.fecha_servicio = '2026-11-21' AND r.nucleo = 10 AND r.linea = 'C5' AND r.registrado <= :'corte'
    WINDOW w AS (PARTITION BY r.trip_id ORDER BY r.stop_sequence))
SELECT round(avg(f.minutos - f.minutos_anterior), 1) AS media_perdida, count(*) AS pasos,
       round(100.0 * count(*) FILTER (WHERE f.minutos - f.minutos_anterior >= 2) / count(*), 1) AS pierden_2_o_mas
FROM filas f
WHERE f.stop_anterior = '35609' AND f.stop_id = '35608' AND f.valida AND f.valida_anterior
  -- seguidas según el horario: la anterior de esta parada en el viaje es justo la fila anterior
  AND f.seq_anterior = (SELECT max(s.stop_sequence) FROM horario.stop_times s
                        WHERE s.trip_id = f.trip_id AND s.stop_sequence < f.stop_sequence);

\echo 'Los pasos del tramo, uno a uno'
WITH filas AS (
    SELECT r.trip_id, r.stop_id, r.minutos, r.llegada_programada,
           lag(r.stop_id) OVER w AS stop_anterior, lag(r.minutos) OVER w AS minutos_anterior,
           lag(r.inferido) OVER w AS inferido_anterior, r.inferido
    FROM retrasos r
    WHERE r.fecha_servicio = '2026-11-21' AND r.nucleo = 10 AND r.linea = 'C5' AND r.registrado <= :'corte'
    WINDOW w AS (PARTITION BY r.trip_id ORDER BY r.stop_sequence))
SELECT trip_id, to_char(llegada_programada AT TIME ZONE 'Europe/Madrid', 'HH24:MI') AS llegada_laguna,
       minutos_anterior AS en_embajadores, minutos AS en_laguna, minutos - minutos_anterior AS diferencia
FROM filas
WHERE stop_anterior = '35609' AND stop_id = '35608' AND NOT inferido AND NOT inferido_anterior
ORDER BY llegada_programada;
