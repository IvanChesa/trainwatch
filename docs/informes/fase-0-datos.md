# Resultados de la captura (generado por `Analisis.java`)

- Día de servicio: **2026-11-20** (viernes)
- Captura: de 09:59:29 a 10:29:15 (hora de Madrid), **90 muestras**, 29,8 min
- GTFS estático cargado en 2,3 s

## 1. GTFS estático

| Fichero | Tamaño sin comprimir | Filas |
|---|---:|---:|
| `agency.txt` | 0,0 MB | 1 |
| `calendar.txt` | 0,1 MB | 450 |
| `routes.txt` | 0,0 MB | 277 |
| `shapes.txt` | 4,0 MB | 123.734 |
| `stops.txt` | 0,2 MB | 1.167 |
| `stop_times.txt` | 258,2 MB | 1.793.333 |
| `transfers.txt` | 0,0 MB | 19 |
| `trips.txt` | 18,8 MB | 130.297 |

- Servicios (service_id) que circulan el 2026-11-20: 15. Viajes del día: **4.964**.
- Viajes del día con `shape_id` presente en `shapes.txt`: 4.930 de 4.964 (99,3 %).
- `shapes.txt`: 144 recorridos distintos; prefijos: [10, 20, 30, 31, 32, 40, 41, 45, 46, 47, 51, 60, 61, 62, 70].
- `calendar_dates.txt`: no viene en el ZIP.

## 2. Cada cuánto se actualiza el feed

Con muestras cada 20 s, un intervalo de 20 s entre cabeceras solo dice que el feed cambia **al menos** tan a menudo como se muestrea. La antigüedad se mide desde el inicio de la muestra (redondeado al segundo), antes de descargar los tres feeds, así que en `trip_updates` y `alerts` puede salir algo menor de lo real y hasta negativa.

| Feed | Cabeceras distintas / muestras | Intervalo entre cabeceras nuevas (mediana · mín · máx) | Antigüedad de la cabecera al descargar (mediana · p95 · máx) |
|---|---:|---|---|
| `vehicle_positions` | 90 / 90 | 20 · 16 · 25 s | 10 · 14 · 16 s |
| `trip_updates` | 90 / 90 | 20 · 16 · 25 s | 10 · 14 · 16 s |
| `alerts` | 90 / 90 | 20 · 13 · 26 s | 7 · 11 · 14 s |

- Marca de tiempo de cada tren respecto a la cabecera del feed: mediana 3 s, p95 6 s, máx. 8 s. A ≤ 5 s de la cabecera: 88,6 % de las observaciones.
- Intervalo entre marcas nuevas del mismo tren: mediana 20 s, p25 20 s, p75 20 s.
- Entre dos muestras seguidas, el tren cambia de coordenadas en el 16,5 % de los casos (31.196 pares).

## 3. ¿Encuentran los trenes en directo su viaje en el horario?

Se cuenta cada `trip_id` distinto visto en `vehicle_positions` durante la captura. «Exacto» = el `trip_id` está en `trips.txt`. «Por número» = no está, pero sí el mismo núcleo + número de tren + línea con otro código de día. «Con retraso» = el viaje aparece también en `trip_updates`.

### Por núcleo

| Grupo | Trenes distintos | Exacto | % exacto | Del día | Por número | Con retraso | Nunca con coordenadas |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10 Madrid | 141 | 139 | 98,6 % | 139 | 0 | 140 | 0 |
| 20 Asturias | 44 | 44 | 100,0 % | 44 | 0 | 44 | 0 |
| 30 Sevilla | 8 | 8 | 100,0 % | 8 | 0 | 8 | 1 |
| 32 Málaga | 11 | 11 | 100,0 % | 11 | 0 | 11 | 0 |
| 40 Valencia | 43 | 43 | 100,0 % | 43 | 0 | 43 | 0 |
| 41 Murcia/Alicante | 13 | 13 | 100,0 % | 13 | 0 | 13 | 0 |
| 45 Cartagena (ancho métrico) | 6 | 6 | 100,0 % | 6 | 0 | 6 | 0 |
| 46 Ferrol (ancho métrico) | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 47 León (ancho métrico) | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 51 Rodalies de Catalunya | 142 | 142 | 100,0 % | 142 | 0 | 142 | 0 |
| 61 San Sebastián | 12 | 12 | 100,0 % | 12 | 0 | 12 | 0 |
| 62 Santander | 28 | 28 | 100,0 % | 28 | 0 | 28 | 0 |
| 70 Zaragoza | 3 | 3 | 100,0 % | 3 | 0 | 3 | 0 |
| **Total** | **461** | **459** | **99,6 %** | 459 | 0 | 460 | 1 |

### Rodalies de Catalunya, por línea

| Grupo | Trenes distintos | Exacto | % exacto | Del día | Por número | Con retraso | Nunca con coordenadas |
|---|---:|---:|---:|---:|---:|---:|---:|
| R1 | 32 | 32 | 100,0 % | 32 | 0 | 32 | 0 |
| R11 | 10 | 10 | 100,0 % | 10 | 0 | 10 | 0 |
| R15 | 8 | 8 | 100,0 % | 8 | 0 | 8 | 0 |
| R16 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| R17 | 3 | 3 | 100,0 % | 3 | 0 | 3 | 0 |
| R2 | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| R2N | 13 | 13 | 100,0 % | 13 | 0 | 13 | 0 |
| R2S | 24 | 24 | 100,0 % | 24 | 0 | 24 | 0 |
| R3 | 8 | 8 | 100,0 % | 8 | 0 | 8 | 0 |
| R4 | 29 | 29 | 100,0 % | 29 | 0 | 29 | 0 |
| R8 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| RG1 | 2 | 2 | 100,0 % | 2 | 0 | 2 | 0 |
| RL4 | 2 | 2 | 100,0 % | 2 | 0 | 2 | 0 |
| **Total** | **142** | **142** | **100,0 %** | 142 | 0 | 142 | 0 |

### Resto de núcleos, por línea

| Grupo | Trenes distintos | Exacto | % exacto | Del día | Por número | Con retraso | Nunca con coordenadas |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10 C1 | 11 | 11 | 100,0 % | 11 | 0 | 11 | 0 |
| 10 C10 | 15 | 15 | 100,0 % | 15 | 0 | 15 | 0 |
| 10 C2 | 20 | 20 | 100,0 % | 20 | 0 | 20 | 0 |
| 10 C3 | 9 | 9 | 100,0 % | 9 | 0 | 9 | 0 |
| 10 C4 | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| 10 C4a | 14 | 14 | 100,0 % | 14 | 0 | 14 | 0 |
| 10 C4b | 16 | 16 | 100,0 % | 16 | 0 | 16 | 0 |
| 10 C5 | 29 | 29 | 100,0 % | 29 | 0 | 29 | 0 |
| 10 C7 | 13 | 12 | 92,3 % | 12 | 0 | 13 | 0 |
| 10 C8 | 1 | 0 | 0,0 % | 0 | 0 | 0 | 0 |
| 10 C8a | 6 | 6 | 100,0 % | 6 | 0 | 6 | 0 |
| 10 C8b | 4 | 4 | 100,0 % | 4 | 0 | 4 | 0 |
| 10 C9 | 2 | 2 | 100,0 % | 2 | 0 | 2 | 0 |
| 20 C1 | 9 | 9 | 100,0 % | 9 | 0 | 9 | 0 |
| 20 C2 | 4 | 4 | 100,0 % | 4 | 0 | 4 | 0 |
| 20 C3 | 7 | 7 | 100,0 % | 7 | 0 | 7 | 0 |
| 20 C4 | 6 | 6 | 100,0 % | 6 | 0 | 6 | 0 |
| 20 C5 | 4 | 4 | 100,0 % | 4 | 0 | 4 | 0 |
| 20 C5a | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| 20 C6 | 6 | 6 | 100,0 % | 6 | 0 | 6 | 0 |
| 20 C7 | 4 | 4 | 100,0 % | 4 | 0 | 4 | 0 |
| 20 C8 | 3 | 3 | 100,0 % | 3 | 0 | 3 | 0 |
| 30 C1 | 2 | 2 | 100,0 % | 2 | 0 | 2 | 0 |
| 30 C3 | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| 30 C4 | 3 | 3 | 100,0 % | 3 | 0 | 3 | 1 |
| 30 C5 | 2 | 2 | 100,0 % | 2 | 0 | 2 | 0 |
| 32 C1 | 10 | 10 | 100,0 % | 10 | 0 | 10 | 0 |
| 32 C2 | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| 40 C1 | 12 | 12 | 100,0 % | 12 | 0 | 12 | 0 |
| 40 C2 | 10 | 10 | 100,0 % | 10 | 0 | 10 | 0 |
| 40 C3 | 8 | 8 | 100,0 % | 8 | 0 | 8 | 0 |
| 40 C5 | 1 | 1 | 100,0 % | 1 | 0 | 1 | 0 |
| 40 C6 | 12 | 12 | 100,0 % | 12 | 0 | 12 | 0 |
| 41 C1 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 41 C2 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 41 C3 | 3 | 3 | 100,0 % | 3 | 0 | 3 | 0 |
| 45 C1 | 6 | 6 | 100,0 % | 6 | 0 | 6 | 0 |
| 46 C1 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 47 C1 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 61 C1 | 12 | 12 | 100,0 % | 12 | 0 | 12 | 0 |
| 62 C1 | 5 | 5 | 100,0 % | 5 | 0 | 5 | 0 |
| 62 C2 | 14 | 14 | 100,0 % | 14 | 0 | 14 | 0 |
| 62 C3 | 9 | 9 | 100,0 % | 9 | 0 | 9 | 0 |
| 70 C1 | 3 | 3 | 100,0 % | 3 | 0 | 3 | 0 |
| **Total** | **319** | **317** | **99,4 %** | 317 | 0 | 318 | 1 |

- Ponderado por observaciones (tren × muestra): 31.815 de 31.855 observaciones tienen su viaje exacto (99,9 %).
- `trip_updates`: 467 viajes distintos, 466 con su viaje en el horario (99,8 %).
- Trenes en `vehicle_positions` sin entrada en `trip_updates`: 1 de 461.
- Observaciones sin coordenadas (sin `position` o 0,0): 161; por núcleo {10=6, 20=6, 30=124, 32=1, 40=4, 47=1, 51=17, 61=1, 62=1} (de {10=9151, 20=2927, 30=556, 32=712, 40=3085, 41=822, 45=326, 46=303, 47=413, 51=10605, 61=852, 62=1940, 70=163}).
- `trip_id` sin viaje exacto (2): [`SPECIAL_10_91886C7`, `SPECIAL_10_91886C8`]

## 4. Al revés: ¿salen en el directo los trenes que el horario da en marcha?

Media por muestra. Un viaje cuenta como «en marcha» entre su primera salida y su última llegada programadas. Se excluyen los 632 viajes de autobús del día (`route_type` 3: servicios alternativos por obras), que nunca salen en el directo, y los que `trip_updates` marca como `CANCELED` en esa muestra (5,1 por muestra).

| Núcleo · línea | Viajes de tren del día | En marcha según horario | De ellos, en el directo | % en directo |
|---|---:|---:|---:|---:|
| 10 Madrid | 1289 | 55,8 | 48,2 | 86,3 % |
| 20 Asturias | 460 | 23,5 | 20,2 | 85,9 % |
| 30 Sevilla | 156 | 9,3 | 2,9 | 31,1 % |
| 31 Cádiz | 146 | 4,7 | 0,0 | 0,0 % |
| 32 Málaga | 134 | 5,5 | 4,9 | 89,8 % |
| 40 Valencia | 324 | 20,1 | 16,2 | 80,7 % |
| 41 Murcia/Alicante | 70 | 3,3 | 2,2 | 65,7 % |
| 45 Cartagena (ancho métrico) | 44 | 1,5 | 1,5 | 97,8 % |
| 46 Ferrol (ancho métrico) | 34 | 1,0 | 1,0 | 100,0 % |
| 47 León (ancho métrico) | 31 | 2,6 | 2,6 | 100,0 % |
| 51 Rodalies de Catalunya · R1 | 190 | 12,8 | 12,8 | 100,0 % |
| 51 Rodalies de Catalunya · R11 | 50 | 6,2 | 5,3 | 86,3 % |
| 51 Rodalies de Catalunya · R13 | 18 | 1,2 | 0,0 | 0,0 % |
| 51 Rodalies de Catalunya · R14 | 6 | 0,0 | 0,0 | — |
| 51 Rodalies de Catalunya · R15 | 36 | 5,8 | 3,4 | 59,0 % |
| 51 Rodalies de Catalunya · R16 | 25 | 2,3 | 1,5 | 68,5 % |
| 51 Rodalies de Catalunya · R17 | 6 | 0,5 | 0,5 | 100,0 % |
| 51 Rodalies de Catalunya · R2 | 54 | 0,0 | 0,0 | — |
| 51 Rodalies de Catalunya · R2N | 72 | 5,3 | 5,3 | 100,0 % |
| 51 Rodalies de Catalunya · R2S | 143 | 10,1 | 9,0 | 89,5 % |
| 51 Rodalies de Catalunya · R3 | 34 | 3,4 | 3,4 | 100,0 % |
| 51 Rodalies de Catalunya · R4 | 163 | 18,0 | 17,3 | 96,2 % |
| 51 Rodalies de Catalunya · R7 | 1 | 0,0 | 0,0 | — |
| 51 Rodalies de Catalunya · R8 | 33 | 0,9 | 0,9 | 100,0 % |
| 51 Rodalies de Catalunya · RG1 | 17 | 2,0 | 2,0 | 100,0 % |
| 51 Rodalies de Catalunya · RL3 | 12 | 0,1 | 0,0 | 0,0 % |
| 51 Rodalies de Catalunya · RL4 | 10 | 2,0 | 2,0 | 100,0 % |
| 51 Rodalies de Catalunya · RT1 | 10 | 0,0 | 0,0 | — |
| 51 Rodalies de Catalunya · RT2 | 16 | 0,0 | 0,0 | — |
| 60 Bilbao | 360 | 13,6 | 0,0 | 0,0 % |
| 61 San Sebastián | 71 | 5,6 | 5,2 | 93,5 % |
| 62 Santander | 222 | 11,1 | 9,8 | 88,2 % |
| 70 Zaragoza | 82 | 0,9 | 0,7 | 78,3 % |
| **Total** | **4319** | **229,2** | **179,0** | **78,1 %** |

- Del lado contrario, 36,8 % de los trenes del directo (130,1 por muestra) aún no han empezado su viaje: esperan en la estación de origen antes de su hora de salida (ver §5 bis).

## 5. Retrasos de `trip_updates`

- Paradas (`stop_time_update`) por viaje en la primera muestra: {0=6, 1=357, 15=1} (número de paradas → viajes).
- `schedule_relationship` en la primera muestra: {CANCELED=6, SCHEDULED=358}.
- Viajes con `delay` a nivel de viaje: 358; paradas con `arrival.delay`: 358 (primera muestra).
- Retrasos distintos de 0 que son múltiplos de 60 s: 100,0 % de 17.405 lecturas → **la resolución es de minutos**.
- `arrival.time − arrival.delay` coincide (±60 s) con la llegada programada de esa parada en `stop_times.txt`: 88,4 % de 31.722 (mediana del desvío 0 s).
- La parada de `trip_updates` es la misma que la próxima parada de `vehicle_positions`: 1,5 % de 16.028 lecturas.

**Distribución del último retraso conocido de cada viaje** (459 viajes):

| Retraso | Viajes | % |
|---|---:|---:|
| adelantado (< 0) | 15 | 3,3 % |
| 0 min | 158 | 34,4 % |
| 1-2 min | 50 | 10,9 % |
| 3-5 min | 38 | 8,3 % |
| 6-10 min | 64 | 13,9 % |
| 11-30 min | 101 | 22,0 % |
| > 30 min | 33 | 7,2 % |

**¿Cuadra el retraso con la posición?** Cuando un tren que ya estaba en el feed aparece detenido en una parada intermedia por primera vez, se compara (marca del tren − llegada programada a esa parada) con el retraso de `trip_updates` en esa misma muestra. Llegadas comparadas: 969. |diferencia| mediana 19 s, p90 57 s. Dentro de ±60 s: 92,4 %; ±120 s: 99,6 %; ±300 s: 100,0 %.

## 5 bis. Diagnóstico: posiciones, paradas y horario

- `current_status`: {INCOMING_AT=10239, IN_TRANSIT_TO=5957, STOPPED_AT=15659}.
- Distancia de la posición a la parada más cercana del GTFS: mediana 24 m, p90 549 m; a ≤ 50 m: 77,9 %.
- Distancia a la parada que indica el propio tren (`stop_id`): mediana 24 m; a ≤ 100 m: 82,7 %.
- Por tren, durante la captura: 11,5 posiciones distintas de media y 3,4 paradas (`stop_id`) distintas.
- Parada de `trip_updates` frente a la de `vehicle_positions` (posición en la secuencia del viaje): {INCOMING_AT: TU − VP = -4=5, INCOMING_AT: TU − VP = 0=233, INCOMING_AT: TU − VP = 1=9855, INCOMING_AT: parada fuera del viaje=90, IN_TRANSIT_TO: TU − VP = -4=4, IN_TRANSIT_TO: TU − VP = 1=5917, IN_TRANSIT_TO: parada fuera del viaje=20, STOPPED_AT: TU − VP = 0=7, STOPPED_AT: TU − VP = 1=15576, STOPPED_AT: parada fuera del viaje=15}.
- Momento de cada observación frente al horario de su viaje: antes de la primera salida 38,5 % (mediana 25 min antes), dentro 51,8 %, después de la última llegada pero dentro del retraso 9,3 %, después sin explicación 0,4 %; sin horario 40.
- De las observaciones anteriores a la salida: con `stop_id` = parada de origen 99,3 %; a ≤ 300 m del origen 98,3 %.
- Distancia a la parada más cercana con `INCOMING_AT`: mediana 20 m, p75 37 m, p90 55 m; a ≤ 50 m: 88,5 %.
- Distancia a la parada más cercana con `IN_TRANSIT_TO`: mediana 606 m, p75 1303 m, p90 2354 m; a ≤ 50 m: 30,2 %.
- Distancia a la parada más cercana con `STOPPED_AT`: mediana 21 m, p75 37 m, p90 53 m; a ≤ 50 m: 89,1 %.
- Viajes cuya línea de la etiqueta no coincide con `route_short_name`: ninguno.

## 6. Avisos (`alerts`)

- Avisos distintos en la captura: **70** (primera muestra 65, última 69; aparecen 5 nuevos y desaparecen 1).
- Por núcleo (según `route_id`): {Bilbao=7, Cádiz=3, Madrid=4, Murcia/Alicante=1, Málaga=4, Rodalies de Catalunya=5, San Sebastián=4, Sevilla=3, Valencia=5, solo estaciones=34}.
- Entidades afectadas por tipo: {route_id=310, stop_id=34}.
- `cause`: {(sin cause)=70}. `effect`: {(sin effect)=70}.
- Idiomas de la descripción: {es=106}. Con `header_text`: 0. Con `url`: 0. Con fecha de fin: 0 de 70.
- Antigüedad del inicio (días): mediana 12, máx. 528. Largo del texto: mediana 159 caracteres, máx. 635.
- Etiquetas tipo hashtag más usadas: [#MadC9×2, #DonostiaAldiriakC1×1, #LÍNEA×1, #MadC4b×1, #MadC5×1, #SanSebastianC1×1].
- Avisos de Rodalies (5):
  - AVISO_526429: Sin servicio ferroviario por obras en el entorno de Montcada Bifurcació. El trayecto entre Fabra i Puig y Montcada Bifurcació se realiza med…
  - AVISO_525563: Por obras de Adif en el entorno de Castelldefels, del 12 de noviembre al 14 de diciembre se modifica temporalmente el servicio de la línea R…
  - AVISO_521823: Circulación ferroviaria entre La Garriga - Puigcerdà/La Tor de Querol. Servicio alternativo por carretera entre Fabra i Puig - La Garriga. S…
  - AVISO_521807: Circulación ferroviaria entre Lleida Pirineus y Manresa. El tramo entre Manresa y Terrassa Estació del Nord se realiza mediante los servicio…
  - AVISO_521824: Servicio ferroviario entre Lleida Pirineus - Les Borges Blanques y entre La Plana Picamoixons - Barcelona Estació de França. Servicio altern…

## 7. Volumen

| Medida | Valor |
|---|---:|
| Trenes por muestra en `vehicle_positions` (media · mín · máx) | 353,9 · 242 · 367 |
| Entidades por muestra en `trip_updates` · `alerts` | 362,1 · 68,8 |
| Tamaño medio del .pb: posiciones · retrasos · avisos | 29,3 · 24,9 · 30,8 KB |
| Viajes de tren programados el día analizado (todos los núcleos, sin autobuses) | 4.319 |
| Pico de trenes en marcha según el horario | 326 a las 07:35 |
| Horas-tren programadas en el día | 4.445 |
| Entidades del feed por cada viaje programado en marcha, durante la captura (incluye trenes esperando en origen; no es cobertura) | 1,51 |
| Filas de posiciones al día con el horario completo (horas-tren × 180) | 800.163 |
| **Filas de posiciones al día** (lo anterior × entidades por viaje en marcha) | **1.208.821** |
| Filas de posiciones en 48 h | 2.417.641 |
| Observaciones que cambian algo (coordenadas, parada o estado) respecto a la anterior del mismo tren | 21,8 % |
| Filas de posiciones al día guardando solo los cambios | 263.604 |
| Filas de retraso final por parada al día, como máximo (todas las paradas programadas del día) | 65.671 |
| **Filas de retraso final por parada al día** (lo anterior × 78,1 % de viajes que salen en el directo, §4) | **51.303** |
| Filas de retraso al año | 18.725.516 |
| Descarga diaria de los tres feeds cada 20 s | 359 MB |

