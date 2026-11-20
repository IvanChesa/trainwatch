package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * El difusor del directo sin servidor: un cliente que no lee no puede bloquear a quien publica (la ingesta), y
 * durante el apagado no se aceptan conexiones.
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
        return List.of(new Tren("51T1", "51T1", "v1", "R4", 51, null, LocalDate.of(2026, 11, 20), EstadoTren.EN_MARCHA,
                null, -1, "IN_TRANSIT_TO", lat, 2.0, Instant.parse("2026-11-20T09:00:00Z"), 60, null, 0));
    }

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
    void alApagarseNoAceptaConexionesNuevas() {
        difusor.stop();
        assertThatThrownBy(() -> difusor.suscribir(51, null)).isInstanceOfSatisfying(ErrorResponseException.class,
                e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("5");
                });
        assertThat(difusor.conexiones()).isZero();
    }
}
