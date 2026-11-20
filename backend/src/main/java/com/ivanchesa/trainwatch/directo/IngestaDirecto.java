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
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <li>se reestima la posición de todos los trenes y se calcula el resumen.</li>
 * </ol>
 * Primero los retrasos, para que las posiciones usen el retraso de la misma muestra. Un 304 no procesa nada. Si un
 * feed falla, los otros siguen; si falla la base de datos, el estado en memoria sigue y las filas de ese ciclo se
 * pierden (se cuenta en {@code trainwatch.directo.errores}).
 *
 * <p>El programador de Spring tiene dos hilos ({@code spring.task.scheduling.pool.size}): la ingesta no espera a otras
 * tareas programadas.
 */
@Component
public class IngestaDirecto {

    private static final Logger log = LoggerFactory.getLogger(IngestaDirecto.class);

    private final ClienteFeeds cliente;
    private final EstadoDirecto estado;
    private final HorarioDirectoJdbc horario;
    private final RepositorioDirecto repositorio;
    private final GestorParticiones particiones;
    private final DirectoProperties propiedades;
    private final Clock reloj;
    private final MeterRegistry metricas;

    private final AtomicReference<ResumenDirecto> resumen;
    private volatile Instant ultimaMuestraAvisos;

    public IngestaDirecto(ClienteFeeds cliente, EstadoDirecto estado, HorarioDirectoJdbc horario,
            RepositorioDirecto repositorio, GestorParticiones particiones, DirectoProperties propiedades, Clock reloj,
            MeterRegistry metricas) {
        this.cliente = cliente;
        this.estado = estado;
        this.horario = horario;
        this.repositorio = repositorio;
        this.particiones = particiones;
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.metricas = metricas;
        this.resumen = new AtomicReference<>(ResumenDirecto.vacio(reloj.instant()));
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
        Instant ahora = reloj.instant();
        try {
            horario.refrescarSiCambia(ahora);
            particiones.mantenerSiToca(ahora);
        } catch (RuntimeException e) {
            error(null, "base_de_datos", e);
        }

        descargar(Feed.RETRASOS).ifPresent(muestra -> {
            ResultadoRetrasos r = procesar(Feed.RETRASOS, () -> estado.procesarRetrasos(muestra, ahora));
            if (r != null && r.nueva()) {
                contarMuestra(Feed.RETRASOS, r.completa());
                metricas.counter("trainwatch.directo.retrasos.sin.cerrar").increment(r.sinCerrar());
                guardar("retrasos", () -> repositorio.guardarRetrasos(r.cerrados()));
            }
        });
        descargar(Feed.POSICIONES).ifPresent(muestra -> {
            ResultadoPosiciones r = procesar(Feed.POSICIONES, () -> estado.procesarPosiciones(muestra, ahora));
            if (r != null && r.nueva()) {
                contarMuestra(Feed.POSICIONES, r.completa());
                metricas.counter("trainwatch.directo.trenes.desaparecidos").increment(r.desaparecidos());
                guardar("posiciones", () -> repositorio.guardarPosiciones(r.cambios()));
            }
        });
        descargar(Feed.AVISOS).ifPresent(muestra -> {
            long cabecera = muestra.getHeader().getTimestamp();
            Instant previa = ultimaMuestraAvisos;
            if (previa == null || cabecera > previa.getEpochSecond()) {
                ultimaMuestraAvisos = Instant.ofEpochSecond(cabecera);
                contarMuestra(Feed.AVISOS, true);
                guardar("avisos", () -> repositorio.sustituirAvisos(avisosDe(muestra, ahora)));
            }
        });

        try {
            estado.reestimar(ahora);
            resumen.set(calcularResumen(ahora));
        } catch (RuntimeException e) {
            error(null, "resumen", e);
        }
    }

    public ResumenDirecto resumen() {
        return resumen.get();
    }

    // ---------------------------------------------------------------- pasos

    private Optional<FeedMessage> descargar(Feed feed) {
        try {
            Descarga d = cliente.descargar(feed);
            contarDescarga(feed, String.valueOf(d.estado()));
            return Optional.ofNullable(d.muestra());
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

    private void guardar(String tabla, IntSupplier escritura) {
        try {
            int filas = escritura.getAsInt();
            metricas.counter("trainwatch.directo.filas", "tabla", tabla).increment(filas);
        } catch (RuntimeException e) {
            error(null, "base_de_datos", e);
        }
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
                porNucleo, circuitos);
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

    private void contarMuestra(Feed feed, boolean completa) {
        metricas.counter("trainwatch.directo.muestras", "feed", feed.etiqueta(), "completa", String.valueOf(completa))
                .increment();
    }

    private void error(Feed feed, String tipo, Exception e) {
        Counter.builder("trainwatch.directo.errores").tag("feed", feed == null ? "-" : feed.etiqueta()).tag("tipo", tipo)
                .register(metricas).increment();
        log.warn("Ingesta en directo, error de {}{}: {}", tipo, feed == null ? "" : " en " + feed.etiqueta(),
                e.toString());
    }
}
