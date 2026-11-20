package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

/**
 * El estado en memoria de la ingesta: qué trenes hay, dónde están y por qué parada va cada uno en
 * {@code trip_updates} (ADR 0008).
 *
 * <p><b>Concurrencia.</b> Hay un solo escritor, el hilo de la ingesta: los métodos que procesan muestras son
 * {@code synchronized}, así que aunque alguien los llamara desde otro hilo (una prueba, un disparo manual) nunca
 * irían dos a la vez. Los lectores (la API, las métricas) no se bloquean: leen el {@link ConcurrentHashMap} de
 * trenes, cuyos valores son {@link Tren} inmutables que se sustituyen enteros. Un lector puede ver unos trenes de
 * la muestra anterior y otros de la nueva mientras se procesa, pero nunca un tren a medias. El seguimiento de
 * {@code trip_updates} solo lo toca el escritor, dentro del {@code synchronized}, y por eso es un {@link HashMap}.
 *
 * <p><b>Muestras.</b> Solo cuenta una muestra <i>nueva</i>: con la marca de la cabecera posterior a la anterior. Una
 * muestra repetida no hace nada. Un tren desaparece tras faltar en {@code muestrasParaDesaparecer} muestras nuevas y
 * completas seguidas; las incompletas no suman ausencias ({@link DetectorMuestrasIncompletas}).
 *
 * <p><b>Retraso final por parada.</b> {@code trip_updates} trae el retraso de la <i>próxima</i> parada. Mientras la
 * próxima parada no cambia, el retraso se va sustituyendo; cuando pasa a una parada posterior, el último retraso
 * conocido es el retraso final de la parada que queda atrás, y se cierra (una fila de {@code retrasos}). La última
 * parada se cierra cuando el viaje desaparece de {@code trip_updates} estando en ella.
 */
public class EstadoDirecto {

    /** Lo que se sabe de un viaje por {@code trip_updates}. Solo lo usa el escritor. */
    private record SeguimientoViaje(String tripId, Viaje viaje, LocalDate fecha, String stopId, int indice,
            Integer retrasoS, boolean cancelado, int ausencias) {

        SeguimientoViaje conAusencias(int n) {
            return new SeguimientoViaje(tripId, viaje, fecha, stopId, indice, retrasoS, cancelado, n);
        }
    }

    /** Resultado de procesar {@code vehicle_positions}: los trenes nuevos o que han cambiado van a {@code posiciones}. */
    public record ResultadoPosiciones(boolean nueva, boolean completa, List<Tren> cambios, int desaparecidos) {
    }

    /**
     * Resultado de procesar {@code trip_updates}.
     *
     * @param sinCerrar paradas que se dejaron atrás pero no se pueden guardar: el viaje no está en el horario (no hay
     *                  {@code stop_sequence}) o la parada nueva no va después de la anterior
     */
    public record ResultadoRetrasos(boolean nueva, boolean completa, List<RetrasoFinal> cerrados, int sinCerrar) {
    }

    private static final String SIN_COORDENADAS = "00000";

    private final FuenteHorario horario;
    private final ZoneId zona;
    private final int muestrasParaDesaparecer;

    private final ConcurrentHashMap<String, Tren> trenes = new ConcurrentHashMap<>();
    /** Viajes cancelados en la última muestra de trip_updates (lectura desde otros hilos). */
    private volatile Set<String> cancelados = Set.of();
    private final Map<String, SeguimientoViaje> seguimiento = new HashMap<>();
    private final DetectorMuestrasIncompletas detectorPosiciones;
    private final DetectorMuestrasIncompletas detectorRetrasos;
    private long cabeceraPosiciones = -1;
    private long cabeceraRetrasos = -1;
    private volatile Instant ultimaMuestraPosiciones;
    private volatile Instant ultimaMuestraRetrasos;

    public EstadoDirecto(FuenteHorario horario, ZoneId zona, int muestrasParaDesaparecer, double fraccionIncompleta) {
        this.horario = horario;
        this.zona = zona;
        this.muestrasParaDesaparecer = muestrasParaDesaparecer;
        this.detectorPosiciones = new DetectorMuestrasIncompletas(fraccionIncompleta, muestrasParaDesaparecer);
        this.detectorRetrasos = new DetectorMuestrasIncompletas(fraccionIncompleta, muestrasParaDesaparecer);
    }

    // ---------------------------------------------------------------- lectura (cualquier hilo)

    /** Copia de los trenes en este momento. */
    public List<Tren> trenes() {
        return List.copyOf(trenes.values());
    }

    public Tren tren(String id) {
        return trenes.get(id);
    }

    /** Viajes que {@code trip_updates} marca como {@code CANCELED} en su última muestra. */
    public Set<String> cancelados() {
        return cancelados;
    }

    /** Marca de la cabecera de la última muestra nueva de posiciones; {@code null} si aún no ha llegado ninguna. */
    public Instant ultimaMuestraPosiciones() {
        return ultimaMuestraPosiciones;
    }

    public Instant ultimaMuestraRetrasos() {
        return ultimaMuestraRetrasos;
    }

    // ---------------------------------------------------------------- escritura (solo el hilo de la ingesta)

    public synchronized ResultadoRetrasos procesarRetrasos(FeedMessage muestra, Instant ahora) {
        long cabecera = muestra.getHeader().getTimestamp();
        if (cabecera <= cabeceraRetrasos) {
            return new ResultadoRetrasos(false, false, List.of(), 0);
        }
        cabeceraRetrasos = cabecera;
        ultimaMuestraRetrasos = Instant.ofEpochSecond(cabecera);
        boolean completa = detectorRetrasos.completa(muestra.getEntityCount());
        Set<String> vistos = new HashSet<>();
        Set<String> canceladosAhora = new HashSet<>();
        List<RetrasoFinal> cerrados = new ArrayList<>();
        int sinCerrar = 0;

        for (FeedEntity entidad : muestra.getEntityList()) {
            if (!entidad.hasTripUpdate()) {
                continue;
            }
            TripUpdate tu = entidad.getTripUpdate();
            String tripId = tu.getTrip().getTripId().trim();
            if (tripId.isEmpty() || !vistos.add(tripId)) {
                continue;
            }
            SeguimientoViaje previo = seguimiento.get(tripId);
            Viaje viaje = horario.viaje(tripId);
            LocalDate fecha = previo != null ? previo.fecha()
                    : viaje != null ? viaje.fechaServicio(ahora, zona) : LocalDate.ofInstant(ahora, zona);

            if (tu.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED) {
                // Lo pendiente de un cancelado se descarta: no ha pasado por esa parada.
                canceladosAhora.add(tripId);
                seguimiento.put(tripId, new SeguimientoViaje(tripId, viaje, fecha, null, -1, null, true, 0));
                continue;
            }
            if (tu.getStopTimeUpdateCount() == 0) {
                Integer retraso = tu.hasDelay() ? Integer.valueOf(tu.getDelay()) : previo == null ? null : previo.retrasoS();
                seguimiento.put(tripId, previo == null || previo.cancelado()
                        ? new SeguimientoViaje(tripId, viaje, fecha, null, -1, retraso, false, 0)
                        : new SeguimientoViaje(tripId, viaje, fecha, previo.stopId(), previo.indice(), retraso, false, 0));
                continue;
            }
            StopTimeUpdate proxima = tu.getStopTimeUpdate(0);
            Integer retraso = retrasoDe(tu, proxima);
            String stopId = proxima.getStopId().trim();
            int indice = indiceProxima(viaje, proxima, stopId, previo);

            if (previo != null && !previo.cancelado() && previo.stopId() != null && !previo.stopId().equals(stopId)) {
                if (previo.viaje() != null && previo.indice() >= 0 && indice > previo.indice() && previo.retrasoS() != null) {
                    cerrados.add(cierre(previo, ahora));
                } else {
                    sinCerrar++;
                }
            }
            seguimiento.put(tripId, new SeguimientoViaje(tripId, viaje, fecha, stopId.isEmpty() ? null : stopId,
                    indice, retraso, false, 0));
        }

        if (completa) {
            for (Iterator<Map.Entry<String, SeguimientoViaje>> it = seguimiento.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, SeguimientoViaje> entrada = it.next();
                SeguimientoViaje s = entrada.getValue();
                if (vistos.contains(s.tripId())) {
                    continue;
                }
                int ausencias = s.ausencias() + 1;
                if (ausencias < muestrasParaDesaparecer) {
                    entrada.setValue(s.conAusencias(ausencias));
                    continue;
                }
                // Desaparece. Si estaba yendo a su última parada, ha llegado: se cierra con el último retraso.
                if (!s.cancelado() && s.viaje() != null && s.indice() >= 0 && s.viaje().esUltima(s.indice())
                        && s.retrasoS() != null) {
                    cerrados.add(cierre(s, ahora));
                }
                it.remove();
            }
        }
        cancelados = Set.copyOf(canceladosAhora);
        return new ResultadoRetrasos(true, completa, cerrados, sinCerrar);
    }

    public synchronized ResultadoPosiciones procesarPosiciones(FeedMessage muestra, Instant ahora) {
        long cabecera = muestra.getHeader().getTimestamp();
        if (cabecera <= cabeceraPosiciones) {
            return new ResultadoPosiciones(false, false, List.of(), 0);
        }
        cabeceraPosiciones = cabecera;
        Instant instanteCabecera = Instant.ofEpochSecond(cabecera);
        ultimaMuestraPosiciones = instanteCabecera;
        boolean completa = detectorPosiciones.completa(muestra.getEntityCount());
        Set<String> vistos = new HashSet<>();
        List<Tren> cambios = new ArrayList<>();

        for (FeedEntity entidad : muestra.getEntityList()) {
            if (!entidad.hasVehicle()) {
                continue;
            }
            VehiclePosition v = entidad.getVehicle();
            String tripId = v.getTrip().getTripId().trim();
            String vehiculo = v.getVehicle().getId().isBlank() ? null : v.getVehicle().getId().trim();
            String id = !tripId.isEmpty() ? tripId : vehiculo != null ? "vehiculo:" + vehiculo : "entidad:" + entidad.getId();
            if (!vistos.add(id)) {
                continue;
            }
            Tren anterior = trenes.get(id);
            Viaje viaje = tripId.isEmpty() ? null : horario.viaje(tripId);
            SeguimientoViaje seg = seguimiento.get(tripId);

            String stopId = v.getStopId().trim();
            if (stopId.isEmpty() || SIN_COORDENADAS.equals(stopId)) {
                stopId = null;
            }
            Double lat = null;
            Double lon = null;
            if (v.hasPosition() && (v.getPosition().getLatitude() != 0 || v.getPosition().getLongitude() != 0)) {
                lat = (double) v.getPosition().getLatitude();
                lon = (double) v.getPosition().getLongitude();
            }
            Instant marca = v.hasTimestamp() ? Instant.ofEpochSecond(v.getTimestamp()) : instanteCabecera;
            LocalDate fecha = seg != null ? seg.fecha()
                    : anterior != null && anterior.fechaServicio() != null ? anterior.fechaServicio()
                    : viaje != null ? viaje.fechaServicio(ahora, zona) : LocalDate.ofInstant(ahora, zona);
            int indice = -1;
            if (viaje != null && stopId != null) {
                int desde = anterior != null && anterior.indiceParada() >= 0 ? anterior.indiceParada() : 0;
                indice = viaje.indiceDe(stopId, desde);
                if (indice < 0) {
                    indice = viaje.indiceDe(stopId, 0);
                }
            }
            Integer retraso = seg == null ? null : seg.retrasoS();
            boolean cancelado = seg != null && seg.cancelado();
            EstadoTren estado = clasificar(viaje, indice, fecha, retraso, cancelado, ahora);
            String linea = viaje != null ? viaje.linea() : lineaDeEtiqueta(v.getVehicle().getLabel());
            Integer nucleo = viaje != null && viaje.nucleo() != null ? viaje.nucleo() : Nucleos.deIdentificador(tripId);
            String estadoFeed = v.hasCurrentStatus() ? v.getCurrentStatus().name() : null;

            Tren nuevo = new Tren(id, tripId.isEmpty() ? null : tripId, vehiculo, linea, nucleo, viaje, fecha, estado,
                    stopId, indice, estadoFeed, lat, lon, marca, retraso, null, 0);
            nuevo = nuevo.conEstimada(EstimadorPosicion.estimar(nuevo, anterior, ahora, zona));
            if (nuevo.cambiaRespectoA(anterior)) {
                cambios.add(nuevo);
            }
            trenes.put(id, nuevo);
        }

        int desaparecidos = 0;
        if (completa) {
            for (Tren t : trenes.values()) {
                if (vistos.contains(t.id())) {
                    continue;
                }
                int ausencias = t.ausencias() + 1;
                if (ausencias >= muestrasParaDesaparecer) {
                    trenes.remove(t.id(), t);
                    desaparecidos++;
                } else {
                    trenes.put(t.id(), t.conAusencias(ausencias));
                }
            }
        }
        return new ResultadoPosiciones(true, completa, cambios, desaparecidos);
    }

    /** Recalcula la posición estimada de todos los trenes para {@code ahora} (entre muestras, el tren avanza). */
    public synchronized void reestimar(Instant ahora) {
        for (Tren t : trenes.values()) {
            trenes.put(t.id(), t.conEstimada(EstimadorPosicion.estimar(t, t, ahora, zona)));
        }
    }

    // ---------------------------------------------------------------- reglas

    EstadoTren clasificar(Viaje viaje, int indice, LocalDate fecha, Integer retraso, boolean cancelado, Instant ahora) {
        if (cancelado) {
            return EstadoTren.CANCELADO;
        }
        if (viaje == null || viaje.paradas().isEmpty()) {
            return EstadoTren.EN_MARCHA; // sin horario: no se sabe más
        }
        if (indice > 0 && viaje.esUltima(indice)) {
            return EstadoTren.TERMINADO;
        }
        long espera = retraso == null ? 0 : Math.max(0, retraso);
        Instant salida = Viaje.instante(fecha, viaje.paradas().getFirst().salidaS(), zona).plusSeconds(espera);
        if (indice <= 0 && ahora.isBefore(salida)) {
            return EstadoTren.EN_ORIGEN;
        }
        return EstadoTren.EN_MARCHA;
    }

    private static Integer retrasoDe(TripUpdate tu, StopTimeUpdate proxima) {
        if (proxima.hasArrival() && proxima.getArrival().hasDelay()) {
            return proxima.getArrival().getDelay();
        }
        if (proxima.hasDeparture() && proxima.getDeparture().hasDelay()) {
            return proxima.getDeparture().getDelay();
        }
        return tu.hasDelay() ? tu.getDelay() : null;
    }

    /** Índice de la próxima parada en el viaje: por {@code stop_sequence} si viene (Renfe no lo manda) o por {@code stop_id}. */
    private static int indiceProxima(Viaje viaje, StopTimeUpdate proxima, String stopId, SeguimientoViaje previo) {
        if (viaje == null) {
            return -1;
        }
        if (proxima.hasStopSequence()) {
            for (int i = 0; i < viaje.paradas().size(); i++) {
                if (viaje.paradas().get(i).secuencia() == proxima.getStopSequence()) {
                    return i;
                }
            }
        }
        // Desde la parada que ya se tenía: en una circular la misma estación sale dos veces.
        int desde = previo != null && previo.indice() >= 0 ? previo.indice() : 0;
        int indice = viaje.indiceDe(stopId, desde);
        return indice >= 0 ? indice : viaje.indiceDe(stopId, 0);
    }

    private static RetrasoFinal cierre(SeguimientoViaje s, Instant ahora) {
        ParadaViaje parada = s.viaje().paradas().get(s.indice());
        int minutos = (int) Math.round(s.retrasoS() / 60.0);
        return new RetrasoFinal(s.fecha(), s.tripId(), parada.secuencia(), parada.stopId(), s.viaje().routeId(),
                s.viaje().linea(), s.viaje().nucleo(), minutos, ahora);
    }

    /** «C3-17907» → «C3». */
    static String lineaDeEtiqueta(String etiqueta) {
        if (etiqueta == null || etiqueta.isBlank()) {
            return null;
        }
        int guion = etiqueta.indexOf('-');
        return (guion > 0 ? etiqueta.substring(0, guion) : etiqueta).trim();
    }

    /** Número de trenes por estado, con los cancelados que solo están en trip_updates. */
    public Map<EstadoTren, Integer> trenesPorEstado() {
        Map<EstadoTren, Integer> cuenta = new EnumMap<>(EstadoTren.class);
        for (EstadoTren e : EstadoTren.values()) {
            cuenta.put(e, 0);
        }
        Set<String> canceladosEnMapa = new HashSet<>();
        for (Tren t : trenes.values()) {
            cuenta.merge(t.estado(), 1, Integer::sum);
            if (t.estado() == EstadoTren.CANCELADO) {
                canceladosEnMapa.add(t.tripId());
            }
        }
        for (String c : cancelados) {
            if (!canceladosEnMapa.contains(c)) {
                cuenta.merge(EstadoTren.CANCELADO, 1, Integer::sum);
            }
        }
        return cuenta;
    }
}
