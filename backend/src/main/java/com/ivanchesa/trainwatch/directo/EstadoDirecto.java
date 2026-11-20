package com.ivanchesa.trainwatch.directo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * la muestra anterior y otros de la nueva mientras se aplica, pero nunca un tren a medias. El seguimiento de
 * {@code trip_updates} solo lo toca el escritor, dentro del {@code synchronized}, y por eso es un {@link HashMap}.
 *
 * <p><b>Muestras.</b> Solo cuenta una muestra <i>nueva</i>: con la marca de la cabecera posterior a la anterior. Una
 * muestra repetida no hace nada. Un tren desaparece tras faltar en {@code muestrasParaDesaparecer} muestras nuevas y
 * completas seguidas; las incompletas no suman ausencias ({@link DetectorMuestrasIncompletas}).
 *
 * <p><b>Primero calcular, luego aplicar (fase 3b).</b> Cada muestra se procesa en tres pasos:
 * <ol>
 * <li><i>resolver</i> los viajes en el horario, la única parte que lee la base de datos, con un {@code try} por
 * viaje: el que falla se salta (cuenta como visto, sin cambios) y se cuenta en {@code sinResolver};</li>
 * <li><i>calcular</i> el estado nuevo sobre copias, sin tocar nada;</li>
 * <li><i>aplicar</i>: sustituir el seguimiento, los trenes, el detector y la cabecera.</li>
 * </ol>
 * Si algo falla antes del tercer paso, el estado queda como estaba y la muestra se puede volver a procesar (la
 * ingesta no confirma el {@code ETag}).
 *
 * <p><b>Retraso final por parada.</b> {@code trip_updates} trae el retraso de la <i>próxima</i> parada. Mientras la
 * próxima parada no cambia, el retraso se va sustituyendo; cuando pasa a una parada posterior, el último retraso
 * conocido es el retraso final de la parada que queda atrás, y se cierra (una fila de {@code retrasos}). Si entre dos
 * muestras se salta paradas, las intermedias se cierran con ese mismo retraso y marcadas como {@code inferido}. La
 * última parada se cierra cuando el viaje desaparece de {@code trip_updates} estando en ella. Un viaje que desaparece
 * a mitad se guarda {@code retencionRetirados} por si vuelve: si vuelve más adelante, las paradas que faltan se
 * cierran como inferidas (ADR 0012).
 */
public class EstadoDirecto {

    private static final Logger log = LoggerFactory.getLogger(EstadoDirecto.class);

    /** Lo que se sabe de un viaje por {@code trip_updates}. Solo lo usa el escritor. */
    private record SeguimientoViaje(String tripId, Viaje viaje, LocalDate fecha, String stopId, int indice,
            Integer retrasoS, boolean cancelado, int ausencias) {

        SeguimientoViaje conAusencias(int n) {
            return new SeguimientoViaje(tripId, viaje, fecha, stopId, indice, retrasoS, cancelado, n);
        }
    }

    /** Un viaje que desapareció a mitad de recorrido, por si vuelve. */
    private record Retirado(SeguimientoViaje seguimiento, Instant cuando) {
    }

    /**
     * Resultado de procesar {@code vehicle_positions}: los trenes nuevos o que han cambiado van a {@code posiciones}.
     *
     * @param sinResolver viajes que no se pudieron buscar en el horario (la base de datos falló): se saltan
     */
    public record ResultadoPosiciones(boolean nueva, boolean completa, List<Tren> cambios, int desaparecidos,
            int sinResolver) {
    }

    /**
     * Resultado de procesar {@code trip_updates}.
     *
     * @param sinCerrar   paradas que se dejaron atrás pero no se pueden guardar: el viaje no está en el horario (no hay
     *                    {@code stop_sequence}), la parada nueva no va después de la anterior o no había retraso
     * @param inferidos   de los cerrados, los que se cerraron sin verlos como próxima parada
     * @param sinResolver viajes que no se pudieron buscar en el horario (la base de datos falló): se saltan
     */
    public record ResultadoRetrasos(boolean nueva, boolean completa, List<RetrasoFinal> cerrados, int sinCerrar,
            int inferidos, int sinResolver) {
    }

    private static final String SIN_COORDENADAS = "00000";
    /** Una marca de tren a mas de 1 h de la cabecera se sustituye por la cabecera. */
    private static final long MARGEN_MARCA_S = 3600;
    /** Cuánto se recuerda un viaje que desapareció a mitad, por si vuelve. */
    public static final Duration RETENCION_RETIRADOS = Duration.ofHours(3);

    private final FuenteHorario horario;
    private final ZoneId zona;
    private final int muestrasParaDesaparecer;
    private final Duration retencionRetirados;

    private final ConcurrentHashMap<String, Tren> trenes = new ConcurrentHashMap<>();
    /** Viajes cancelados en la última muestra de trip_updates (lectura desde otros hilos). */
    private volatile Set<String> cancelados = Set.of();
    private Map<String, SeguimientoViaje> seguimiento = new HashMap<>();
    private Map<String, Retirado> retirados = new HashMap<>();
    private final DetectorMuestrasIncompletas detectorPosiciones;
    private final DetectorMuestrasIncompletas detectorRetrasos;
    private long cabeceraPosiciones = -1;
    private long cabeceraRetrasos = -1;
    private int retrocesosPosiciones;
    private int retrocesosRetrasos;
    private volatile Instant ultimaMuestraPosiciones;
    private volatile Instant ultimaMuestraRetrasos;

    public EstadoDirecto(FuenteHorario horario, ZoneId zona, int muestrasParaDesaparecer, double fraccionIncompleta) {
        this(horario, zona, muestrasParaDesaparecer, fraccionIncompleta, RETENCION_RETIRADOS);
    }

    public EstadoDirecto(FuenteHorario horario, ZoneId zona, int muestrasParaDesaparecer, double fraccionIncompleta,
            Duration retencionRetirados) {
        this.horario = horario;
        this.zona = zona;
        this.muestrasParaDesaparecer = muestrasParaDesaparecer;
        this.retencionRetirados = retencionRetirados;
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

    /** Viajes desaparecidos a mitad que se recuerdan por si vuelven (para las pruebas y las métricas). */
    public synchronized int retirados() {
        return retirados.size();
    }

    // ---------------------------------------------------------------- escritura (solo el hilo de la ingesta)

    public synchronized ResultadoRetrasos procesarRetrasos(FeedMessage muestra, Instant ahora) {
        long cabecera = muestra.getHeader().getTimestamp();
        if (!esNueva(cabecera, cabeceraRetrasos, retrocesosRetrasos)) {
            retrocesosRetrasos = cabecera < cabeceraRetrasos ? retrocesosRetrasos + 1 : 0;
            return new ResultadoRetrasos(false, false, List.of(), 0, 0, 0);
        }

        // 1. Resolver: la única parte que lee la base de datos.
        Map<String, TripUpdate> porViaje = new LinkedHashMap<>();
        for (FeedEntity entidad : muestra.getEntityList()) {
            if (entidad.hasTripUpdate()) {
                String tripId = entidad.getTripUpdate().getTrip().getTripId().trim();
                if (!tripId.isEmpty()) {
                    porViaje.putIfAbsent(tripId, entidad.getTripUpdate());
                }
            }
        }
        Map<String, Viaje> viajes = new HashMap<>();
        Set<String> sinResolver = resolver(porViaje.keySet(), viajes);

        // 2. Calcular sobre copias.
        DetectorMuestrasIncompletas.Evaluacion evaluacion = detectorRetrasos.evaluar(muestra.getEntityCount());
        Map<String, SeguimientoViaje> nuevo = new HashMap<>(seguimiento);
        Map<String, Retirado> retiradosNuevo = new HashMap<>(retirados);
        retiradosNuevo.values().removeIf(r -> r.cuando().plus(retencionRetirados).isBefore(ahora));
        List<RetrasoFinal> cerrados = new ArrayList<>();
        int[] cuentas = new int[2]; // sinCerrar, inferidos

        for (Map.Entry<String, TripUpdate> e : porViaje.entrySet()) {
            String tripId = e.getKey();
            if (sinResolver.contains(tripId)) {
                continue; // se queda como estaba; la próxima muestra lo pondrá al día
            }
            TripUpdate tu = e.getValue();
            Viaje viaje = viajes.get(tripId);
            SeguimientoViaje previo = nuevo.get(tripId);
            boolean vuelve = false;
            if (previo == null) {
                Retirado r = retiradosNuevo.remove(tripId);
                if (r != null) {
                    previo = r.seguimiento();
                    vuelve = true;
                }
            }
            // La fecha se fija con el viaje; si antes no se encontraba en el horario y ahora si, se recalcula.
            LocalDate fecha = previo != null && (previo.viaje() != null || viaje == null) ? previo.fecha()
                    : viaje != null ? viaje.fechaServicio(ahora, zona) : LocalDate.ofInstant(ahora, zona);

            if (tu.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED) {
                // Lo pendiente de un cancelado se descarta: no ha pasado por esa parada.
                nuevo.put(tripId, new SeguimientoViaje(tripId, viaje, fecha, null, -1, null, true, 0));
                continue;
            }
            if (tu.getStopTimeUpdateCount() == 0) {
                Integer retraso = tu.hasDelay() ? Integer.valueOf(tu.getDelay()) : previo == null ? null : previo.retrasoS();
                nuevo.put(tripId, previo == null || previo.cancelado()
                        ? new SeguimientoViaje(tripId, viaje, fecha, null, -1, retraso, false, 0)
                        : new SeguimientoViaje(tripId, viaje, fecha, previo.stopId(), previo.indice(), retraso, false, 0));
                continue;
            }
            StopTimeUpdate proxima = tu.getStopTimeUpdate(0);
            Integer retraso = retrasoDe(tu, proxima);
            String stopId = proxima.getStopId().trim();
            int indice = indiceProxima(viaje, proxima, stopId, previo);

            if (previo != null && !previo.cancelado() && previo.stopId() != null && !previo.stopId().equals(stopId)) {
                cerrarHasta(previo, indice, vuelve, ahora, cerrados, cuentas);
            }
            nuevo.put(tripId, new SeguimientoViaje(tripId, viaje, fecha, stopId.isEmpty() ? null : stopId,
                    indice, retraso, false, 0));
        }

        if (evaluacion.completa()) {
            for (Iterator<Map.Entry<String, SeguimientoViaje>> it = nuevo.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, SeguimientoViaje> entrada = it.next();
                SeguimientoViaje s = entrada.getValue();
                if (porViaje.containsKey(s.tripId())) {
                    continue; // visto (también los que no se pudieron resolver: no suman ausencias)
                }
                int ausencias = s.ausencias() + 1;
                if (ausencias < muestrasParaDesaparecer) {
                    entrada.setValue(s.conAusencias(ausencias));
                    continue;
                }
                boolean conParada = !s.cancelado() && s.viaje() != null && s.indice() >= 0 && s.retrasoS() != null;
                if (conParada && s.viaje().esUltima(s.indice())) {
                    // Desaparece yendo a su última parada: ha llegado. Se cierra con el último retraso.
                    cerrados.add(cierre(s, s.indice(), false, ahora));
                } else if (conParada) {
                    // Desaparece a mitad: se recuerda un tiempo por si vuelve (ADR 0012).
                    retiradosNuevo.put(s.tripId(), new Retirado(s.conAusencias(0), ahora));
                }
                it.remove();
            }
        }
        Set<String> todos = new HashSet<>();
        for (SeguimientoViaje s : nuevo.values()) {
            if (s.cancelado()) {
                todos.add(s.tripId()); // tambien los que faltan en esta muestra y aun no han desaparecido
            }
        }

        // 3. Aplicar: a partir de aquí no hay nada que pueda fallar.
        seguimiento = nuevo;
        retirados = retiradosNuevo;
        detectorRetrasos.aceptar(evaluacion);
        retrocesosRetrasos = 0;
        cabeceraRetrasos = cabecera;
        ultimaMuestraRetrasos = Instant.ofEpochSecond(cabecera);
        cancelados = Set.copyOf(todos);
        return new ResultadoRetrasos(true, evaluacion.completa(), cerrados, cuentas[0], cuentas[1], sinResolver.size());
    }

    public synchronized ResultadoPosiciones procesarPosiciones(FeedMessage muestra, Instant ahora) {
        long cabecera = muestra.getHeader().getTimestamp();
        if (!esNueva(cabecera, cabeceraPosiciones, retrocesosPosiciones)) {
            retrocesosPosiciones = cabecera < cabeceraPosiciones ? retrocesosPosiciones + 1 : 0;
            return new ResultadoPosiciones(false, false, List.of(), 0, 0);
        }
        Instant instanteCabecera = Instant.ofEpochSecond(cabecera);

        // 1. Resolver.
        Set<String> tripIds = new HashSet<>();
        for (FeedEntity entidad : muestra.getEntityList()) {
            if (entidad.hasVehicle()) {
                String tripId = entidad.getVehicle().getTrip().getTripId().trim();
                if (!tripId.isEmpty()) {
                    tripIds.add(tripId);
                }
            }
        }
        Map<String, Viaje> viajes = new HashMap<>();
        Set<String> sinResolver = resolver(tripIds, viajes);

        // 2. Calcular: los trenes nuevos van a un mapa aparte.
        DetectorMuestrasIncompletas.Evaluacion evaluacion = detectorPosiciones.evaluar(muestra.getEntityCount());
        Set<String> vistos = new HashSet<>();
        Map<String, Tren> nuevos = new LinkedHashMap<>();
        List<Tren> cambios = new ArrayList<>();

        for (FeedEntity entidad : muestra.getEntityList()) {
            if (!entidad.hasVehicle()) {
                continue;
            }
            VehiclePosition v = entidad.getVehicle();
            String tripId = v.getTrip().getTripId().trim();
            String vehiculo = v.getVehicle().getId().isBlank() ? null : v.getVehicle().getId().trim();
            String id = !tripId.isEmpty() ? tripId : vehiculo != null ? "vehiculo:" + vehiculo : "entidad:" + entidad.getId();
            if (!vistos.add(id) || sinResolver.contains(tripId)) {
                continue; // repetido, o sin horario por un fallo: sigue como estaba y no suma ausencias
            }
            Tren anterior = trenes.get(id);
            Viaje viaje = tripId.isEmpty() ? null : viajes.get(tripId);
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
            if (cabecera > 0 && Math.abs(marca.getEpochSecond() - cabecera) > MARGEN_MARCA_S) {
                marca = instanteCabecera; // una marca absurda no puede caer fuera de las particiones
            }
            LocalDate fecha = seg != null && seg.viaje() != null ? seg.fecha()
                    : anterior != null && anterior.viaje() != null ? anterior.fechaServicio()
                    : viaje != null ? viaje.fechaServicio(ahora, zona) : LocalDate.ofInstant(ahora, zona);
            if (stopId == null && anterior != null) {
                stopId = anterior.stopId(); // sin parada en esta muestra (00000): sigue en la ultima conocida
            }
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
            nuevos.put(id, nuevo);
        }

        Map<String, Tren> ausentes = new HashMap<>();
        Set<String> desaparecen = new HashSet<>();
        if (evaluacion.completa()) {
            for (Tren t : trenes.values()) {
                if (vistos.contains(t.id())) {
                    continue;
                }
                int ausencias = t.ausencias() + 1;
                if (ausencias >= muestrasParaDesaparecer) {
                    desaparecen.add(t.id());
                } else {
                    ausentes.put(t.id(), t.conAusencias(ausencias));
                }
            }
        }

        // 3. Aplicar.
        trenes.putAll(nuevos);
        trenes.putAll(ausentes);
        for (String id : desaparecen) {
            trenes.remove(id);
        }
        detectorPosiciones.aceptar(evaluacion);
        retrocesosPosiciones = 0;
        cabeceraPosiciones = cabecera;
        ultimaMuestraPosiciones = instanteCabecera;
        return new ResultadoPosiciones(true, evaluacion.completa(), cambios, desaparecen.size(), sinResolver.size());
    }

    /** Recalcula la posición estimada de todos los trenes para {@code ahora} (entre muestras, el tren avanza). */
    public synchronized void reestimar(Instant ahora) {
        for (Tren t : trenes.values()) {
            // El estado depende de la hora (EN_ORIGEN pasa a EN_MARCHA al llegar su salida): se vuelve a calcular.
            EstadoTren estadoAhora = clasificar(t.viaje(), t.indiceParada(), t.fechaServicio(), t.retrasoS(),
                    t.estado() == EstadoTren.CANCELADO, ahora);
            Tren actual = estadoAhora == t.estado() ? t : t.conEstado(estadoAhora);
            trenes.put(t.id(), actual.conEstimada(EstimadorPosicion.estimar(actual, t, ahora, zona)));
        }
    }

    // ---------------------------------------------------------------- reglas

    /**
     * Busca cada viaje en el horario, con un {@code try} por viaje. Devuelve los que fallaron (la base de datos no
     * contesta): la muestra sigue sin ellos.
     */
    private Set<String> resolver(Set<String> tripIds, Map<String, Viaje> viajes) {
        Set<String> fallidos = new HashSet<>();
        RuntimeException primero = null;
        for (String tripId : tripIds) {
            try {
                viajes.put(tripId, horario.viaje(tripId));
            } catch (RuntimeException e) {
                fallidos.add(tripId);
                if (primero == null) {
                    primero = e;
                }
            }
        }
        if (primero != null) {
            log.warn("No se pudieron buscar {} viajes en el horario; se saltan en esta muestra", fallidos.size(),
                    primero);
        }
        return fallidos;
    }

    /**
     * Cierra las paradas desde la de {@code previo} hasta la anterior a {@code hasta}: la de {@code previo} con su
     * último retraso; las intermedias (que el tren se saltó entre dos muestras), con el mismo retraso y como
     * inferidas. Si el viaje vuelve tras desaparecer, todas son inferidas: el último retraso es de antes.
     */
    private void cerrarHasta(SeguimientoViaje previo, int hasta, boolean vuelve, Instant ahora,
            List<RetrasoFinal> cerrados, int[] cuentas) {
        boolean avanza = previo.viaje() != null && previo.indice() >= 0 && hasta > previo.indice();
        if (!avanza) {
            cuentas[0]++;
            return;
        }
        if (previo.retrasoS() == null) {
            cuentas[0] += hasta - previo.indice();
            return;
        }
        for (int i = previo.indice(); i < hasta; i++) {
            boolean inferido = vuelve || i > previo.indice();
            cerrados.add(cierre(previo, i, inferido, ahora));
            if (inferido) {
                cuentas[1]++;
            }
        }
    }

    /**
     * Una muestra es nueva si su cabecera es posterior a la anterior. Sin marca (0), se confia en el ETag del cliente.
     * Si la marca retrocede (otro servidor, un reinicio del feed), se descarta, pero si se repite en 3 muestras
     * seguidas se acepta como el reloj nuevo: si no, la ingesta se quedaria parada hasta reiniciar la aplicacion.
     */
    private boolean esNueva(long cabecera, long anterior, int retrocesosSeguidos) {
        if (cabecera == 0 || cabecera > anterior) {
            return true;
        }
        return cabecera < anterior && retrocesosSeguidos + 1 >= muestrasParaDesaparecer;
    }

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

    private static RetrasoFinal cierre(SeguimientoViaje s, int indice, boolean inferido, Instant ahora) {
        ParadaViaje parada = s.viaje().paradas().get(indice);
        int minutos = (int) Math.round(s.retrasoS() / 60.0);
        return new RetrasoFinal(s.fecha(), s.tripId(), parada.secuencia(), parada.stopId(), s.viaje().routeId(),
                s.viaje().linea(), s.viaje().nucleo(), minutos, ahora, inferido);
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
