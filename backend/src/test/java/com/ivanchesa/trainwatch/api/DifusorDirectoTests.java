package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ivanchesa.trainwatch.directo.EstadoDirecto;
import com.ivanchesa.trainwatch.directo.EstadoTren;
import com.ivanchesa.trainwatch.directo.Tren;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El difusor del directo sin servidor: un cliente que no lee no puede bloquear a quien publica (la ingesta), y
 * durante el apagado no se aceptan conexiones. También el directo de todos los núcleos (fase 5b).
 */
class DifusorDirectoTests {

    static final int COLA = 3;

    /**
     * Un emisor cuyo envío se queda atascado, como un socket con la ventana TCP llena. Igual que en Spring 7.1,
     * {@code complete()} espera al mismo cerrojo que un {@code send} en curso.
     */
    static class EmisorAtascado extends SseEmitter {
        final ReentrantLock cerrojo = new ReentrantLock();
        final CountDownLatch enviando = new CountDownLatch(1);
        final CountDownLatch liberar = new CountDownLatch(1);
        final CountDownLatch completado = new CountDownLatch(1);

        EmisorAtascado(long tiempo) {
            super(tiempo);
        }

        @Override
        public void send(Set<DataWithMediaType> datos) throws IOException {
            cerrojo.lock();
            try {
                enviando.countDown();
                liberar.await();
                throw new IOException("El cliente se ha ido");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cerrojo.unlock();
            }
        }

        @Override
        public void complete() {
            cerrojo.lock();
            try {
                completado.countDown();
            } finally {
                cerrojo.unlock();
            }
        }
    }

    final EstadoDirecto estado = Mockito.mock(EstadoDirecto.class);
    final DifusorDirecto difusor = new DifusorDirecto(estado,
            new ApiProperties(List.of("http://localhost:5173"), new ApiProperties.Directo(10, Duration.ofHours(1),
                    Duration.ofMinutes(30), COLA, Duration.ofSeconds(3))),
            JsonMapper.builder().build(), Clock.systemUTC(), new SimpleMeterRegistry());

    static List<Tren> trenesEn(double lat) {
        return List.of(tren("51T1", "R4", 51, lat));
    }

    static Tren tren(String id, String linea, int nucleo, double lat) {
        return new Tren(id, id, "v-" + id, linea, nucleo, null, LocalDate.of(2026, 11, 20), EstadoTren.EN_MARCHA, null,
                -1, "IN_TRANSIT_TO", lat, 2.0, Instant.parse("2026-11-20T09:00:00Z"), 60, null, 0);
    }

    /** Un emisor que apunta lo que se le manda: el nombre y los datos de cada evento. */
    static class EmisorQueApunta extends SseEmitter {
        record Evento(String nombre, String datos) {
        }

        final BlockingQueue<Evento> eventos = new LinkedBlockingQueue<>();

        EmisorQueApunta(long tiempo) {
            super(tiempo);
        }

        @Override
        public void send(Set<DataWithMediaType> datos) throws IOException {
            ByteArrayOutputStream texto = new ByteArrayOutputStream();
            for (DataWithMediaType d : datos) {
                texto.write(d.getData() instanceof byte[] b ? b : d.getData().toString().getBytes(StandardCharsets.UTF_8));
            }
            String nombre = null;
            String cuerpo = null;
            for (String linea : texto.toString(StandardCharsets.UTF_8).split("\n")) {
                if (linea.startsWith("event:")) {
                    nombre = linea.substring(6);
                } else if (linea.startsWith("data:")) {
                    cuerpo = linea.substring(5);
                }
            }
            eventos.add(new Evento(nombre, cuerpo));
        }

        Evento siguiente() throws InterruptedException {
            return eventos.poll(5, TimeUnit.SECONDS);
        }
    }

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void unClienteQueNoLeeNoBloqueaLaPublicacionYSeLeDesconecta() throws InterruptedException {
        EmisorAtascado[] emisor = new EmisorAtascado[1];
        difusor.fabricaEmisores = t -> emisor[0] = new EmisorAtascado(t);
        difusor.suscribir(51, null);
        // Su hilo está atascado enviando la instantánea, con el cerrojo del emisor cogido.
        assertThat(emisor[0].enviando.await(5, TimeUnit.SECONDS)).isTrue();

        // La ingesta publica más cambios de los que caben en su cola: no se bloquea y lo desconecta.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i <= COLA + 1; i++) {
                difusor.publicar(trenesEn(41.0 + i / 100.0), Instant.now(), Instant.now());
            }
        });
        assertThat(difusor.conexiones()).isZero();
        assertThat(emisor[0].completado.getCount()).as("aún no se ha cerrado la respuesta").isEqualTo(1);

        // Cuando el envío termina (falla), el propio hilo de la conexión cierra la respuesta.
        emisor[0].liberar.countDown();
        assertThat(emisor[0].completado.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void elLatidoNoBloqueaConUnClienteAtascado() throws InterruptedException {
        EmisorAtascado[] emisor = new EmisorAtascado[1];
        difusor.fabricaEmisores = t -> emisor[0] = new EmisorAtascado(t);
        difusor.suscribir(51, null);
        assertThat(emisor[0].enviando.await(5, TimeUnit.SECONDS)).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i <= COLA + 1; i++) {
                difusor.latir();
            }
        });
        assertThat(difusor.conexiones()).isZero();
        emisor[0].liberar.countDown();
    }

    @Test
    void elDirectoDeTodosLlevaLosTrenesDeTodosLosNucleosYSusCambios() throws InterruptedException {
        Instant dato = Instant.parse("2026-11-20T09:00:00Z");
        difusor.publicar(List.of(tren("51T1", "R4", 51, 41.4), tren("10T1", "C5", 10, 40.4)), Instant.now(), dato);
        List<EmisorQueApunta> emisores = new ArrayList<>();
        difusor.fabricaEmisores = t -> {
            EmisorQueApunta e = new EmisorQueApunta(t);
            emisores.add(e);
            return e;
        };
        difusor.suscribirTodos(null);
        difusor.suscribir(51, null);
        EmisorQueApunta todos = emisores.get(0);
        EmisorQueApunta rodalies = emisores.get(1);

        EmisorQueApunta.Evento instantanea = todos.siguiente();
        assertThat(instantanea.nombre()).isEqualTo("instantanea");
        JsonNode foto = json.readTree(instantanea.datos());
        assertThat(foto.get("nucleo").isNull()).isTrue();
        assertThat(foto.get("trenes").valueStream().map(t -> t.get("id").asString())).containsExactly("10T1", "51T1");
        assertThat(json.readTree(rodalies.siguiente().datos()).get("trenes")).hasSize(1);

        // Cambia el tren de Madrid: al directo de todos le llega; al de Rodalies, nada.
        difusor.publicar(List.of(tren("51T1", "R4", 51, 41.4), tren("10T1", "C5", 10, 40.5)), Instant.now(), dato);
        EmisorQueApunta.Evento cambios = todos.siguiente();
        assertThat(cambios.nombre()).isEqualTo("cambios");
        JsonNode c = json.readTree(cambios.datos());
        assertThat(c.get("nucleo").isNull()).isTrue();
        assertThat(c.get("secuencia").asLong()).isEqualTo(foto.get("secuencia").asLong() + 1);
        assertThat(c.get("cambios").valueStream().map(t -> t.get("id").asString())).containsExactly("10T1");
        assertThat(rodalies.eventos.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Un tren que se va de un núcleo se va también de todos.
        difusor.publicar(List.of(tren("10T1", "C5", 10, 40.5)), Instant.now(), dato);
        assertThat(json.readTree(todos.siguiente().datos()).get("bajas").valueStream().map(JsonNode::asString))
                .containsExactly("51T1");

        // «Todos» no es un núcleo: ni lo conoce la API de núcleos ni tiene trenes propios.
        assertThat(difusor.conoce(0)).isFalse();
        assertThat(difusor.trenes(0)).isEmpty();
        assertThat(difusor.conexiones()).isEqualTo(2);
    }

    @Test
    void unClienteDeTodosQueNoLeeTampocoBloqueaLaPublicacion() throws InterruptedException {
        EmisorAtascado[] emisor = new EmisorAtascado[1];
        difusor.fabricaEmisores = t -> emisor[0] = new EmisorAtascado(t);
        difusor.suscribirTodos(null);
        assertThat(emisor[0].enviando.await(5, TimeUnit.SECONDS)).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i <= COLA + 1; i++) {
                difusor.publicar(trenesEn(41.0 + i / 100.0), Instant.now(), Instant.now());
            }
        });
        assertThat(difusor.conexiones()).isZero();
        emisor[0].liberar.countDown();
        assertThat(emisor[0].completado.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void elMaximoDeConexionesCuentaTambienLasDeTodos() {
        difusor.fabricaEmisores = EmisorQueApunta::new;
        for (int i = 0; i < 5; i++) {
            difusor.suscribirTodos(null);
            difusor.suscribir(51, null);
        }
        assertThat(difusor.conexiones()).isEqualTo(10);
        assertThatThrownBy(() -> difusor.suscribirTodos(null)).isInstanceOfSatisfying(ErrorResponseException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThat(difusor.conexiones()).isEqualTo(10);
    }

    @Test
    void alApagarseNoAceptaConexionesNuevas() {
        difusor.stop();
        assertThatThrownBy(() -> difusor.suscribir(51, null)).isInstanceOfSatisfying(ErrorResponseException.class,
                e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("5");
                });
        assertThatThrownBy(() -> difusor.suscribirTodos(null)).isInstanceOf(ErrorResponseException.class);
        assertThat(difusor.conexiones()).isZero();
    }
}
