# Guía de defensa · fase 0 (prueba técnica)

Lo que tienes que saber explicar de esta fase. Las cifras están en [`docs/informes/fase-0.md`](../informes/fase-0.md).

## 1. Por qué una prueba técnica antes de programar

Porque todo el proyecto depende de unos datos que no controlas. Antes de invertir 6-8 semanas había que comprobar con datos reales tres cosas: que los feeds se pueden leer en Java, que los trenes en directo se pueden cruzar con el horario (si no, no hay origen, destino ni estadística por línea) y cuánto volumen generan (para dimensionar la base de datos y el servidor).

**Pregunta típica:** «¿Y si los datos hubieran salido mal?». El plan tenía un umbral: si en Rodalies faltaba más del 30 % de los viajes en el horario, se replanteaba cómo enseñarlos. Medirlo primero es más barato que descubrirlo en la fase 5.

## 2. GTFS y GTFS Realtime

- **GTFS estático:** un ZIP de CSV con el horario planificado. Las tablas clave: `routes` (líneas), `trips` (cada circulación, con su `service_id` y su `shape_id`), `stop_times` (hora de cada viaje en cada parada), `stops`, `calendar` (qué días circula cada `service_id`) y `shapes` (el dibujo del recorrido).
- **GTFS Realtime:** tres feeds en **Protocol Buffers** (binario, no JSON), que se descargan cada pocos segundos:
  - `vehicle_positions`: dónde está cada tren y en qué parada (`current_status`: `STOPPED_AT`, `INCOMING_AT`, `IN_TRANSIT_TO`);
  - `trip_updates`: retraso previsto por parada;
  - `alerts`: avisos en texto.
- El enlace entre los dos mundos es el **`trip_id`**: si el `trip_id` del directo está en `trips.txt`, sabes la línea, las paradas y las horas programadas.

**Pregunta típica:** «¿Por qué protobuf y no JSON?». Pesa mucho menos: las posiciones ocupan 29 KB en protobuf y 131 KB en el JSON que Renfe publica en paralelo, unas 4,5 veces más. Además, se decodifica más rápido y tiene esquema: la librería oficial (`gtfs-realtime-bindings` de MobilityData) genera las clases Java a partir del `.proto`, así que no hay que escribir el parser.

## 3. Cómo se midió

- **Captura:** los tres feeds cada 20 s durante 30 min, guardados en crudo (`.pb`). Guardar el crudo permite repetir el análisis sin volver a descargar y sirve después como datos de prueba («feeds grabados» del plan).
- **Cruce:** cada `trip_id` distinto del directo se busca en `trips.txt`; se cuenta por núcleo (las dos primeras cifras del `trip_id`, p. ej. `51` = Rodalies) y por línea.
- **Al revés:** de los viajes que el horario da en marcha en cada momento, cuántos salen en el directo. Así se ven los núcleos que no emiten.
- **Coherencia del retraso:** cuando un tren aparece detenido en una parada, su retraso real es la hora de la marca menos la llegada programada; se compara con el que dice `trip_updates`.
- **Volumen:** horas-tren del día según el horario × una fila cada 20 s, y paradas programadas del día para el histórico de retrasos.

**Detalle que conviene saber contar:** el `stop_times.txt` pesa 258 MB. No se carga entero en memoria: se lee en streaming desde el ZIP y solo se guarda el detalle de los viajes que interesan. La carga tarda unos 2 s.

## 4. Lo que salió y qué cambia

Lo importante (las cifras exactas, en el informe):

- Los trenes en directo **sí** encuentran su viaje en el horario, también en Rodalies y en la RL4. Los «huecos en Cataluña» que avisaban en datos.gob.es no se reproducen hoy.
- Los huecos están en otro sitio: hay núcleos que no emiten en directo (Bilbao y Cádiz durante la captura) y el horario incluye autobuses de sustitución por obras (`route_type` 3), que nunca salen en el directo.
- Las posiciones van casi siempre **pegadas a las estaciones** y el retraso viene **en minutos enteros**, solo para la próxima parada. Por eso el movimiento suave del mapa tiene que calcularse con el horario más el retraso sobre el recorrido (`shapes.txt`), no interpolando las coordenadas del feed.
- El directo incluye trenes que **esperan en origen** antes de salir (una mediana de 25 min antes). Hay que distinguirlos en el mapa y en las estadísticas.
- `stop_id` de `vehicle_positions` no sigue la especificación: indica la parada actual o la última, y `trip_updates` da la siguiente.

**Pregunta típica:** «¿Cómo calculas la puntualidad si el feed solo da el retraso de la próxima parada?». Se guarda el último retraso conocido para cada parada justo antes de que el tren pase a la siguiente: ese es el «retraso final en esa parada». Con muestras cada 20 s se pierde poco, pero el dato tiene la resolución de minutos que da Renfe.

## 5. Herramientas

- **JDK 25 (Eclipse Temurin)** instalado con `winget`, y **Maven Wrapper** (`mvnw`): el proyecto trae el script que descarga la versión exacta de Maven, así cualquiera lo compila sin instalar Maven y todos usan la misma versión.
- Sin Spring a propósito: la prueba solo tenía que leer y medir; Spring entra en la fase 1.
