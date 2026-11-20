-- Fase 2 · GTFS estático: el horario de Renfe Cercanías y Rodalies (fomento_transit.zip).
--
-- Todas las tablas del horario viven en el esquema "horario". La importación nocturna NO las modifica: crea
-- "horario_carga" con la misma estructura (CREATE TABLE ... LIKE horario.x), lo llena y, si todo va bien,
-- cambia los nombres de los dos esquemas en una transacción (ADR 0006). Por eso:
--   * este fichero es la única definición de las tablas y de sus índices: la importación los copia de aquí;
--   * no hay claves foráneas que entren o salgan de "horario" (se romperían con el cambio de esquema): las
--     demás tablas guardan los identificadores del GTFS (trip_id, stop_id) como texto.
-- Nombres: los campos del GTFS conservan su nombre original (stop_id, route_type...); los que añadimos
-- nosotros van en español (nucleo, es_autobus, ubicacion...).

-- La tabla de prueba de la fase 1 ya no hace falta.
DROP TABLE prueba_cimientos;

CREATE SCHEMA horario;

-- Estaciones. ubicacion es geometry en WGS 84 (ADR 0005): se calcula sola a partir de la latitud y la longitud.
CREATE TABLE horario.stops (
    stop_id             TEXT              PRIMARY KEY,
    stop_name           TEXT              NOT NULL,
    stop_lat            DOUBLE PRECISION  NOT NULL,
    stop_lon            DOUBLE PRECISION  NOT NULL,
    wheelchair_boarding SMALLINT,
    ubicacion           geometry(Point, 4326) GENERATED ALWAYS AS
                            (ST_SetSRID(ST_MakePoint(stop_lon, stop_lat), 4326)) STORED
);
-- Estaciones cercanas a un punto, en metros: ORDER BY ubicacion::geography <-> punto (KNN) o ST_DWithin.
CREATE INDEX stops_ubicacion_geog_idx ON horario.stops USING gist ((ubicacion::geography));

-- Líneas. El núcleo son las cifras iniciales del route_id (10 Madrid, 51 Rodalies...).
-- route_type 3 = autobús de sustitución: se importa, pero queda fuera del directo y de la puntualidad.
CREATE TABLE horario.routes (
    route_id         TEXT      PRIMARY KEY,
    route_short_name TEXT      NOT NULL,
    route_long_name  TEXT,
    route_type       SMALLINT  NOT NULL,
    route_color      TEXT,
    route_text_color TEXT,
    nucleo           SMALLINT,
    es_autobus       BOOLEAN   GENERATED ALWAYS AS (route_type = 3) STORED
);
CREATE INDEX routes_nucleo_idx ON horario.routes (nucleo, route_short_name);

-- Calendario: un service_id por núcleo y día. Renfe no publica calendar_dates.txt.
CREATE TABLE horario.calendar (
    service_id TEXT     PRIMARY KEY,
    monday     BOOLEAN  NOT NULL,
    tuesday    BOOLEAN  NOT NULL,
    wednesday  BOOLEAN  NOT NULL,
    thursday   BOOLEAN  NOT NULL,
    friday     BOOLEAN  NOT NULL,
    saturday   BOOLEAN  NOT NULL,
    sunday     BOOLEAN  NOT NULL,
    start_date DATE     NOT NULL,
    end_date   DATE     NOT NULL
);

-- Viajes. es_autobus se copia de su línea; las columnas forma_* las calcula la importación al proyectar las
-- paradas sobre el recorrido:
--   forma_sentido          1 si las paradas avanzan en el sentido en que está dibujado el recorrido,
--                         -1 si van al revés, NULL si no avanzan siempre en el mismo sentido o si el viaje
--                         tiene menos de dos paradas;
--   forma_distancia_max_m  distancia de la parada más alejada del recorrido;
--   forma_encaja           sentido conocido, todas las paradas a 200 m o menos y todas proyectadas.
-- En un recorrido cerrado, el avance entre dos paradas se cuenta módulo 1 (de 0,9 a 0,1 es avanzar 0,2).
CREATE TABLE horario.trips (
    trip_id               TEXT      PRIMARY KEY,
    route_id              TEXT      NOT NULL,
    service_id            TEXT      NOT NULL,
    trip_headsign         TEXT,
    wheelchair_accessible SMALLINT,
    block_id              TEXT,
    shape_id              TEXT,
    es_autobus            BOOLEAN   NOT NULL DEFAULT false,
    forma_sentido         SMALLINT  CHECK (forma_sentido IN (-1, 1)),
    forma_distancia_max_m REAL,
    forma_encaja          BOOLEAN   NOT NULL DEFAULT false
);
CREATE INDEX trips_route_idx ON horario.trips (route_id);
CREATE INDEX trips_service_idx ON horario.trips (service_id);

-- Horas de paso. llegada_s y salida_s son segundos desde la medianoche del día de servicio: pueden pasar de
-- 86.400 (un tren que sale a las 23:50 llega a las 24:10 = 87.000 s).
CREATE TABLE horario.stop_times (
    trip_id       TEXT     NOT NULL,
    stop_sequence INTEGER  NOT NULL,
    stop_id       TEXT     NOT NULL,
    llegada_s     INTEGER  NOT NULL,
    salida_s      INTEGER  NOT NULL,
    -- Las paradas de un viaje en orden: WHERE trip_id = ? ORDER BY stop_sequence.
    PRIMARY KEY (trip_id, stop_sequence)
);
-- Próximas salidas de una estación: WHERE stop_id = ? AND salida_s BETWEEN ? AND ? (y luego el calendario).
CREATE INDEX stop_times_salidas_idx ON horario.stop_times (stop_id, salida_s) INCLUDE (trip_id);

-- Recorridos: un LineString por shape_id (van por línea y núcleo, como 10_C1 o 51_R4, a veces con su
-- inverso, 40_C6_INV; no por variante). cerrado: empieza y acaba en el mismo sitio (a menos de 100 m), como la
-- C4 circular de Sevilla; ahí la posición sobre el recorrido da la vuelta de 1 a 0.
CREATE TABLE horario.shapes (
    shape_id   TEXT                       PRIMARY KEY,
    recorrido  geometry(LineString, 4326) NOT NULL,
    puntos     INTEGER                    NOT NULL,
    longitud_m DOUBLE PRECISION           NOT NULL,
    cerrado    BOOLEAN                    NOT NULL
);
CREATE INDEX shapes_recorrido_idx ON horario.shapes USING gist (recorrido);

-- Cada parada proyectada sobre cada recorrido que la usa: posicion es la fracción 0..1 del recorrido
-- (ST_LineLocatePoint) y distancia_m, lo que la estación queda del recorrido. Para la parada de un viaje:
-- JOIN por (trips.shape_id, stop_times.stop_id); el avance del viaje es posicion si forma_sentido = 1 y
-- 1 - posicion si es -1.
CREATE TABLE horario.parada_forma (
    shape_id    TEXT              NOT NULL,
    stop_id     TEXT              NOT NULL,
    posicion    DOUBLE PRECISION  NOT NULL CHECK (posicion BETWEEN 0 AND 1),
    distancia_m REAL              NOT NULL,
    PRIMARY KEY (shape_id, stop_id)
);

-- Transbordos (19 filas): se importan tal cual, sin clave.
CREATE TABLE horario.transfers (
    from_stop_id      TEXT     NOT NULL,
    to_stop_id        TEXT     NOT NULL,
    from_route_id     TEXT,
    to_route_id       TEXT,
    from_trip_id      TEXT,
    to_trip_id        TEXT,
    transfer_type     SMALLINT NOT NULL,
    min_transfer_time INTEGER
);

-- Registro de importaciones (fuera de "horario": sobrevive a los cambios de esquema). De aquí salen el ETag y
-- el Last-Modified de la última importación correcta para la descarga condicional, y GET /api/horario/estado.
CREATE TABLE importacion_horario (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    motivo         TEXT         NOT NULL,   -- NOCTURNA, MANUAL o ARRANQUE
    inicio         TIMESTAMPTZ  NOT NULL,
    fin            TIMESTAMPTZ,
    resultado      TEXT         NOT NULL,   -- EN_CURSO, CORRECTA, SIN_CAMBIOS o FALLIDA
    etag           TEXT,
    last_modified  TEXT,                    -- tal cual llega en la cabecera HTTP
    vigencia_desde DATE,
    vigencia_hasta DATE,
    filas          JSONB,
    error          TEXT
);
CREATE INDEX importacion_horario_resultado_idx ON importacion_horario (resultado, inicio DESC);
