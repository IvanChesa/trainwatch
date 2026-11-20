package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;

/** Una fila de {@code retrasos}: el retraso con que un tren dejó atrás una parada. */
public record RetrasoFinal(LocalDate fechaServicio, String tripId, int stopSequence, String stopId, String routeId,
        String linea, Integer nucleo, int minutos, Instant registrado) {
}
