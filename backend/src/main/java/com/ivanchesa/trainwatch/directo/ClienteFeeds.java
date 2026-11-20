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
import com.ivanchesa.trainwatch.CuerpoLimitado;

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
 * <li><b>Peticiones condicionales.</b> Se guarda el {@code ETag} y el {@code Last-Modified} de la última muestra
 * <i>procesada</i> de cada feed y se mandan como {@code If-None-Match} e {@code If-Modified-Since}. Si no hay nada
 * nuevo, Renfe contesta 304 sin cuerpo: se puede preguntar cada 5-10 s sin bajar ~30 KB cada vez. Un 200 con el
 * mismo {@code ETag} también cuenta como sin cambios. Los validadores no se guardan al descargar, sino cuando la
 * ingesta llama a {@link #confirmar} tras procesar la muestra (fase 3b): si el procesado falla, la siguiente petición
 * vuelve a traer la misma muestra.</li>
 * <li><b>Tamaño máximo</b> mirando {@code Content-Length} y cortando la lectura al pasarlo ({@link CuerpoLimitado}).</li>
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
     * @param muestra      el feed decodificado, o {@code null} si no había nada nuevo (304 o el mismo ETag)
     * @param estado       código HTTP
     * @param etag         {@code ETag} de la respuesta, para {@link #confirmar}
     * @param lastModified {@code Last-Modified} de la respuesta, para {@link #confirmar}
     */
    public record Descarga(Feed feed, FeedMessage muestra, int estado, int bytes, String etag, String lastModified) {

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
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // el apagado de la aplicacion tiene que ver la interrupcion
            }
            throw new IOException(e);
        } catch (Error e) {
            throw e; // OutOfMemoryError y compañía no son un fallo de red: que suban (fase 3b)
        } catch (Throwable e) {
            throw new IllegalStateException(e); // ni Exception ni Error: no debería pasar
        }
    }

    /**
     * Guarda los validadores de una descarga ya procesada: la siguiente petición los manda y, si no hay nada nuevo,
     * recibe un 304. Si no se llama (el procesado falló), la siguiente petición vuelve a traer la muestra.
     */
    public void confirmar(Descarga descarga) {
        if (descarga.muestra() != null) {
            validadores.put(descarga.feed(), new Validadores(descarga.etag(), descarga.lastModified()));
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
            return new Descarga(feed, null, 304, 0, null, null);
        }
        if (estado != 200) {
            throw new FeedException("El feed " + feed.etiqueta() + " ha contestado " + estado);
        }
        byte[] cuerpo = respuesta.body();
        String etag = respuesta.headers().firstValue("ETag").orElse(null);
        if (etag != null && previos != null && etag.equals(previos.etag())) {
            return new Descarga(feed, null, 200, cuerpo.length, null, null);
        }
        FeedMessage muestra = FeedMessage.parseFrom(cuerpo); // InvalidProtocolBufferException es una IOException
        return new Descarga(feed, muestra, 200, cuerpo.length, etag,
                respuesta.headers().firstValue("Last-Modified").orElse(null));
    }

    /** Envía con un tiempo máximo para toda la respuesta, cuerpo incluido. */
    private HttpResponse<byte[]> enviar(HttpRequest peticion) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> futura = http.sendAsync(peticion,
                CuerpoLimitado.de(HttpResponse.BodyHandlers.ofByteArray(), propiedades.tamanoMaximo()));
        try {
            return futura.get(propiedades.tiempoRespuesta().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            futura.cancel(true);
            throw new FeedException("Tiempo agotado (" + propiedades.tiempoRespuesta().toMillis() + " ms)");
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof Error error) {
                throw error; // un OutOfMemoryError en el hilo del HttpClient no es un fallo de red
            }
            if (causa instanceof CuerpoLimitado.CuerpoDemasiadoGrande grande) {
                throw new FeedException("Feed demasiado grande: " + grande.getMessage());
            }
            if (causa instanceof IOException io && io.getCause() instanceof CuerpoLimitado.CuerpoDemasiadoGrande grande) {
                throw new FeedException("Feed demasiado grande: " + grande.getMessage());
            }
            throw causa instanceof IOException io ? io : new IOException(causa);
        } catch (InterruptedException e) {
            futura.cancel(true);
            throw e;
        }
    }
}
