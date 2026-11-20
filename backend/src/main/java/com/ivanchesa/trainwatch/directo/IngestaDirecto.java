package com.ivanchesa.trainwatch.directo;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import com.ivanchesa.trainwatch.directo.ClienteFeeds.Descarga;
import com.ivanchesa.trainwatch.directo.EstadoDirecto.ResultadoPosiciones;
import com.ivanchesa.trainwatch.directo.EstadoDirecto.ResultadoRetrasos;
import com.ivanchesa.trainwatch.directo.RepositorioDirecto.Aviso;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * El ciclo de la ingesta en directo, cada {@code trainwatch.directo.intervalo} (7 s) contando desde el final del
 * anterior ({@code fixedDelay}: nunca se solapan dos ciclos):
 * <ol>
 * <li>{@code trip_updates}: retrasos y cancelaciones; los retrasos finales que se cierran van a {@code retrasos};</li>
 * <li>{@code vehicle_positions}: el estado de cada tren; los que cambian van a {@code posiciones};</li>
 * <li>{@code alerts}: sustituyen a los avisos anteriores;</li>
 * <li>se reestima la posición de todos los trenes y se calcula el resumen;</li>
 * <li>se publica {@link CicloDirectoTerminado}: el directo por SSE manda los cambios (fase 4).</li>
 * </ol>
 * Primero los retrasos, para que las posiciones usen el retraso de la misma muestra. Un 304 no procesa nada. Si un
 * feed falla, los otros siguen.
 *
 * <p><b>Si falla la base de datos (fase 3b).</b>
 * <ul>
 * <li>Al <i>leer</i> el horario: el viaje que no se puede buscar se salta en esa muestra y se cuenta
 * ({@code trainwatch.directo.viajes.sin.resolver}). Si el procesado de una muestra falla entero, el estado no cambia
 * y el {@code ETag} no se confirma ({@link ClienteFeeds#confirmar}): el ciclo siguiente vuelve a traer la muestra.</li>
 * <li>Al <i>escribir</i>: las filas de {@code retrasos} y {@code posiciones} esperan en una cola acotada y se
 * reintentan en cada ciclo; los avisos no se dan por recibidos hasta escribirlos. El mapa en directo sigue al día.</li>
 * </ul>
 * Los tiempos máximos de la base de datos ({@code application.properties}: Hikari, {@code socketTimeout} y
 * {@code statement_timeout}) hacen que una base de datos colgada sea un error en segundos, no un ciclo parado.
 *
 * <p>Las particiones las mantiene {@link GestorParticiones} en su propia tarea programada: un {@code DROP} esperando
 * un cerrojo no para la ingesta. El programador de Spring tiene tres hilos ({@code spring.task.scheduling.pool.size}).
 */
@Component
public class IngestaDirecto {

    private static final Logger log = LoggerFactory.getLogger(IngestaDirecto.class);

    private final ClienteFeeds cliente;
    private final EstadoDirecto estado;
    private final HorarioDirectoJdbc horario;
    private final RepositorioDirecto repositorio;
    private final DirectoProperties propiedades;
    private final Clock reloj;
    private final MeterRegistry metricas;
    private final ApplicationEventPublisher eventos;

    private final AtomicReference<ResumenDirecto> resumen;
    private volatile Instant ultimaMuestraAvisos;
    private final DetectorMuestrasIncompletas detectorAvisos;
    /**
     * Retrasos finales cerrados que aun no se han podido escribir (la base de datos fallo): se reintentan en el
     * ciclo siguiente. Son la base de las estadisticas y el estado en memoria ya no los volveria a cerrar. Acotada.
     */
    private final List<RetrasoFinal> retrasosPendientes = new ArrayList<>();
    private static final int MAXIMO_PENDIENTES = 100_000;
    /** Lo mismo para las posiciones (fase 3b): unas 340 filas por minuto a mediodía, así que caben unas 2 h. */
    private final List<Tren> posicionesPendientes = new ArrayList<>();
    private static final int MAXIMO_POSICIONES_PENDIENTES = 50_000;
    /** Fase 7: el final del último ciclo que terminó sin nada pendiente de escribir (ver {@link ResumenDirecto}). */
    private volatile Instant guardadoAlDia;

    public IngestaDirecto(ClienteFeeds cliente, EstadoDirecto estado, HorarioDirectoJdbc horario,
            RepositorioDirecto repositorio, DirectoProperties propiedades, Clock reloj, MeterRegistry metricas,
            ApplicationEventPublisher eventos) {
        this.cliente = cliente;
        this.estado = estado;
        this.horario = horario;
        this.repositorio = repositorio;
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.metricas = metricas;
        this.eventos = eventos;
        this.resumen = new AtomicReference<>(ResumenDirecto.vacio(reloj.instant()));
        this.detectorAvisos = new DetectorMuestrasIncompletas(propiedades.fraccionIncompleta(),
                propiedades.muestrasParaDesaparecer());
        registrarIndicadores();
    }

    @Scheduled(fixedDelayString = "${trainwatch.directo.intervalo:PT7S}", initialDelayString = "PT10S")
    void programado() {
        if (propiedades.activada()) {
            ciclo();
        }
    }

    /** Un ciclo completo. Público para las pruebas; {@code synchronized} para que nunca vayan dos a la vez. */
    public synchronized void ciclo() {
        try {
            horario.refrescarSiCambia(reloj.instant());
        } catch (RuntimeException e) {
            error(null, "base_de_datos", e);
        }

        descargar(Feed.RETRASOS).ifPresent(d -> {
            Instant ahoraRetrasos = reloj.instant(); // la descarga puede haber tardado (reintentos)
            ResultadoRetrasos r = procesar(Feed.RETRASOS, () -> estado.procesarRetrasos(d.muestra(), ahoraRetrasos));
            if (r == null) {
                return; // sin confirmar: el ciclo siguiente vuelve a traer la muestra
            }
            cliente.confirmar(d);
            if (r.nueva()) {
                contarMuestra(Feed.RETRASOS, r.completa());
                metricas.counter("trainwatch.directo.retrasos.sin.cerrar").increment(r.sinCerrar());
                metricas.counter("trainwatch.directo.retrasos.inferidos").increment(r.inferidos());
                contarSinResolver(Feed.RETRASOS, r.sinResolver());
                encolar(retrasosPendientes, r.cerrados(), MAXIMO_PENDIENTES, "retrasos");
            }
        });
        guardarPendientes("retrasos", retrasosPendientes, repositorio::guardarRetrasos);

        descargar(Feed.POSICIONES).ifPresent(d -> {
            Instant ahoraPosiciones = reloj.instant();
            ResultadoPosiciones r = procesar(Feed.POSICIONES,
                    () -> estado.procesarPosiciones(d.muestra(), ahoraPosiciones));
            if (r == null) {
                return;
            }
            cliente.confirmar(d);
            if (r.nueva()) {
                contarMuestra(Feed.POSICIONES, r.completa());
                metricas.counter("trainwatch.directo.trenes.desaparecidos").increment(r.desaparecidos());
                contarSinResolver(Feed.POSICIONES, r.sinResolver());
                encolar(posicionesPendientes, r.cambios(), MAXIMO_POSICIONES_PENDIENTES, "posiciones");
            }
        });
        guardarPendientes("posiciones", posicionesPendientes, repositorio::guardarPosiciones);

        descargar(Feed.AVISOS).ifPresent(this::procesarAvisos);

        Instant fin = reloj.instant();
        if (retrasosPendientes.isEmpty() && posicionesPendientes.isEmpty()) {
            guardadoAlDia = fin;
        }
        try {
            estado.reestimar(fin);
            resumen.set(calcularResumen(fin));
        } catch (RuntimeException e) {
            error(null, "resumen", e);
        }
        // Fase 4: el directo por SSE manda los cambios. Si falla, la ingesta sigue: es otro consumidor del estado.
        try {
            eventos.publishEvent(new CicloDirectoTerminado(fin));
        } catch (RuntimeException e) {
            error(null, "difusion", e);
        }
    }

    /**
     * El resumen del último ciclo, con la antigüedad del último dato calculada <i>ahora</i> (fase 3b): si la ingesta
     * se queda parada, la antigüedad sigue creciendo y se nota.
     */
    public ResumenDirecto resumen() {
        // El último dato, del estado (no del resumen): si el resumen no se pudo calcular, el dato sigue al día.
        return resumen.get().alInstante(reloj.instant(), estado.ultimaMuestraPosiciones(), guardadoAlDia,
                pendientesPorTabla());
    }

    /**
     * Filas que esperan a que la base de datos vuelva, por tabla. Lo lee el hilo de la API mientras la ingesta escribe
     * en las listas: el tamaño puede estar a medias de un ciclo, que para un indicador basta (como los gauges).
     */
    private Map<String, Integer> pendientesPorTabla() {
        return Map.of("retrasos", retrasosPendientes.size(), "posiciones", posicionesPendientes.size());
    }

    /** Filas que esperan a que la base de datos vuelva (para las pruebas). */
    int pendientes(String tabla) {
        return switch (tabla) {
            case "retrasos" -> retrasosPendientes.size();
            case "posiciones" -> posicionesPendientes.size();
            default -> throw new IllegalArgumentException(tabla);
        };
    }

    // ---------------------------------------------------------------- pasos

    private Optional<Descarga> descargar(Feed feed) {
        try {
            Descarga d = cliente.descargar(feed);
            contarDescarga(feed, String.valueOf(d.estado()));
            return d.nueva() ? Optional.of(d) : Optional.empty();
        } catch (CallNotPermittedException e) {
            contarDescarga(feed, "circuito_abierto");
            metricas.counter("trainwatch.directo.errores", "feed", feed.etiqueta(), "tipo", "circuito_abierto")
                    .increment();
        } catch (IOException | RuntimeException e) {
            contarDescarga(feed, "error");
            error(feed, "descarga", e);
        }
        return Optional.empty();
    }

    private <T> T procesar(Feed feed, Supplier<T> paso) {
        try {
            return paso.get();
        } catch (RuntimeException e) {
            error(feed, "procesado", e);
            return null;
        }
    }

    /**
     * Avisos: una muestra nueva sustituye a los anteriores. No se da por recibida (ni se confirma su {@code ETag})
     * hasta que se ha escrito: si la base de datos falla, se repite en el ciclo siguiente.
     */
    private void procesarAvisos(Descarga d) {
        FeedMessage muestra = d.muestra();
        long cabecera = muestra.getHeader().getTimestamp();
        Instant previa = ultimaMuestraAvisos;
        if (previa != null && cabecera != 0 && cabecera == previa.getEpochSecond()) {
            cliente.confirmar(d); // la misma muestra: nada que hacer
            return;
        }
        // Una muestra de avisos vacia o con muchos menos no borra los que hay (como las incompletas).
        DetectorMuestrasIncompletas.Evaluacion evaluacion = detectorAvisos.evaluar(muestra.getEntityCount());
        if (evaluacion.completa()) {
            try {
                int filas = repositorio.sustituirAvisos(avisosDe(muestra, reloj.instant()));
                metricas.counter("trainwatch.directo.filas", "tabla", "avisos").increment(filas);
            } catch (RuntimeException e) {
                error(null, "base_de_datos", e);
                return;
            }
        }
        detectorAvisos.aceptar(evaluacion);
        ultimaMuestraAvisos = Instant.ofEpochSecond(cabecera);
        contarMuestra(Feed.AVISOS, evaluacion.completa());
        cliente.confirmar(d);
    }

    /** Añade a una cola de pendientes; si pasa del máximo, se descartan las más viejas (y se cuentan). */
    private <T> void encolar(List<T> cola, List<T> nuevas, int maximo, String tabla) {
        cola.addAll(nuevas);
        if (cola.size() > maximo) {
            int sobran = cola.size() - maximo;
            cola.subList(0, sobran).clear();
            metricas.counter("trainwatch.directo.filas.descartadas", "tabla", tabla).increment(sobran);
        }
    }

    /**
     * Escribe lo pendiente de una tabla, en una transacción. Si falla, se queda en la cola para el ciclo siguiente.
     * Si falla porque alguna fila no cabe en ninguna partición (no se arreglará sola reintentando), se escriben de una
     * en una para apartar esas filas y contarlas: si no, una sola fila imposible bloquearía la cola entera.
     */
    private <T> void guardarPendientes(String tabla, List<T> cola, ToIntFunction<List<T>> escritura) {
        if (cola.isEmpty()) {
            return;
        }
        try {
            int filas = escritura.applyAsInt(List.copyOf(cola));
            metricas.counter("trainwatch.directo.filas", "tabla", tabla).increment(filas);
            cola.clear();
        } catch (RuntimeException e) {
            if (!sinParticion(e)) {
                error(null, "base_de_datos", e);
                return;
            }
            log.warn("Ingesta en directo: filas de {} sin particion; se apartan de una en una", tabla, e);
            List<T> quedan = new ArrayList<>();
            int escritas = 0;
            int apartadas = 0;
            for (int i = 0; i < cola.size(); i++) {
                T fila = cola.get(i);
                try {
                    escritas += escritura.applyAsInt(List.of(fila));
                } catch (RuntimeException otra) {
                    if (sinParticion(otra)) {
                        apartadas++;
                    } else {
                        error(null, "base_de_datos", otra);
                        quedan.addAll(cola.subList(i, cola.size()));
                        break;
                    }
                }
            }
            cola.clear();
            cola.addAll(quedan);
            metricas.counter("trainwatch.directo.filas", "tabla", tabla).increment(escritas);
            metricas.counter("trainwatch.directo.filas.descartadas", "tabla", tabla).increment(apartadas);
        }
    }

    /** La fila no cabe en ninguna particion: SQLSTATE 23514. */
    private static boolean sinParticion(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql && "23514".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    ResumenDirecto calcularResumen(Instant ahora) {
        List<Tren> trenes = estado.trenes();
        Set<String> cancelados = estado.cancelados();
        Map<Integer, Set<String>> programados = horario.enMarchaSegunHorario(ahora);
        Map<Integer, Set<String>> enDirectoPorNucleo = new HashMap<>();
        int sinHorario = 0;
        for (Tren t : trenes) {
            if (t.sinHorario()) {
                sinHorario++;
            }
            if (t.nucleo() != null && t.estado() != EstadoTren.CANCELADO) {
                enDirectoPorNucleo.computeIfAbsent(t.nucleo(), k -> new HashSet<>()).add(t.tripId());
            }
        }
        Set<Integer> nucleos = new TreeSet<>(horario.nucleos());
        nucleos.addAll(enDirectoPorNucleo.keySet());
        List<ResumenDirecto.Nucleo> porNucleo = new ArrayList<>();
        for (Integer n : nucleos) {
            Set<String> vivos = enDirectoPorNucleo.getOrDefault(n, Set.of());
            Set<String> enMarcha = programados.getOrDefault(n, Set.of());
            int vistos = 0;
            for (String trip : enMarcha) {
                if (vivos.contains(trip) && !cancelados.contains(trip)) {
                    vistos++;
                }
            }
            String estadoNucleo = vivos.isEmpty() ? enMarcha.isEmpty() ? "SIN_SERVICIO" : "SIN_DATOS_EN_DIRECTO"
                    : "EN_DIRECTO";
            Double cobertura = enMarcha.isEmpty() ? null : Math.round(1000.0 * vistos / enMarcha.size()) / 10.0;
            porNucleo.add(new ResumenDirecto.Nucleo(n, Nucleos.nombre(n), estadoNucleo, vivos.size(), enMarcha.size(),
                    vistos, cobertura));
        }
        Map<String, String> circuitos = new LinkedHashMap<>();
        for (Feed f : Feed.values()) {
            circuitos.put(f.etiqueta(), cliente.estadoCircuito(f).name());
        }
        Instant ultimo = estado.ultimaMuestraPosiciones();
        Long antiguedad = ultimo == null ? null : Duration.between(ultimo, ahora).toSeconds();
        return new ResumenDirecto(ahora, ultimo, antiguedad, trenes.size(), estado.trenesPorEstado(), sinHorario,
                porNucleo, circuitos, guardadoAlDia, pendientesPorTabla());
    }

    /** Una fila por aviso y entidad afectada (línea o estación). El texto, en español si viene. */
    static List<Aviso> avisosDe(FeedMessage muestra, Instant ahora) {
        List<Aviso> avisos = new ArrayList<>();
        for (FeedEntity entidad : muestra.getEntityList()) {
            if (!entidad.hasAlert()) {
                continue;
            }
            Alert a = entidad.getAlert();
            String texto = texto(a.getDescriptionText());
            if (texto == null) {
                texto = texto(a.getHeaderText());
            }
            if (texto == null) {
                continue;
            }
            Instant inicio = null;
            Instant fin = null;
            if (a.getActivePeriodCount() > 0) {
                var periodo = a.getActivePeriod(0);
                inicio = periodo.hasStart() ? Instant.ofEpochSecond(periodo.getStart()) : null;
                fin = periodo.hasEnd() ? Instant.ofEpochSecond(periodo.getEnd()) : null;
            }
            if (a.getInformedEntityCount() == 0) {
                avisos.add(new Aviso(entidad.getId(), null, null, null, texto, inicio, fin, ahora));
            }
            for (EntitySelector e : a.getInformedEntityList()) {
                String route = e.getRouteId().isBlank() ? null : e.getRouteId().trim();
                String stop = e.getStopId().isBlank() ? null : e.getStopId().trim();
                avisos.add(new Aviso(entidad.getId(), route, stop, Nucleos.deIdentificador(route), texto, inicio, fin,
                        ahora));
            }
        }
        return avisos;
    }

    private static String texto(TranslatedString t) {
        String primero = null;
        for (TranslatedString.Translation tr : t.getTranslationList()) {
            if ("es".equalsIgnoreCase(tr.getLanguage())) {
                return tr.getText();
            }
            if (primero == null) {
                primero = tr.getText();
            }
        }
        return primero;
    }

    // ---------------------------------------------------------------- métricas

    private void registrarIndicadores() {
        for (Feed f : Feed.values()) {
            Gauge.builder("trainwatch.directo.antiguedad", this, i -> i.antiguedad(f))
                    .tag("feed", f.etiqueta()).baseUnit("seconds")
                    .description("Segundos desde la cabecera de la última muestra nueva del feed").register(metricas);
        }
        for (EstadoTren e : EstadoTren.values()) {
            Gauge.builder("trainwatch.directo.trenes", this, i -> i.resumen().porEstado().getOrDefault(e, 0))
                    .tag("estado", e.name()).description("Trenes en directo por estado").register(metricas);
        }
        Gauge.builder("trainwatch.directo.trenes.sin.horario", this, i -> i.resumen().sinHorario())
                .description("Trenes en directo cuyo viaje no está en el horario").register(metricas);
        Gauge.builder("trainwatch.directo.pendientes", this, i -> i.retrasosPendientes.size()).tag("tabla", "retrasos")
                .description("Filas que esperan a que la base de datos vuelva").register(metricas);
        Gauge.builder("trainwatch.directo.pendientes", this, i -> i.posicionesPendientes.size())
                .tag("tabla", "posiciones").description("Filas que esperan a que la base de datos vuelva")
                .register(metricas);
    }

    private double antiguedad(Feed feed) {
        Instant ultima = switch (feed) {
            case POSICIONES -> estado.ultimaMuestraPosiciones();
            case RETRASOS -> estado.ultimaMuestraRetrasos();
            case AVISOS -> ultimaMuestraAvisos;
        };
        return ultima == null ? Double.NaN : Duration.between(ultima, reloj.instant()).toMillis() / 1000.0;
    }

    private void contarDescarga(Feed feed, String resultado) {
        metricas.counter("trainwatch.directo.descargas", "feed", feed.etiqueta(), "resultado", resultado).increment();
    }

    private void contarSinResolver(Feed feed, int n) {
        metricas.counter("trainwatch.directo.viajes.sin.resolver", "feed", feed.etiqueta()).increment(n);
    }

    private void contarMuestra(Feed feed, boolean completa) {
        metricas.counter("trainwatch.directo.muestras", "feed", feed.etiqueta(), "completa", String.valueOf(completa))
                .increment();
    }

    private void error(Feed feed, String tipo, Exception e) {
        Counter.builder("trainwatch.directo.errores").tag("feed", feed == null ? "-" : feed.etiqueta()).tag("tipo", tipo)
                .register(metricas).increment();
        // Con la excepción como último argumento: SLF4J escribe la traza entera (fase 3b).
        log.warn("Ingesta en directo, error de {}{}", tipo, feed == null ? "" : " en " + feed.etiqueta(), e);
    }
}
