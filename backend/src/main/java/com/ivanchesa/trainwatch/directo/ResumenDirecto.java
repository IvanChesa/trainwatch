package com.ivanchesa.trainwatch.directo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lo que responde {@code GET /api/directo/estado}. Se calcula al final de cada ciclo de la ingesta y se publica
 * entero (inmutable): quien lo lee no ve nunca uno a medias. La antigüedad se vuelve a calcular al responder
 * ({@link #alInstante}).
 *
 * @param calculado     cuándo se calculó (el final del último ciclo de la ingesta)
 * @param ultimoDato    marca de la cabecera de la última muestra nueva de posiciones
 * @param antiguedadS   segundos desde {@code ultimoDato} hasta el momento de responder (fase 3b: antes, hasta
 *                      {@code calculado}, y una ingesta parada no se notaba)
 * @param trenes        trenes en el estado en memoria (sin los cancelados que solo están en trip_updates)
 * @param sinHorario    trenes cuyo viaje no está en el horario
 * @param circuitos     estado del circuit breaker de cada feed (CLOSED, OPEN, HALF_OPEN)
 */
public record ResumenDirecto(Instant calculado, Instant ultimoDato, Long antiguedadS, int trenes,
        Map<EstadoTren, Integer> porEstado, int sinHorario, List<Nucleo> nucleos, Map<String, String> circuitos) {

    /**
     * El directo de un núcleo.
     *
     * @param estado          {@code EN_DIRECTO}, {@code SIN_DATOS_EN_DIRECTO} (el horario da trenes en marcha y el
     *                        feed no trae ninguno: hoy, Bilbao y Cádiz) o {@code SIN_SERVICIO} (el horario no da
     *                        ninguno en marcha, por ejemplo de madrugada)
     * @param enDirecto       trenes del núcleo en el feed (sin cancelados), en cualquier estado
     * @param enMarchaHorario viajes que el horario da en marcha ahora (entre su primera salida y su última llegada)
     * @param enMarchaVistos  de esos, cuántos están en el feed
     * @param coberturaPct    {@code enMarchaVistos / enMarchaHorario}, en %; {@code null} si el horario no da ninguno
     */
    public record Nucleo(int nucleo, String nombre, String estado, int enDirecto, int enMarchaHorario,
            int enMarchaVistos, Double coberturaPct) {
    }

    /**
     * El mismo resumen con el último dato que hay ahora (el del estado en memoria, que puede ser más nuevo que el del
     * resumen si este no se pudo calcular) y su antigüedad contada hasta {@code ahora}.
     */
    public ResumenDirecto alInstante(Instant ahora, Instant ultimoAhora) {
        Instant ultimo = ultimoAhora != null ? ultimoAhora : ultimoDato;
        Long antiguedad = ultimo == null ? null : Duration.between(ultimo, ahora).toSeconds();
        return new ResumenDirecto(calculado, ultimo, antiguedad, trenes, porEstado, sinHorario, nucleos, circuitos);
    }

    public static ResumenDirecto vacio(Instant ahora) {
        return new ResumenDirecto(ahora, null, null, 0, Map.of(), 0, List.of(), Map.of());
    }
}
