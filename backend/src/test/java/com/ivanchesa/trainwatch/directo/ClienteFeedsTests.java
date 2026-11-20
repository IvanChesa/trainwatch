package com.ivanchesa.trainwatch.directo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ivanchesa.trainwatch.directo.ClienteFeeds.Descarga;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** El cliente de los feeds contra un servidor HTTP local: peticiones condicionales, reintentos y circuit breaker. */
class ClienteFeedsTests {

    static final String RUTA = "/vehicle_positions.pb";

    ServidorFeeds renfe;
    byte[] muestra;

    @BeforeEach
    void arrancar() throws IOException {
        renfe = new ServidorFeeds();
        try (InputStream in = getClass().getResourceAsStream("/gtfsrt/20261120T092815Z_vehicle_positions.pb")) {
            muestra = in.readAllBytes();
        }
        renfe.servir(RUTA, muestra);
    }

    @AfterEach
    void parar() {
        renfe.close();
    }

    ClienteFeeds cliente(int reintentos, Duration tiempoRespuesta) {
        URI url = renfe.url(RUTA);
        DirectoProperties p = new DirectoProperties(false, Duration.ofSeconds(7), url, url, url, Duration.ofSeconds(1),
                tiempoRespuesta, 5_000_000, reintentos, Duration.ofMillis(10), 50, 10, Duration.ofSeconds(60), 3, 0.8,
                Duration.ofHours(48));
        return new ClienteFeeds(p, new SimpleMeterRegistry());
    }

    @Test
    void sinCambiosElServidorContesta304YNoHayNadaQueProcesar() throws IOException {
        ClienteFeeds cliente = cliente(3, Duration.ofSeconds(2));

        Descarga primera = cliente.descargar(Feed.POSICIONES);
        assertThat(primera.estado()).isEqualTo(200);
        assertThat(primera.nueva()).isTrue();
        assertThat(primera.muestra().getEntityCount()).isEqualTo(337);
        assertThat(primera.bytes()).isEqualTo(muestra.length);

        Descarga segunda = cliente.descargar(Feed.POSICIONES);
        assertThat(segunda.estado()).isEqualTo(304);
        assertThat(segunda.nueva()).isFalse();
        assertThat(segunda.muestra()).isNull();
        // La primera sin condición; la segunda, con el ETag de la primera.
        assertThat(renfe.ifNoneMatch.getFirst()).isEmpty();
        assertThat(renfe.ifNoneMatch.get(1)).startsWith("\"");
    }

    @Test
    void reintentaConEsperaYSeRecupera() throws IOException {
        renfe.fallosPendientes.set(2);
        Descarga d = cliente(3, Duration.ofSeconds(2)).descargar(Feed.POSICIONES);
        assertThat(d.estado()).isEqualTo(200);
        assertThat(renfe.peticiones.get()).isEqualTo(3);
    }

    @Test
    void elCircuitoSeAbreTrasVariosFallosYDejaDeLlamar() {
        renfe.fallosPendientes.set(-1);
        ClienteFeeds cliente = cliente(3, Duration.ofSeconds(2));

        // Primera descarga: 3 intentos fallidos. Segunda: 2 más y, con 5 fallos de 5, el circuito se abre.
        assertThatThrownBy(() -> cliente.descargar(Feed.POSICIONES)).isInstanceOf(ClienteFeeds.FeedException.class);
        assertThat(cliente.estadoCircuito(Feed.POSICIONES)).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatThrownBy(() -> cliente.descargar(Feed.POSICIONES)).isInstanceOf(CallNotPermittedException.class);
        assertThat(cliente.estadoCircuito(Feed.POSICIONES)).isEqualTo(CircuitBreaker.State.OPEN);
        int llamadas = renfe.peticiones.get();
        assertThat(llamadas).isEqualTo(5);

        // Abierto: falla al momento, sin llamar a Renfe, aunque Renfe ya funcione.
        renfe.fallosPendientes.set(0);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cliente.descargar(Feed.POSICIONES)).isInstanceOf(CallNotPermittedException.class);
        }
        assertThat(renfe.peticiones.get()).isEqualTo(llamadas);
        // Cada feed tiene su circuito: los otros siguen cerrados.
        assertThat(cliente.estadoCircuito(Feed.RETRASOS)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unServidorQueNoContestaSeCortaPorTiempo() {
        renfe.esperaMs = 2_000;
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente(1, Duration.ofMillis(300)).descargar(Feed.POSICIONES))
                .isInstanceOf(ClienteFeeds.FeedException.class).hasMessageContaining("Tiempo agotado");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1_500));
    }
}
