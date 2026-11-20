package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Un tren en directo: lo último que dijo el feed, cruzado con su viaje del horario. Es <b>inmutable</b>: cada muestra
 * crea un {@code Tren} nuevo que sustituye al anterior en el mapa del estado. Así, quien lo lee desde otro hilo
 * (la API, las métricas) ve siempre un tren entero, nunca uno a medio actualizar.
 *
 * @param id            clave del mapa: el {@code trip_id} (o el vehículo, si el feed no trae viaje)
 * @param viaje         su viaje en el horario, o {@code null} si no está («sin horario»)
 * @param indiceParada  índice de {@code stopId} en las paradas del viaje; -1 si no se sabe
 * @param estadoFeed    {@code current_status} del feed, tal cual
 * @param lat           coordenadas del feed; {@code null} si no las trae
 * @param marca         marca de tiempo del tren en el feed
 * @param retrasoS      retraso en segundos para la próxima parada, de {@code trip_updates}
 * @param estimada      posición estimada en el último ciclo
 * @param ausencias     muestras nuevas y completas seguidas en que no ha aparecido
 */
public record Tren(String id, String tripId, String vehiculo, String linea, Integer nucleo, Viaje viaje,
        LocalDate fechaServicio, EstadoTren estado, String stopId, int indiceParada, String estadoFeed, Double lat,
        Double lon, Instant marca, Integer retrasoS, PosicionEstimada estimada, int ausencias) {

    public boolean sinHorario() {
        return viaje == null;
    }

    public Integer stopSequence() {
        return viaje != null && indiceParada >= 0 ? viaje.paradas().get(indiceParada).secuencia() : null;
    }

    /** Si algo de lo que se guarda en {@code posiciones} ha cambiado respecto a otro estado del mismo tren. */
    public boolean cambiaRespectoA(Tren anterior) {
        return anterior == null
                || estado != anterior.estado
                || !Objects.equals(stopId, anterior.stopId)
                || !Objects.equals(estadoFeed, anterior.estadoFeed)
                || !Objects.equals(lat, anterior.lat)
                || !Objects.equals(lon, anterior.lon)
                || !Objects.equals(retrasoS, anterior.retrasoS);
    }

    Tren conAusencias(int n) {
        return new Tren(id, tripId, vehiculo, linea, nucleo, viaje, fechaServicio, estado, stopId, indiceParada,
                estadoFeed, lat, lon, marca, retrasoS, estimada, n);
    }

    Tren conEstimada(PosicionEstimada p) {
        return new Tren(id, tripId, vehiculo, linea, nucleo, viaje, fechaServicio, estado, stopId, indiceParada,
                estadoFeed, lat, lon, marca, retrasoS, p, ausencias);
    }
}
