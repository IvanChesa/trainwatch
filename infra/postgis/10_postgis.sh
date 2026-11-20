#!/bin/sh
# Sustituye al script de arranque de la imagen postgis/postgis, que crea postgis, postgis_topology,
# fuzzystrmatch y postgis_tiger_geocoder en la base de datos inicial. Aquí no hace nada: PostGIS lo activa
# la primera migración de Flyway, igual que en las pruebas y que en un PostgreSQL que no sea esta imagen.
