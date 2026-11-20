package com.ivanchesa.trainwatch.api;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ivanchesa.trainwatch.api.Respuestas.Cambios;
import com.ivanchesa.trainwatch.api.Respuestas.Instantanea;
import com.ivanchesa.trainwatch.api.Respuestas.Latido;
import com.ivanchesa.trainwatch.api.Respuestas.TrenMapa;
import com.ivanchesa.trainwatch.directo.CicloDirectoTerminado;
import com.ivanchesa.trainwatch.directo.EstadoDirecto;
import com.ivanchesa.trainwatch.directo.Tren;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * El directo por SSE ({@code GET /api/directo?nucleo=51}, ADR 0015).
 *
 * <p><b>Qué manda.</b> Al conectarse, un evento {@code instantanea} con todos los trenes del núcleo. Después, al final
 * de cada ciclo de la ingesta ({@link CicloDirectoTerminado}, cada ~7 s), un evento {@code cambios} con las altas,
 * los trenes que han cambiado y las bajas, si hay alguno. Cada {@code latido} (~15 s), un evento {@code latido} con la
 * antigüedad del último dato. Las instantáneas y los cambios llevan como {@code id} su número de secuencia.
 *
 * <p><b>Coherencia.</b> Cada núcleo es un {@link Canal} con su foto de los trenes y su secuencia. Calcular los cambios,
 * guardar la foto nueva y encolarlos a los suscriptores se hace dentro del cerrojo del canal; dar de alta a un
 * suscriptor (encolar su instantánea y apuntarlo), también. Así nadie recibe la instantánea {@code n} y luego los
 * cambios de {@code n} otra vez, ni se pierde los de {@code n+1}.
 *
 * <p><b>Carga.</b> El hilo de la ingesta no escribe nunca en un socket: solo calcula los cambios una vez por núcleo,
 * los serializa <i>una vez</i> a JSON y los deja en la cola de cada suscriptor (sin bloquear). Cada suscriptor tiene
 * un hilo virtual que vacía su cola hacia su conexión: un cliente lento solo se retrasa a sí mismo. Si su cola se
 * llena ({@code cola-maxima}), se le desconecta; al reconectarse recibe una instantánea nueva. Hay un máximo de
 * conexiones ({@code maximo-conexiones}): la siguiente recibe un 503 con {@code Retry-After}. Cada conexión dura como
 * mucho {@code duracion-maxima}; después el navegador se reconecta solo.
 *
 * <p><b>{@code Last-Event-ID}.</b> Se lee, pero la reconexión recibe siempre una instantánea nueva, no los cambios que
 * se perdió: casi todos los trenes en marcha cambian en cada ciclo, así que reenviar los cambios perdidos ocuparía
 * tanto o más que la instantánea y obligaría a guardar un historial (ADR 0015).
 */
@Component
public class DifusorDirecto implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DifusorDirecto.class);
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final EstadoDirecto estado;
    private final ApiProperties.Directo config;
    private final JsonMapper json;
    private final Clock reloj;
    private final MeterRegistry metricas;

    private final Map<Integer, Canal> canales = new ConcurrentHashMap<>();
    private final Set<Suscriptor> suscriptores = ConcurrentHashMap.newKeySet();
    private final AtomicInteger conexiones = new AtomicInteger();
    private final AtomicLong numeroHilo = new AtomicLong();
    private ScheduledExecutorService latidos;
    private volatile boolean enMarcha;

    /** Un núcleo: la última foto de sus trenes, su secuencia y quién lo escucha. Se usa con su propio cerrojo. */
    private static final class Canal {
        final int nucleo;
        long secuencia;
        Map<String, TrenMapa> trenes = Map.of();
        Instant generado;
        Instant ultimoDato;
        /** La instantánea ya serializada, para no serializarla una vez por cliente; se borra al cambiar la foto. */
        byte[] instantanea;
        final Set<Suscriptor> suscriptores = ConcurrentHashMap.newKeySet();

        Canal(int nucleo) {
            this.nucleo = nucleo;
        }
    }

    /** Una conexión SSE: su cola de eventos y el hilo virtual que la vacía. */
    private final class Suscriptor {
        /** Marca de fin en la cola: el hilo termina al sacarla. */
        private static final Set<DataWithMediaType> FIN = Set.of();

        final SseEmitter emitter;
        final Canal canal;
        final BlockingQueue<Set<DataWithMediaType>> cola;
        final AtomicBoolean cerrado = new AtomicBoolean();

        Suscriptor(SseEmitter emitter, Canal canal) {
            this.emitter = emitter;
            this.canal = canal;
            this.cola = new ArrayBlockingQueue<>(config.colaMaxima() + 1);
        }

        /** Deja un evento en la cola sin bloquear. Si está llena (cliente lento), se le desconecta. */
        void encolar(Set<DataWithMediaType> evento) {
            if (cerrado.get()) {
                return;
            }
            if (cola.size() >= config.colaMaxima() || !cola.offer(evento)) {
                metricas.counter("trainwatch.api.directo.desconectados", "motivo", "lento").increment();
                log.info("Directo: cliente lento en el núcleo {} ({} eventos esperando); se le desconecta",
                        canal.nucleo, cola.size());
                cerrar();
            }
        }

        void enviarHastaCerrar() {
            try {
                while (true) {
                    Set<DataWithMediaType> evento = cola.take();
                    if (evento == FIN || cerrado.get()) {
                        return;
                    }
                    emitter.send(evento);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // El cliente se ha ido (o la conexión se ha cerrado por tiempo): no es un error de la aplicación.
                log.debug("Directo: no se pudo enviar al núcleo {}: {}", canal.nucleo, e.toString());
            } finally {
                cerrar();
            }
        }

        /** Se puede llamar varias veces y desde cualquier hilo: solo la primera hace algo. */
        void cerrar() {
            if (!cerrado.compareAndSet(false, true)) {
                return;
            }
            canal.suscriptores.remove(this);
            suscriptores.remove(this);
            conexiones.decrementAndGet();
            cola.clear();
            cola.offer(FIN); // despierta al hilo si espera en take(); no se le interrumpe a mitad de escribir
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Directo: al cerrar la conexión: {}", e.toString());
            }
        }
    }

    public DifusorDirecto(EstadoDirecto estado, ApiProperties propiedades, JsonMapper json, Clock reloj,
            MeterRegistry metricas) {
        this.estado = estado;
        this.config = propiedades.directo();
        this.json = json;
        this.reloj = reloj;
        this.metricas = metricas;
        Gauge.builder("trainwatch.api.directo.conexiones", conexiones, AtomicInteger::get)
                .description("Conexiones SSE abiertas al directo").register(metricas);
    }

    // ---------------------------------------------------------------- conexiones

    /**
     * Abre una conexión al directo de un núcleo. El núcleo ya viene comprobado por el controlador (si no, cualquiera
     * podría crear canales sin fin).
     *
     * @param ultimoId el {@code Last-Event-ID} que manda el navegador al reconectarse, o {@code null}
     * @throws org.springframework.web.ErrorResponseException 503 si ya hay {@code maximo-conexiones} abiertas
     */
    public SseEmitter suscribir(int nucleo, String ultimoId) {
        if (conexiones.incrementAndGet() > config.maximoConexiones()) {
            conexiones.decrementAndGet();
            metricas.counter("trainwatch.api.directo.rechazadas").increment();
            throw Problemas.directoCompleto(config.maximoConexiones(), 30);
        }
        if (ultimoId != null) {
            metricas.counter("trainwatch.api.directo.reconexiones").increment();
        }
        Canal canal = canales.computeIfAbsent(nucleo, Canal::new);
        SseEmitter emitter = new SseEmitter(config.duracionMaxima().toMillis());
        Suscriptor s = new Suscriptor(emitter, canal);
        emitter.onCompletion(s::cerrar);
        emitter.onTimeout(s::cerrar);
        emitter.onError(e -> s.cerrar());
        suscriptores.add(s);
        synchronized (canal) {
            s.cola.offer(eventoInstantanea(canal));
            canal.suscriptores.add(s);
        }
        Thread.ofVirtual().name("sse-" + nucleo + "-" + numeroHilo.incrementAndGet()).start(s::enviarHastaCerrar);
        return emitter;
    }

    /** Conexiones abiertas ahora (para las pruebas y la métrica). */
    public int conexiones() {
        return conexiones.get();
    }

    /** La última foto de los trenes de un núcleo, la misma que recibe el directo; vacía si no hay ninguno. */
    public List<TrenMapa> trenes(int nucleo) {
        Canal canal = canales.get(nucleo);
        if (canal == null) {
            return List.of();
        }
        synchronized (canal) {
            return List.copyOf(canal.trenes.values());
        }
    }

    /** Si el directo conoce el núcleo (ha tenido trenes alguna vez desde que arrancó la aplicación). */
    public boolean conoce(int nucleo) {
        return canales.containsKey(nucleo);
    }

    // ---------------------------------------------------------------- publicación

    @EventListener
    public void alTerminarCiclo(CicloDirectoTerminado ciclo) {
        publicar(estado.trenes(), ciclo.instante(), estado.ultimaMuestraPosiciones());
    }

    /**
     * Calcula la foto nueva de cada núcleo, la compara con la anterior y encola los cambios a sus suscriptores. Lo
     * llama el hilo de la ingesta: no bloquea (solo encola).
     */
    void publicar(List<Tren> trenes, Instant ahora, Instant ultimoDato) {
        Map<Integer, Map<String, TrenMapa>> porNucleo = new HashMap<>();
        for (Tren t : trenes) {
            if (t.nucleo() != null) {
                porNucleo.computeIfAbsent(t.nucleo(), k -> new TreeMap<>()).put(t.id(), VistaTrenes.mapa(t));
            }
        }
        Set<Integer> nucleos = new HashSet<>(canales.keySet());
        nucleos.addAll(porNucleo.keySet());
        for (Integer n : nucleos) {
            Canal canal = canales.computeIfAbsent(n, Canal::new);
            Map<String, TrenMapa> nuevo = porNucleo.getOrDefault(n, Map.of());
            synchronized (canal) {
                List<TrenMapa> altas = new ArrayList<>();
                List<TrenMapa> cambios = new ArrayList<>();
                List<String> bajas = new ArrayList<>();
                for (TrenMapa t : nuevo.values()) {
                    TrenMapa antes = canal.trenes.get(t.id());
                    if (antes == null) {
                        altas.add(t);
                    } else if (!antes.equals(t)) {
                        cambios.add(t);
                    }
                }
                for (String id : canal.trenes.keySet()) {
                    if (!nuevo.containsKey(id)) {
                        bajas.add(id);
                    }
                }
                canal.trenes = nuevo;
                canal.generado = ahora;
                boolean datoNuevo = !Objects.equals(canal.ultimoDato, ultimoDato);
                canal.ultimoDato = ultimoDato;
                if (altas.isEmpty() && cambios.isEmpty() && bajas.isEmpty() && !datoNuevo) {
                    continue; // nada que contar: ni secuencia nueva ni evento
                }
                canal.secuencia++;
                canal.instantanea = null;
                if (canal.suscriptores.isEmpty()) {
                    continue;
                }
                Cambios c = new Cambios(canal.secuencia, n, VistaTrenes.hora(ahora), VistaTrenes.hora(ultimoDato), altas,
                        cambios, bajas);
                Set<DataWithMediaType> evento = SseEmitter.event().id(String.valueOf(canal.secuencia)).name("cambios")
                        .data(json.writeValueAsBytes(c), JSON).build();
                for (Suscriptor s : canal.suscriptores) {
                    s.encolar(evento);
                }
            }
        }
    }

    /** Manda un {@code latido} a todas las conexiones (serializado una vez). */
    void latir() {
        if (suscriptores.isEmpty()) {
            return;
        }
        Instant ahora = reloj.instant();
        Instant ultimo = estado.ultimaMuestraPosiciones();
        Long antiguedad = ultimo == null ? null : Duration.between(ultimo, ahora).toSeconds();
        Latido l = new Latido(VistaTrenes.hora(ahora), VistaTrenes.hora(ultimo), antiguedad);
        Set<DataWithMediaType> evento = SseEmitter.event().name("latido").data(json.writeValueAsBytes(l), JSON)
                .build();
        for (Suscriptor s : suscriptores) {
            s.encolar(evento);
        }
    }

    /** La instantánea del canal, con el {@code retry:} para el navegador (con algo de azar). Con el cerrojo cogido. */
    private Set<DataWithMediaType> eventoInstantanea(Canal canal) {
        if (canal.instantanea == null) {
            canal.instantanea = json.writeValueAsBytes(new Instantanea(canal.secuencia, canal.nucleo,
                    VistaTrenes.hora(canal.generado), VistaTrenes.hora(canal.ultimoDato),
                    List.copyOf(canal.trenes.values())));
        }
        long base = config.reintento().toMillis();
        long reintento = base + ThreadLocalRandom.current().nextLong(base + 1);
        return SseEmitter.event().id(String.valueOf(canal.secuencia)).name("instantanea").reconnectTime(reintento)
                .data(canal.instantanea, JSON).build();
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    public void start() {
        latidos = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("sse-latido").daemon()
                .factory());
        long cada = config.latido().toMillis();
        latidos.scheduleAtFixedRate(() -> {
            try {
                latir();
            } catch (RuntimeException e) {
                log.warn("Directo: no se pudo mandar el latido", e);
            }
        }, cada, cada, TimeUnit.MILLISECONDS);
        enMarcha = true;
    }

    /**
     * Cierra todas las conexiones antes de que el servidor web empiece su apagado ordenado (esta fase va antes): si
     * no, esperaría a unas peticiones que no terminan nunca.
     */
    @Override
    public void stop() {
        enMarcha = false;
        if (latidos != null) {
            latidos.shutdownNow();
        }
        for (Suscriptor s : List.copyOf(suscriptores)) {
            s.cerrar();
        }
    }

    @Override
    public boolean isRunning() {
        return enMarcha;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
