package com.ivanchesa.trainwatch.directo;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Descarga los feeds GTFS Realtime de Renfe.
 *
 * <ul>
 * <li><b>Peticiones condicionales.</b> Se guarda el {@code ETag} y el {@code Last-Modified} de la última respuesta
 * buena de cada feed y se mandan como {@code If-None-Match} e {@code If-Modified-Since}. Si no hay nada nuevo, Renfe
 * contesta 304 sin cuerpo: se puede preguntar cada 5-10 s sin bajar ~30 KB cada vez. Un 200 con el mismo
 * {@code ETag} también cuenta como sin cambios.</li>
 * <li><b>Tiempos cortos.</b> 3 s para conectar y 5 s para toda la petición, cuerpo incluido ({@code sendAsync} con
 * {@code get(timeout)}: {@code HttpRequest.timeout} solo cubre las cabeceras, lección de la fase 2).</li>
 * <li><b>Resilience4j</b>, un circuito y un reintento por feed: el reintento envuelve al circuito. Cada intento
 * cuenta en el circuito; si en sus últimas 10 llamadas falla la mitad, se abre y durante 60 s ni se intenta
 * ({@link CallNotPermittedException}, que no se reintenta). Luego deja pasar una llamada de prueba (semiabierto) y,
 * si va bien, se cierra. El reintento hace hasta 3 intentos con espera creciente (0,5 s y 1 s).</li>
 * </ul>
 *
 * <p>Los validadores se guardan en un mapa concurrente, aunque hoy solo descarga el hilo de la ingesta.
 */
@Component
public class ClienteFeeds {

    /**
     * Una descarga.
     *
     * @param muestra el feed decodificado, o {@code null} si no había nada nuevo (304 o el mismo ETag)
     * @param estado  código HTTP
     */
    public record Descarga(Feed feed, FeedMessage muestra, int estado, int bytes) {

        public boolean nueva() {
            return muestra != null;
        }
    }

    /** El feed contestó algo que no es 200 ni 304, o vino demasiado grande. Cuenta como fallo en el circuito. */
    public static class FeedException extends IOException {
        public FeedException(String mensaje) {
            super(mensaje);
        }
    }

    private record Validadores(String etag, String lastModified) {
    }

    private static final String AGENTE = "TrainWatch (proyecto no oficial)";

    private final DirectoProperties propiedades;
    private final HttpClient http;
    private final Map<Feed, Validadores> validadores = new ConcurrentHashMap<>();
    private final Map<Feed, CircuitBreaker> circuitos = new EnumMap<>(Feed.class);
    private final Map<Feed, Retry> reintentos = new EnumMap<>(Feed.class);

    public ClienteFeeds(DirectoProperties propiedades, MeterRegistry metricas) {
        this.propiedades = propiedades;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propiedades.tiempoConexion())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        CircuitBreakerRegistry registroCircuitos = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(propiedades.ventanaCircuito())
                .minimumNumberOfCalls(Math.min(5, propiedades.ventanaCircuito()))
                .failureRateThreshold(propiedades.umbralFallos())
                .waitDurationInOpenState(propiedades.esperaCircuitoAbierto())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        RetryRegistry registroReintentos = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(propiedades.reintentos())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(propiedades.esperaReintento(), 2.0))
                .ignoreExceptions(CallNotPermittedException.class, InterruptedException.class)
                .build());
        for (Feed feed : Feed.values()) {
            circuitos.put(feed, registroCircuitos.circuitBreaker("feed-" + feed.etiqueta()));
            reintentos.put(feed, registroReintentos.retry("feed-" + feed.etiqueta()));
        }
        // resilience4j_circuitbreaker_state, _calls, resilience4j_retry_calls... con la etiqueta name=feed-…
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registroCircuitos).bindTo(metricas);
        TaggedRetryMetrics.ofRetryRegistry(registroReintentos).bindTo(metricas);
    }

    /**
     * Descarga un feed con reintentos y circuito.
     *
     * @throws CallNotPermittedException si el circuito está abierto
     * @throws IOException               si fallan todos los intentos
     */
    public Descarga descargar(Feed feed) throws IOException {
        CheckedSupplier<Descarga> llamada = () -> pedir(feed);
        CheckedSupplier<Descarga> conCircuito = CircuitBreaker.decorateCheckedSupplier(circuitos.get(feed), llamada);
        CheckedSupplier<Descarga> conReintento = Retry.decorateCheckedSupplier(reintentos.get(feed), conCircuito);
        try {
            return conReintento.get();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // el apagado de la aplicacion tiene que ver la interrupcion
            }
            throw new IOException(e);
        }
    }

    public CircuitBreaker.State estadoCircuito(Feed feed) {
        return circuitos.get(feed).getState();
    }

    private Descarga pedir(Feed feed) throws IOException, InterruptedException {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(propiedades.url(feed))
                .timeout(propiedades.tiempoRespuesta())
                .header("User-Agent", AGENTE)
                .GET();
        Validadores previos = validadores.get(feed);
        if (previos != null && previos.etag() != null) {
            peticion.header("If-None-Match", previos.etag());
        }
        if (previos != null && previos.lastModified() != null) {
            peticion.header("If-Modified-Since", previos.lastModified());
        }
        HttpResponse<byte[]> respuesta = enviar(peticion.build());
        int estado = respuesta.statusCode();
        if (estado == 304) {
            return new Descarga(feed, null, 304, 0);
        }
        if (estado != 200) {
            throw new FeedException("El feed " + feed.etiqueta() + " ha contestado " + estado);
        }
        byte[] cuerpo = respuesta.body();
        if (cuerpo.length > propiedades.tamanoMaximo()) {
            throw new FeedException("El feed " + feed.etiqueta() + " pesa " + cuerpo.length + " bytes");
        }
        String etag = respuesta.headers().firstValue("ETag").orElse(null);
        if (etag != null && previos != null && etag.equals(previos.etag())) {
            return new Descarga(feed, null, 200, cuerpo.length);
        }
        FeedMessage muestra = FeedMessage.parseFrom(cuerpo); // InvalidProtocolBufferException es una IOException
        validadores.put(feed, new Validadores(etag, respuesta.headers().firstValue("Last-Modified").orElse(null)));
        return new Descarga(feed, muestra, 200, cuerpo.length);
    }

    /** Envía con un tiempo máximo para toda la respuesta, cuerpo incluido. */
    private HttpResponse<byte[]> enviar(HttpRequest peticion) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> futura = http.sendAsync(peticion, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return futura.get(propiedades.tiempoRespuesta().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            futura.cancel(true);
            throw new FeedException("Tiempo agotado (" + propiedades.tiempoRespuesta().toMillis() + " ms)");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            futura.cancel(true);
            throw e;
        }
    }
}
