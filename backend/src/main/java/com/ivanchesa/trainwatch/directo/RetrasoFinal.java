package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una fila de {@code retrasos}: el retraso con que un tren dejó atrás una parada.
 *
 * @param inferido {@code true} si la parada no se vio como próxima en ninguna muestra (el tren se la saltó entre dos
 *                 muestras o volvió tras desaparecer) y se cierra con el último retraso conocido (ADR 0012). Las
 *                 estadísticas pueden filtrarlas.
 */
public record RetrasoFinal(LocalDate fechaServicio, String tripId, int stopSequence, String stopId, String routeId,
        String linea, Integer nucleo, int minutos, Instant registrado, boolean inferido) {
}
