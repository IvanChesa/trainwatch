-- Fase 3 · Ingesta en directo: lo que se guarda de los feeds GTFS Realtime de Renfe.
--
-- Las tres tablas guardan los identificadores del GTFS (trip_id, stop_id, route_id) como texto, sin claves
-- foráneas hacia "horario" (ADR 0006: el esquema se sustituye entero cada noche).
-- Las particiones NO se crean aquí: las crea y las borra GestorParticiones al arrancar y cada hora (ADR 0009).

-- Posiciones: una fila por tren SOLO cuando cambia algo (parada, coordenadas, estado del feed, estado propio o
-- retraso). Unas 0,26 M filas al día (fase 0). Se guardan 48 h: particiones diarias (medianoche de Madrid) que se
-- borran enteras con DROP TABLE, sin DELETE ni VACUUM.
CREATE TABLE posiciones (
    instante       TIMESTAMPTZ       NOT NULL,   -- marca del tren en el feed (o de la cabecera si no trae)
    trip_id        TEXT              NOT NULL,
    vehiculo       TEXT,
    fecha_servicio DATE,
    nucleo         SMALLINT,
    linea          TEXT,
    estado         TEXT              NOT NULL,   -- EN_ORIGEN, EN_MARCHA, TERMINADO o CANCELADO
    estado_feed    TEXT,                         -- STOPPED_AT, INCOMING_AT o IN_TRANSIT_TO, tal cual
    stop_id        TEXT,                         -- parada actual o la última (no la próxima: fase 0)
    stop_sequence  INTEGER,
    lat            DOUBLE PRECISION,             -- del feed; NULL si no trae coordenadas (0,5 %)
    lon            DOUBLE PRECISION,
    retraso_s      INTEGER,                      -- de trip_updates, para la próxima parada
    fraccion       DOUBLE PRECISION,             -- posición estimada sobre el recorrido (0..1), si lo hay
    lat_estimada   DOUBLE PRECISION,
    lon_estimada   DOUBLE PRECISION,
    rumbo          REAL,                         -- grados desde el norte; para la futura vista 3D
    ubicacion      geometry(Point, 4326) GENERATED ALWAYS AS
                       (CASE WHEN lat IS NULL OR lon IS NULL THEN NULL
                             ELSE ST_SetSRID(ST_MakePoint(lon, lat), 4326) END) STORED
) PARTITION BY RANGE (instante);
-- El recorrido de un tren en orden.
CREATE INDEX posiciones_viaje_idx ON posiciones (trip_id, instante);

-- Retraso final de cada tren en cada parada: el último retraso conocido cuando trip_updates pasa a la siguiente
-- parada. Es la base de las estadísticas: unas 51.000 filas al día y se guarda siempre, en particiones mensuales.
CREATE TABLE retrasos (
    fecha_servicio DATE         NOT NULL,
    trip_id        TEXT         NOT NULL,
    stop_sequence  INTEGER      NOT NULL,
    stop_id        TEXT         NOT NULL,
    route_id       TEXT,
    linea          TEXT,
    nucleo         SMALLINT,
    minutos        INTEGER      NOT NULL,      -- Renfe da el retraso en minutos enteros (fase 0)
    registrado     TIMESTAMPTZ  NOT NULL,      -- cuándo se cerró
    PRIMARY KEY (fecha_servicio, trip_id, stop_sequence)
) PARTITION BY RANGE (fecha_servicio);
CREATE INDEX retrasos_linea_idx ON retrasos (nucleo, linea, fecha_servicio);

-- Avisos: el estado actual. Cada muestra nueva de alerts.pb sustituye entera a la anterior (en una transacción).
-- Una fila por aviso y entidad afectada: colgado de una línea (route_id) o de una estación (stop_id).
CREATE TABLE avisos (
    aviso_id  TEXT         NOT NULL,
    route_id  TEXT,
    stop_id   TEXT,
    nucleo    SMALLINT,
    texto     TEXT         NOT NULL,
    inicio    TIMESTAMPTZ,
    fin       TIMESTAMPTZ,
    recibido  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX avisos_route_idx ON avisos (route_id);
CREATE INDEX avisos_stop_idx ON avisos (stop_id);
