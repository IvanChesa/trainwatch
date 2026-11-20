-- Fase 1 · Cimientos: activa PostGIS y crea una tabla de prueba para comprobar que la geometría funciona.
-- La tabla de prueba se borrará cuando llegue el modelo de verdad (fase 2), en una migración nueva.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE prueba_cimientos (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre    TEXT                   NOT NULL,
    posicion  geometry(Point, 4326)  NOT NULL,
    creado_en TIMESTAMPTZ            NOT NULL DEFAULT now()
);

-- Una fila conocida para las pruebas: la estación de Lleida Pirineus (RL4), en WGS 84 (longitud, latitud).
INSERT INTO prueba_cimientos (nombre, posicion)
VALUES ('Lleida Pirineus', ST_SetSRID(ST_MakePoint(0.6335, 41.6206), 4326));
