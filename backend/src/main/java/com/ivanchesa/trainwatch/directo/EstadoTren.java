package com.ivanchesa.trainwatch.directo;

/**
 * Estado propio de un tren en directo (no es el {@code current_status} del feed).
 * <ul>
 * <li>{@code EN_ORIGEN}: en su estación de origen y antes de su salida (con el retraso). El 38,5 % del feed
 * (fase 0): se pinta aparte y no cuenta como en marcha.</li>
 * <li>{@code EN_MARCHA}: ha salido y no ha llegado al final. Un tren sin viaje en el horario cuenta aquí.</li>
 * <li>{@code TERMINADO}: está en la última parada de su viaje.</li>
 * <li>{@code CANCELADO}: solo si {@code trip_updates} lo marca {@code CANCELED}. Un tren que no aparece no es un
 * cancelado (cambio 3 tras la fase 0).</li>
 * </ul>
 */
public enum EstadoTren {
    EN_ORIGEN, EN_MARCHA, TERMINADO, CANCELADO
}
