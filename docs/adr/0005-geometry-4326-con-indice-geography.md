# ADR 0005 · `geometry(…, 4326)` para estaciones y recorridos, con índice sobre `geography`

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 2

## Contexto

El horario trae dos cosas espaciales: las estaciones (`stops`, 1.167 puntos) y los recorridos (`shapes`, 144 líneas). PostGIS ofrece dos tipos:

- **`geometry`**: coordenadas en un plano. Con SRID 4326 (longitud y latitud en grados) las distancias salen en grados, que no son metros, pero tiene **todas** las funciones de PostGIS.
- **`geography`**: coordenadas sobre el elipsoide. Las distancias salen en metros y bien calculadas, pero solo tiene una parte de las funciones.

Lo que se va a hacer con estos datos:

1. **Proyectar paradas sobre recorridos** (fase 2) y **colocar trenes sobre el recorrido** (fase 3): `ST_LineLocatePoint`, `ST_LineInterpolatePoint`, `ST_LineSubstring`. Son **solo de `geometry`**.
2. **Estaciones cercanas a un punto**, en metros (fase 4): `ORDER BY … <-> punto LIMIT n` y `ST_DWithin`.
3. **Trenes dentro del mapa visible** (fase 4): caja de coordenadas, que encaja con `geometry`.

## Decisión

- `stops.ubicacion` es **`geometry(Point, 4326)`**, calculada por PostgreSQL (`GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(stop_lon, stop_lat), 4326))`): no se puede desincronizar de la latitud y la longitud.
- `shapes.recorrido` es **`geometry(LineString, 4326)`**.
- Para las búsquedas en metros, un **índice GiST sobre la expresión `(ubicacion::geography)`**. La consulta de las estaciones cercanas es `ORDER BY ubicacion::geography <-> punto::geography LIMIT 5`, y el plan usa el índice (*Index Scan using stops_ubicacion_geog_idx*, 0,1 ms con las 1.167 estaciones reales).
- Las distancias que se guardan (`parada_forma.distancia_m`, `shapes.longitud_m`) se calculan con `::geography`, en metros.

## Alternativas

1. **Todo `geography`.** Distancias en metros sin conversiones, pero `ST_LineLocatePoint` no existe para `geography`: habría que convertir a `geometry` en cada llamada de la proyección y del cálculo de posiciones, que es lo que más se va a usar.
2. **Las dos columnas** (`geometry` y `geography`). Duplica el dato para ahorrarse un *cast* que el índice de expresión ya resuelve.
3. **Proyectar a un sistema métrico** (ETRS89 / UTM 30N, EPSG:25830). Distancias en metros con `geometry`, pero España ocupa tres husos UTM (29, 30 y 31) y Canarias otro; Rodalies está en el 31. Un único huso deforma los extremos y complica todo para nada.
4. **`geometry` sin índice `geography`**, ordenando por grados. En Madrid un grado de longitud mide unos 85 km y uno de latitud 111 km: el orden por cercanía puede salir mal en empates cercanos.

## Consecuencias

- Las funciones lineales de PostGIS funcionan directamente sobre las columnas.
- Quien escriba una consulta en metros tiene que acordarse del `::geography` (con él, además, usa el índice). Está apuntado en la migración V2.
- Es coherente con la fase 1 (`geometry(Point, 4326)` en la tabla de prueba).
