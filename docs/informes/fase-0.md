# Fase 0 · Prueba técnica: informe

**Fecha:** 20-11-2026 (viernes). **Captura:** de 09:59 a 10:29, hora de Madrid, con 90 muestras cada 20 s de los tres feeds y 0 descargas fallidas. **Código:** `spike/` (Java 25, sin Spring, con `gtfs-realtime-bindings` 0.2.0 de MobilityData). **Cifras completas:** [`fase-0-datos.md`](fase-0-datos.md), generado por `Analisis.java`.

## Veredicto: **sigue con cambios**

El umbral del plan era replantear si en Rodalies faltaba más del 30 % de los viajes en el horario. **No falta ninguno**: los 142 trenes distintos de Rodalies que se vieron en directo, incluidos los 2 de la RL4 y los 2 de la RG1, tienen su `trip_id` exacto en el GTFS estático. El proyecto se sostiene.

Los cambios vienen de otros hallazgos, que el plan no preveía:

1. **Las posiciones no son un GPS continuo.** Van casi siempre pegadas a las estaciones, así que el movimiento suave hay que calcularlo con el horario, el retraso y el recorrido.
2. **Hay núcleos sin directo.** Bilbao y Cádiz no emiten ningún tren y Sevilla emite un tercio.
3. **El directo incluye trenes parados en origen,** una mediana de 25 min antes de su salida (el 37 % de lo que trae el feed).
4. **El retraso viene en minutos enteros y solo para la próxima parada.** Eso basta para la estadística, pero condiciona cómo se guarda.

## 1. El GTFS estático

`fomento_transit.zip`: **15,6 MB** comprimido (15.564.301 B), **295 MB** sin comprimir. Lo publicaron el 20-11 a las 03:09 GMT. Cubre **30 días**, del 20-11 al 19-12, con un `service_id` por núcleo y día (15 núcleos) y sin `calendar_dates.txt`.

| Fichero | Tamaño sin comprimir | Filas |
|---|---:|---:|
| `stop_times.txt` | 258,2 MB | 1.793.333 |
| `trips.txt` | 18,8 MB | 130.297 |
| **`shapes.txt`** | **4,0 MB** | **123.734** (144 recorridos) |
| `stops.txt` | 0,2 MB | 1.167 |
| `routes.txt` | 0,0 MB | 277 (222 de tren y 55 de autobús) |
| `calendar.txt` | 0,1 MB | 450 |
| `transfers.txt` · `agency.txt` | 0,0 MB | 19 · 1 |

- **Sí trae `shapes.txt`.** El 99,3 % de los viajes del día tiene su recorrido (4.930 de 4.964). Los recorridos van por línea y núcleo (`10_C1`, `51_R4`…), no por variante.
- **Trae autobuses.** El día analizado hay **632 viajes con `route_type` 3**: son los servicios alternativos por carretera de las obras (por ejemplo, la R3 entre Fabra i Puig y Vic). Comparten línea con los trenes («R3») y **nunca salen en el directo**.
- Viajes de tren del día: **4.319**. El pico, según el horario, es de **326 trenes** en marcha a las 07:35.
- `stop_times.txt` se lee en streaming desde el ZIP, sin descomprimirlo a disco. Leer todo el ZIP y quedarse con lo necesario tarda **2,3 s**.
- Formato: todos los campos van **rellenos con espacios** (`10T0001C1  ,C1  ,…`), así que hay que recortarlos al importar.

## 2. ¿Encuentran los trenes en directo su viaje en el horario?

Se cuenta cada `trip_id` distinto que aparece en `vehicle_positions` durante la captura.

| Núcleo | Trenes en directo | Con `trip_id` en el horario | % |
|---|---:|---:|---:|
| **51 Rodalies de Catalunya** | **142** | **142** | **100 %** |
| 10 Madrid | 141 | 139 | 98,6 % |
| 20 Asturias | 44 | 44 | 100 % |
| 40 Valencia | 43 | 43 | 100 % |
| 62 Santander | 28 | 28 | 100 % |
| 41 Murcia/Alicante | 13 | 13 | 100 % |
| 61 San Sebastián | 12 | 12 | 100 % |
| 32 Málaga | 11 | 11 | 100 % |
| 30 Sevilla | 8 | 8 | 100 % |
| 45 Cartagena · 46 Ferrol · 47 León | 6 · 5 · 5 | 6 · 5 · 5 | 100 % |
| 70 Zaragoza | 3 | 3 | 100 % |
| 31 Cádiz · 60 Bilbao | **0 · 0** | — | — |
| **Total** | **461** | **459** | **99,6 %** |

**Rodalies, por línea:** R1 32/32, R4 29/29, R2S 24/24, R2N 13/13, R11 10/10, R3 8/8, R15 8/8, R16 5/5, R8 5/5, R17 3/3, **RG1 2/2**, **RL4 2/2** y R2 1/1.

- Los 2 que fallan son de Madrid (C7 y C8): `SPECIAL_10_91886C7` y `SPECIAL_10_91886C8`, circulaciones especiales que no están en el horario.
- Ponderado por observaciones (tren × muestra), el 99,9 %. En `trip_updates`, 466 de 467 viajes (99,8 %).
- La línea de la etiqueta del tren (`R4-…`) coincide siempre con la `route_short_name` de su viaje.
- **Los «huecos en Cataluña» de datos.gob.es no se reproducen hoy.** La RG1 tiene 17 viajes en el horario del día y la RT2, 16. La RT2 no tenía ningún tren en marcha durante la captura, así que no se ha podido comprobar en directo.

### Al revés: ¿salen en el directo los trenes que el horario da en marcha?

De los viajes de tren que, según el horario, están entre su primera salida y su última llegada (sin autobuses ni cancelados), **sale en el directo el 78,1 %** (179 de 229 por muestra).

| Núcleo · línea | Viajes del día | En marcha (media) | En directo | % |
|---|---:|---:|---:|---:|
| **60 Bilbao** | 360 | 13,6 | **0** | **0 %** |
| **31 Cádiz** | 146 | 4,7 | **0** | **0 %** |
| **30 Sevilla** | 156 | 9,3 | 2,9 | **31 %** |
| 41 Murcia/Alicante | 70 | 3,3 | 2,2 | 66 % |
| 40 Valencia | 324 | 20,1 | 16,2 | 81 % |
| 10 Madrid | 1.289 | 55,8 | 48,2 | 86 % |
| 51 Rodalies (todas) | 896 | 70,6 | 63,4 | 90 % |
| · R1 · R2N · R3 · R8 · RG1 · **RL4** | | | | 100 % |
| · R4 · R2S · R11 | | | | 96 · 90 · 86 % |
| · R16 · R15 | | | | 69 · 59 % |
| · R13 | 18 | 1,2 | 0 | 0 % |

Resto: Asturias 86 %, Málaga 90 %, Santander 88 %, San Sebastián 94 %, Zaragoza 78 % y Cartagena, Ferrol y León ~100 %. Detalle en `fase-0-datos.md` §4.

- **Bilbao y Cádiz no emiten nada.** No sale ningún tren suyo en `vehicle_positions` en toda la captura, ni en `trip_updates` en una consulta posterior (10:45). Sí tienen avisos (7 y 3).
- Lo que falta en Rodalies son líneas regionales de Tarragona y el Ebro (R13, R15 y R16), justo las que tienen tramos con autobús por obras según los avisos. No se puede saber si esos trenes no circulan o si no emiten.

## 3. Cada cuánto se actualiza

- **Cada ~20 s.** Las 90 muestras traen cabeceras nuevas en los tres feeds. Además, consultando el feed cada 2 s durante 85 s, la cabecera cambió a los 18, 22, 20, 19 y 23 s.
- La cabecera tiene **~10 s** al descargarla (mediana; p95 14 s). La marca de cada tren va a 3 s de la cabecera (mediana).
- El servidor manda **`ETag`, `Last-Modified` y `Cache-Control: max-age=30`**. Se puede consultar más a menudo con peticiones condicionales (`If-None-Match`), con un 304 barato si no hay nada nuevo. Así el dato llega antes, sin bajar el feed entero cada vez.
- **Una muestra llegó incompleta** (la de las 10:29:15): 242 trenes y 20 KB, frente a ~360 trenes y 29 KB. La ingesta no puede dar por desaparecido un tren porque falte en una sola muestra.

## 4. ¿Cuadran los retrasos con las posiciones?

**Sí, dentro de la resolución del dato.** Se miraron 969 llegadas: trenes que ya estaban en el feed y aparecen detenidos por primera vez en una parada intermedia. En cada una se compara (marca del tren − llegada programada) con el retraso de `trip_updates` en la misma muestra:

- diferencia mediana de **19 s** (p90, 57 s);
- **92,4 %** dentro de ±60 s, **99,6 %** dentro de ±120 s y 100 % dentro de ±300 s.

Lo que hay que saber del formato:

- **Resolución de minutos.** El 100 % de los 17.405 retrasos distintos de 0 son múltiplos de 60 s.
- **Solo la próxima parada.** 357 de 364 viajes traen un único `stop_time_update`. 6 vienen como `CANCELED`, sin paradas, y 1 trae 15.
- `arrival.time − arrival.delay` coincide con la llegada programada del `stop_times.txt` en el 88,4 % de las lecturas (mediana del desvío, 0 s).
- **El `stop_id` de `vehicle_positions` no sigue la especificación.** Con `INCOMING_AT` o `IN_TRANSIT_TO` debería ser la próxima parada, pero es la actual o la última: la parada de `trip_updates` va una por delante en el 98,8 % de las lecturas (31.348 de 31.722).
- **Retrasos altos.** El último retraso conocido de cada viaje, en esa media hora: 0 min el 34 %, 1-5 min el 19 %, 6-10 min el 14 %, 11-30 min el 22 % y más de 30 min el 7 %. Es una franja de un solo día y con obras en varias líneas: **no es todavía una estadística publicable**.

### Lo que dicen las posiciones

- **Pegadas a las estaciones.** El 78 % de las posiciones está a ≤ 50 m de una estación (mediana, 24 m). Con `STOPPED_AT` o `INCOMING_AT`, el 89 %. Solo con `IN_TRANSIT_TO` (el 19 % de las observaciones) hay posiciones intermedias de verdad (mediana, 606 m de la estación más cercana).
- **Pocas novedades.** Solo el **21,8 %** de las observaciones cambia algo (coordenadas, parada o estado) respecto a la anterior del mismo tren.
- **Trenes esperando en origen.** El **38,5 %** de las observaciones son trenes que aún no han salido: en el 99,3 % de los casos el `stop_id` es la estación de origen, y salen una mediana de **25 min** después.
- **Sin coordenadas** viene un 0,5 % de las observaciones (161), casi todas de Sevilla (124). Las que se han mirado a mano traen el `stop_id` «00000».

## 5. Avisos

- **70 avisos distintos** (69 en la última muestra). Durante la media hora aparecieron 5 y desapareció 1.
- Solo traen **texto libre en español** (`description_text`). No traen `header_text`, `url`, `cause`, `effect` ni fecha de fin, y el aviso más antiguo empezó hace 528 días.
- **Afectan a:** líneas (`route_id`, 36 avisos) o estaciones (`stop_id`, 34, sobre todo ascensores y accesos). Algunos llevan etiquetas tipo `#MadC5`.
- **Rodalies tiene 5:** obras en Montcada, Castelldefels (R2S) y La Garriga (R3), y dos de las regionales de Lleida, Manresa y La Plana-Picamoixons, que afectan a la **RL4**.
- **Consecuencia:** no se pueden clasificar ni filtrar por gravedad. Se enseñan tal cual, colgados de la línea o la estación, y se consideran activos mientras estén en el feed.

## 6. Volumen y retención (§3 del plan)

| Medida | Valor |
|---|---:|
| Trenes por muestra en `vehicle_positions` (10:00-10:30) | 354 de media, máx. 367 |
| Tamaño de cada descarga: posiciones · retrasos · avisos | 29 · 25 · 31 KB |
| Descarga diaria de los tres feeds cada 20 s | ~360 MB |
| Horas-tren programadas al día (sin autobuses) | 4.445 |
| **Filas de posiciones al día** (1 por tren cada 20 s) | **~0,8-1,2 M** |
| Filas de posiciones en 48 h | ~1,6-2,4 M |
| Filas de posiciones al día **guardando solo los cambios** | **~0,26 M** |
| **Filas de retraso final por parada al día** | **~51.000** (máx. 65.671) |
| Filas de retraso al año | ~19 M |

**Cómo se calculan:**

- **Posiciones:** el horario da 4.445 horas-tren al día, que a una fila cada 20 s son 0,8 M. Ese es el mínimo. El máximo, 1,2 M, añade lo que se observó en la captura: el feed trae 1,51 entidades por viaje en marcha, porque incluye trenes esperando en origen. Es una extrapolación de media hora de la mañana: no hay medida de la noche ni de la hora punta.
- **Retrasos:** paradas programadas del día × 78,1 % de viajes que salen en el directo.

**Conclusión para la retención:** las ~1 M filas al día del plan **se confirman**, y 48 h en particiones diarias es razonable. Hay dos ajustes baratos:

- guardar una fila solo cuando cambia algo divide las posiciones por ~4,6;
- el histórico de retrasos (~19 M filas al año) cabe sin problema, pero conviene particionarlo por mes desde el principio.

## 7. Lo que cambiaría del plan

1. **§3, movimiento suave.** No interpolar entre las coordenadas del feed, que saltan de estación en estación. La posición en el mapa se **estima**: con la última parada y la siguiente, sus horas programadas más el retraso, sobre `shapes.txt`. El feed corrige cuando llega. Como los recorridos van por línea y no por variante, hay que proyectar las paradas sobre el recorrido en la importación (fase 2).
2. **§2, mapa y estadísticas.**
   - **Trenes en origen:** se pintan aparte, por ejemplo «sale a las 10:25», y no cuentan como «en marcha».
   - **Puntualidad:** solo con los trenes vistos en directo. Un viaje del horario que no sale en el directo **no** se cuenta como cancelado (solo los `CANCELED` explícitos).
3. **D3, alcance.** Se mantiene «todos los núcleos», pero con un **estado por núcleo: «sin datos en directo»** (hoy, Bilbao y Cádiz) y la cobertura visible (Sevilla, 31 %). El texto público no debe prometer «todos los trenes de España en directo».
4. **Fase 2, importación.** Los autobuses de sustitución (`route_type` 3) se importan, porque sirven en la ficha de la estación y para los avisos, pero se excluyen del directo y de la puntualidad. Hay que recortar los campos rellenos de espacios.
5. **Fase 3, ingesta.**
   - **Peticiones condicionales** (`ETag`) cada ~5-10 s en vez de descargar a ciegas cada 20 s.
   - **Tolerar muestras incompletas:** un tren no desaparece hasta faltar en 2-3 muestras seguidas.
   - **Retraso final por parada:** es el último retraso conocido cuando la parada de `trip_updates` pasa a la siguiente.
   - **`stop_id` de las posiciones:** tratarlo como parada actual o última, no como la próxima.
   - **Captura larga:** grabar 24 h seguidas antes de dar por buenos los volúmenes y el comportamiento nocturno.
6. **Fase 6, estadísticas.**
   - **Resolución:** el retraso viene en minutos. Se publican medias y porcentajes («% con menos de 3 min»), no segundos.
   - **Prudencia:** las cifras de hoy (29 % de los trenes con más de 10 min) son de una media hora con obras. No se publica nada hasta tener semanas de datos.
7. **§1, problema conocido.** El riesgo «huecos del horario en Cataluña» baja: hoy no hay huecos en Rodalies. En su lugar entra un riesgo nuevo: **núcleos que no emiten en directo**.

## 8. Cómo se hizo y límites

- **JDK:** Eclipse Temurin **25.0.5+8 LTS**, instalado con `winget install EclipseAdoptium.Temurin.25.JDK` (oficial y gratuito). Docker no estaba arrancado, así que no se usó la imagen `eclipse-temurin:25`. El Java 8 del sistema no se ha tocado.
- **Maven:** Maven Wrapper 3.3.4 (solo script), con Maven 3.9.16. `gtfs-realtime-bindings` 0.2.0 trae `protobuf-java` 4.34.0, que en Java 25 avisa por el uso de `sun.misc.Unsafe`. Funciona, pero hay que vigilarlo.
- **Captura:** la de 30 minutos se hizo con un bucle de `curl` (lanzado mientras se instalaba el JDK, para no perder tiempo). `Captura.java` hace lo mismo y se probó 1 minuto (3 muestras correctas). `Analisis.java` lee los dos formatos igual. Los `.pb` (8,2 MB) quedan en `spike/datos/`, fuera del repo.
- **Revisión independiente:** un subagente sin contexto revisó el código. No encontró nada grave y sí 6 puntos importantes, todos corregidos antes de las cifras finales:
  - el intervalo de 20 s medía el muestreo y no el feed (se añadió la consulta cada 2 s);
  - llegadas falsas en la comparación retraso-posición;
  - el 0 contaba como múltiplo de 60;
  - cancelados en el denominador;
  - el factor de volumen estaba mal rotulado;
  - horarios sin ordenar por `stop_sequence`.
- **Límites:**
  - **Una sola franja:** un viernes de 10:00 a 10:30, sin hora punta, noche ni fin de semana.
  - **Un solo día de servicio:** una captura que cruce la medianoche necesitaría también el día anterior.
  - **Sin explicar:** por qué no emiten Bilbao y Cádiz. Se ha medido, no explicado.
