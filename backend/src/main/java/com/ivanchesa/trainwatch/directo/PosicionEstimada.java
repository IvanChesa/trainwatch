package com.ivanchesa.trainwatch.directo;

import java.time.Instant;

/**
 * Dónde se cree que está un tren en un instante (ADR 0010).
 *
 * @param fraccion fracción del recorrido (0..1, en el sentido del dibujo, como {@code parada_forma.posicion});
 *                 {@code null} si no hay recorrido
 * @param rumbo    grados desde el norte (0..360) hacia donde va; {@code null} si no se sabe
 * @param fuente   {@code RECORRIDO} si sale del horario, el retraso y el recorrido; {@code FEED} si son las
 *                 coordenadas del feed tal cual
 */
public record PosicionEstimada(double lat, double lon, Double fraccion, Double rumbo, Fuente fuente,
        Instant instante) {

    public enum Fuente {
        RECORRIDO, FEED
    }
}
