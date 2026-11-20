package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una fila de {@code retrasos}: el retraso con que un tren dejó atrás una parada.
 *
 * @param inferido {@code true} si la parada no se vio como próxima en ninguna muestra (el tren se la saltó entre dos
 *                 muestras o volvió tras desaparecer) y se cierra con el último retraso conocido (ADR 0012). Las
 *                 estadísticas pueden filtrarlas.
 * @param llegadaProgramada hora programada de llegada a la parada según el horario del viaje y su
 *                 {@code fechaServicio} (fase 4, ADR 0014): la necesitan las estadísticas por franja horaria, porque el
 *                 horario de ese día se sustituye por la noche y {@code registrado} es cuándo se cerró, no cuándo tocaba
 *                 pasar. {@code null} si no se sabe (las filas anteriores a la V5).
 */
public record RetrasoFinal(LocalDate fechaServicio, String tripId, int stopSequence, String stopId, String routeId,
        String linea, Integer nucleo, int minutos, Instant registrado, boolean inferido, Instant llegadaProgramada) {
}
