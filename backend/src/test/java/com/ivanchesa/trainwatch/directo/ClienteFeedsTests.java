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

/**
 * El cliente de los feeds contra un servidor HTTP local: peticiones condicionales con el {@code ETag} confirmado tras
 * procesar, reintentos con espera creciente, circuit breaker (abierto, semiabierto y cerrado), tiempo máximo con el
 * cuerpo incluido y tamaño máximo.
 */
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
        return cliente(reintentos, tiempoRespuesta, Duration.ofMillis(10), Duration.ofSeconds(60), 5_000_000);
    }

    ClienteFeeds cliente(int reintentos, Duration tiempoRespuesta, Duration esperaReintento, Duration circuitoAbierto,
            int tamanoMaximo) {
        URI url = renfe.url(RUTA);
        DirectoProperties p = new DirectoProperties(false, Duration.ofSeconds(7), url, url, url, Duration.ofSeconds(1),
                tiempoRespuesta, tamanoMaximo, reintentos, esperaReintento, 50, 10, circuitoAbierto, 3, 0.8,
                Duration.ofHours(48), Duration.ofHours(1), Duration.ofSeconds(5));
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
        cliente.confirmar(primera); // la ingesta la ha procesado

        Descarga segunda = cliente.descargar(Feed.POSICIONES);
        assertThat(segunda.estado()).isEqualTo(304);
        assertThat(segunda.nueva()).isFalse();
        assertThat(segunda.muestra()).isNull();
        // La primera sin condición; la segunda, con el ETag de la primera.
        assertThat(renfe.ifNoneMatch.getFirst()).isEmpty();
        assertThat(renfe.ifNoneMatch.get(1)).startsWith("\"");
    }

    @Test
    void sinConfirmarLaMuestraVuelveATraerse() throws IOException {
        // Fase 3b: si el procesado falla, la ingesta no confirma y la misma muestra se puede volver a procesar.
        ClienteFeeds cliente = cliente(3, Duration.ofSeconds(2));
        assertThat(cliente.descargar(Feed.POSICIONES).nueva()).isTrue();

        Descarga otra = cliente.descargar(Feed.POSICIONES);
        assertThat(otra.estado()).isEqualTo(200);
        assertThat(otra.nueva()).isTrue();
        assertThat(otra.muestra().getEntityCount()).isEqualTo(337);
        assertThat(renfe.ifNoneMatch).containsExactly("", "");

        cliente.confirmar(otra);
        assertThat(cliente.descargar(Feed.POSICIONES).estado()).isEqualTo(304);
    }

    @Test
    void reintentaConEsperaCrecienteYSeRecupera() throws IOException {
        renfe.fallosPendientes.set(2);
        Descarga d = cliente(3, Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(60), 5_000_000)
                .descargar(Feed.POSICIONES);
        assertThat(d.estado()).isEqualTo(200);
        assertThat(renfe.peticiones.get()).isEqualTo(3);
        // La espera se dobla: 200 ms antes del segundo intento y 400 ms antes del tercero.
        Duration primera = Duration.ofNanos(renfe.llegadas.get(1) - renfe.llegadas.get(0));
        Duration segunda = Duration.ofNanos(renfe.llegadas.get(2) - renfe.llegadas.get(1));
        // Cotas superiores holgadas: en una máquina cargada el planificador puede tardar algo más.
        assertThat(primera).isBetween(Duration.ofMillis(200), Duration.ofMillis(1_000));
        assertThat(segunda).isBetween(Duration.ofMillis(400), Duration.ofMillis(1_500));
        assertThat(segunda).isGreaterThan(primera);
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
    void trasLaEsperaElCircuitoDejaPasarUnaPruebaYSiVaBienSeCierra() throws Exception {
        renfe.fallosPendientes.set(-1);
        ClienteFeeds cliente = cliente(1, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofMillis(300),
                5_000_000);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cliente.descargar(Feed.POSICIONES)).isInstanceOf(ClienteFeeds.FeedException.class);
        }
        assertThat(cliente.estadoCircuito(Feed.POSICIONES)).isEqualTo(CircuitBreaker.State.OPEN);

        // Pasada la espera, la llamada de prueba (semiabierto) falla: se vuelve a abrir.
        Thread.sleep(400);
        assertThatThrownBy(() -> cliente.descargar(Feed.POSICIONES)).isInstanceOf(ClienteFeeds.FeedException.class);
        assertThat(cliente.estadoCircuito(Feed.POSICIONES)).isEqualTo(CircuitBreaker.State.OPEN);

        // Otra espera; Renfe ya funciona: la llamada de prueba va bien y el circuito se cierra.
        renfe.fallosPendientes.set(0);
        Thread.sleep(400);
        int antes = renfe.peticiones.get();
        assertThat(cliente.descargar(Feed.POSICIONES).estado()).isEqualTo(200);
        assertThat(renfe.peticiones.get()).isEqualTo(antes + 1);
        assertThat(cliente.estadoCircuito(Feed.POSICIONES)).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cliente.descargar(Feed.POSICIONES).estado()).isEqualTo(200);
    }

    @Test
    void unServidorQueNoContestaSeCortaPorTiempo() {
        renfe.esperaMs = 2_000;
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente(1, Duration.ofMillis(300)).descargar(Feed.POSICIONES))
                .isInstanceOf(IOException.class); // HttpTimeoutException o FeedException, según qué plazo salte antes
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void unCuerpoQueSeParaAMitadTambienSeCortaPorTiempo() {
        // Las cabeceras llegan al momento y el cuerpo se para a la mitad: HttpRequest.timeout ya no protege (solo
        // cubre hasta las cabeceras); lo corta el plazo total de la petición.
        renfe.pausaEnCuerpoMs = 2_000;
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente(1, Duration.ofMillis(300)).descargar(Feed.POSICIONES))
                .isInstanceOf(ClienteFeeds.FeedException.class).hasMessageContaining("Tiempo agotado");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void unContentLengthDemasiadoGrandeSeRechazaSinLeer() {
        assertThatThrownBy(() -> cliente(1, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(60),
                muestra.length - 1).descargar(Feed.POSICIONES))
                .isInstanceOf(ClienteFeeds.FeedException.class).hasMessageContaining("Content-Length");
    }

    @Test
    void sinContentLengthSeCortaLaLecturaAlPasarElMaximo() {
        renfe.porTrozos = true;
        assertThatThrownBy(() -> cliente(1, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(60),
                muestra.length / 2).descargar(Feed.POSICIONES))
                .isInstanceOf(ClienteFeeds.FeedException.class).hasMessageContaining("se corta la lectura");
    }

    @Test
    void porTrozosYDentroDelMaximoSeLeeEntera() throws IOException {
        renfe.porTrozos = true;
        Descarga d = cliente(1, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(60), muestra.length)
                .descargar(Feed.POSICIONES);
        assertThat(d.bytes()).isEqualTo(muestra.length);
        assertThat(d.muestra().getEntityCount()).isEqualTo(337);
    }
}
