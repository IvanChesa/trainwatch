package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Un viaje del horario, con lo que la ingesta necesita: línea, núcleo, paradas en orden y su recorrido. Inmutable.
 *
 * @param fechaServicio el día del calendario de Renfe si su {@code service_id} vale un solo día (lo normal: uno por
 *                      núcleo y día); {@code null} si vale varios, y entonces se deduce con
 *                      {@link #fechaServicio(Instant, ZoneId)}
 * @param recorrido     el recorrido si el viaje encaja en él ({@code forma_encaja}); si no, {@code null}
 * @param sentido       1 si va en el sentido del dibujo del recorrido, -1 si al revés
 */
public record Viaje(String tripId, String routeId, String linea, Integer nucleo, String destino,
        LocalDate fechaServicio, List<ParadaViaje> paradas, Recorrido recorrido, int sentido) {

    public Viaje {
        paradas = List.copyOf(paradas);
    }

    /** Índice de la parada con ese {@code stop_id} desde {@code desde} (las circulares repiten estación); -1 si no está. */
    public int indiceDe(String stopId, int desde) {
        if (stopId == null) {
            return -1;
        }
        for (int i = Math.max(0, desde); i < paradas.size(); i++) {
            if (paradas.get(i).stopId().equals(stopId)) {
                return i;
            }
        }
        return -1;
    }

    public boolean esUltima(int indice) {
        return indice == paradas.size() - 1;
    }

    /**
     * El día de servicio: el del calendario si vale un solo día. Si no, hoy o ayer: ayer solo si a esta hora el
     * viaje de ayer sigue en marcha (pasa de medianoche) y el de hoy aún no está cerca de salir.
     */
    public LocalDate fechaServicio(Instant ahora, ZoneId zona) {
        if (fechaServicio != null) {
            return fechaServicio;
        }
        LocalDate hoy = LocalDate.ofInstant(ahora, zona);
        if (paradas.isEmpty()) {
            return hoy;
        }
        long salida = paradas.getFirst().salidaS();
        long llegada = paradas.getLast().llegadaS();
        LocalDate ayer = hoy.minusDays(1);
        long segundosAyer = ahora.getEpochSecond() - origenDelDia(ayer, zona).getEpochSecond();
        long segundosHoy = ahora.getEpochSecond() - origenDelDia(hoy, zona).getEpochSecond();
        boolean enMarchaDeAyer = segundosAyer >= salida - 3600 && segundosAyer <= llegada + 3600;
        return enMarchaDeAyer && segundosHoy < salida - 3600 ? ayer : hoy;
    }

    /**
     * El cero de las horas de un dia de servicio en GTFS: mediodia menos 12 h, no la medianoche. Los dos dias de
     * cambio de hora no es lo mismo.
     */
    public static Instant origenDelDia(LocalDate fecha, ZoneId zona) {
        return fecha.atTime(12, 0).atZone(zona).toInstant().minusSeconds(12 * 3600);
    }

    /** Instante de una hora del horario (segundos desde la medianoche del día de servicio). */
    public static Instant instante(LocalDate fecha, int segundos, ZoneId zona) {
        return origenDelDia(fecha, zona).plusSeconds(segundos);
    }
}
