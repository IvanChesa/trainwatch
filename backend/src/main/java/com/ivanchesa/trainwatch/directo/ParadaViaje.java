package com.ivanchesa.trainwatch.directo;

/**
 * Una parada de un viaje del horario.
 *
 * @param secuencia {@code stop_sequence}
 * @param llegadaS  segundos desde la medianoche del día de servicio (pueden pasar de 86.400)
 * @param salidaS   igual que {@code llegadaS}
 * @param avance    fracción del recorrido en el sentido del viaje (0..1), ya girada si el viaje va al revés del
 *                  dibujo ({@code 1 - posicion}); {@code null} si la parada no está proyectada
 */
public record ParadaViaje(int secuencia, String stopId, String nombre, double lat, double lon, int llegadaS,
        int salidaS, Double avance) {
}
