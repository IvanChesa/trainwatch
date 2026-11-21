-- Fase 6 · Estadísticas de puntualidad: agregados por día de servicio que se guardan para siempre (ADR 0022 y 0023).
--
-- Los calcula cada noche EstadisticasCalculo, antes de que cambie el horario (04:30) y de que se borre la partición de
-- posiciones de ese día (48-72 h). Recalcular un día lo sustituye entero en una transacción.
--
-- Solo sumas y recuentos, nunca medias: sumar días y semanas da lo mismo que calcular sobre las filas originales.
-- Entran las paradas VISTAS (retrasos.inferido = false) con hora programada (llegada_programada no nula), de trenes
-- (sin autobuses). La hora es la programada en hora de Madrid; el día, el de servicio (fecha_servicio).
--
-- Los tramos de minutos son los colores del mapa: menos de 3 (incluye los adelantos), de 3 a 5, de 6 a 15 y 16 o más.
-- histograma: 61 posiciones (en SQL, de la 1 a la 61): la posición i+1 cuenta las paradas con i minutos; la primera
-- también las de 0 o menos y la última, las de 60 o más. De ahí salen la mediana y el percentil 90.

-- Qué días están calculados, cuándo y con qué fuentes. con_horario: el horario cubría ese día cuando se calculó (si no,
-- se conservaron los viajes programados de antes). con_posiciones: lo mismo con la partición de posiciones (cancelados).
CREATE TABLE estad_dias (
    fecha_servicio DATE         PRIMARY KEY,
    calculado      TIMESTAMPTZ  NOT NULL,
    filas_retrasos INTEGER      NOT NULL,
    con_horario    BOOLEAN      NOT NULL,
    con_posiciones BOOLEAN      NOT NULL
);

-- Por día, núcleo, línea y hora programada (0-23, hora de Madrid).
CREATE TABLE estad_linea_hora (
    fecha_servicio     DATE      NOT NULL,
    nucleo             SMALLINT  NOT NULL,
    linea              TEXT      NOT NULL,
    hora               SMALLINT  NOT NULL CHECK (hora BETWEEN 0 AND 23),
    viajes             INTEGER   NOT NULL,   -- viajes con alguna parada vista en esa hora
    paradas            INTEGER   NOT NULL,
    suma_minutos       INTEGER   NOT NULL,   -- sin recortar: la media es exacta
    menos_de_3         INTEGER   NOT NULL,
    de_3_a_5           INTEGER   NOT NULL,
    de_6_a_15          INTEGER   NOT NULL,
    de_16_o_mas        INTEGER   NOT NULL,
    histograma         INTEGER[] NOT NULL CHECK (cardinality(histograma) = 61),
    -- Llegada a la última parada vista de cada viaje (la cifra «por viaje»): cuenta en la hora de esa parada.
    finales            INTEGER   NOT NULL,
    suma_finales       INTEGER   NOT NULL,
    finales_menos_de_3 INTEGER   NOT NULL,
    PRIMARY KEY (fecha_servicio, nucleo, linea, hora),
    CHECK (menos_de_3 + de_3_a_5 + de_6_a_15 + de_16_o_mas = paradas)
);
CREATE INDEX estad_linea_hora_linea_idx ON estad_linea_hora (nucleo, linea, fecha_servicio);

-- Por día, núcleo y línea: lo que no se puede sumar por horas (viajes distintos) y lo que se quita o se cuenta aparte.
-- También hay fila para las líneas que el horario daba y no se vieron (viajes = 0): cuentan en la cobertura.
CREATE TABLE estad_linea_dia (
    fecha_servicio     DATE      NOT NULL,
    nucleo             SMALLINT  NOT NULL,
    linea              TEXT      NOT NULL,
    viajes             INTEGER   NOT NULL,   -- con alguna parada vista con hora: los de las cifras
    viajes_con_datos   INTEGER   NOT NULL,   -- con alguna fila en retrasos: los «vistos» de la cobertura
    paradas_inferidas  INTEGER   NOT NULL,
    paradas_sin_hora   INTEGER   NOT NULL,   -- vistas, pero sin llegada_programada (antes de la V5)
    viajes_programados INTEGER,              -- del horario de ese día; NULL si no se supo
    cancelados         INTEGER,              -- viajes con estado CANCELADO en posiciones; NULL si no se supo
    PRIMARY KEY (fecha_servicio, nucleo, linea)
);
CREATE INDEX estad_linea_dia_nucleo_idx ON estad_linea_dia (nucleo, fecha_servicio);

-- Por día, estación y línea: la puntualidad de la estación (ficha de la estación).
CREATE TABLE estad_estacion_dia (
    fecha_servicio    DATE      NOT NULL,
    stop_id           TEXT      NOT NULL,
    nucleo            SMALLINT  NOT NULL,
    linea             TEXT      NOT NULL,
    viajes            INTEGER   NOT NULL,
    paradas           INTEGER   NOT NULL,
    suma_minutos      INTEGER   NOT NULL,
    menos_de_3        INTEGER   NOT NULL,
    de_3_a_5          INTEGER   NOT NULL,
    de_6_a_15         INTEGER   NOT NULL,
    de_16_o_mas       INTEGER   NOT NULL,
    histograma        INTEGER[] NOT NULL CHECK (cardinality(histograma) = 61),
    paradas_inferidas INTEGER   NOT NULL,
    PRIMARY KEY (fecha_servicio, stop_id, nucleo, linea),
    CHECK (menos_de_3 + de_3_a_5 + de_6_a_15 + de_16_o_mas = paradas)
);
CREATE INDEX estad_estacion_dia_stop_idx ON estad_estacion_dia (stop_id, fecha_servicio);

-- Tramos: minutos que se ganan o se pierden entre dos paradas seguidas del mismo viaje (las dos vistas), por línea y
-- sentido. diferencia = retraso al llegar a la segunda - retraso al llegar a la primera.
CREATE TABLE estad_tramo_dia (
    fecha_servicio   DATE      NOT NULL,
    nucleo           SMALLINT  NOT NULL,
    linea            TEXT      NOT NULL,
    desde_stop_id    TEXT      NOT NULL,
    hasta_stop_id    TEXT      NOT NULL,
    pasos            INTEGER   NOT NULL,
    suma_diferencia  INTEGER   NOT NULL,
    pierden_2_o_mas  INTEGER   NOT NULL,
    ganan_2_o_mas    INTEGER   NOT NULL,
    PRIMARY KEY (fecha_servicio, nucleo, linea, desde_stop_id, hasta_stop_id)
);
CREATE INDEX estad_tramo_dia_linea_idx ON estad_tramo_dia (nucleo, linea, fecha_servicio);
