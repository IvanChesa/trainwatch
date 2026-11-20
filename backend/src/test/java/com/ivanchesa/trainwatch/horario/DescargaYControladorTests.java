package com.ivanchesa.trainwatch.horario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Pruebas sin Spring ni base de datos: la descarga contra el servidor de prueba y las reglas del controlador. */
class DescargaYControladorTests {

    static final ServidorGtfs RENFE = new ServidorGtfs();

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    static DescargaHorario descarga(Duration tiempoMaximo) {
        return new DescargaHorario(new HorarioProperties(RENFE.url(), "-", false, "", 10_000, 0.5, 200, 25, tiempoMaximo));
    }

    @Test
    void unaDescargaAtascadaFallaAlAgotarElTiempoTotal() {
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), "\"a\"");
        RENFE.atascado = true;
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> descarga(Duration.ofSeconds(1)).descargar("", "", false))
                    .isInstanceOf(IOException.class).hasMessageContaining("no terminó");
            // El servidor sigue parado 15 s: la descarga no le espera.
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        } finally {
            RENFE.atascado = false;
        }
    }

    @Test
    void unZipConContentLengthDemasiadoGrandeSeRechazaSinLeerlo() {
        byte[] zip = ServidorGtfs.zipDeEjemplo();
        RENFE.sirve(zip, "\"grande\"");
        var props = new HorarioProperties(RENFE.url(), "-", false, "", 10_000, 0.5, 200, 25, Duration.ofSeconds(10));
        assertThatThrownBy(() -> new DescargaHorario(props, zip.length - 1).descargar("", "", false))
                .isInstanceOf(IOException.class).hasMessageContaining("tamaño máximo").hasMessageContaining("Content-Length");
    }

    @Test
    void sinContentLengthSeCortaLaDescargaAlPasarDelMaximo() throws Exception {
        byte[] zip = ServidorGtfs.zipDeEjemplo();
        RENFE.sirve(zip, "\"trozos\"");
        RENFE.porTrozos = true;
        try {
            var props = new HorarioProperties(RENFE.url(), "-", false, "", 10_000, 0.5, 200, 25, Duration.ofSeconds(10));
            assertThatThrownBy(() -> new DescargaHorario(props, zip.length / 2).descargar("", "", false))
                    .isInstanceOf(IOException.class).hasMessageContaining("se corta la lectura");
            // Dentro del máximo, por trozos también se descarga entero.
            var r = new DescargaHorario(props, zip.length).descargar("", "", false);
            assertThat(r.bytes()).isEqualTo(zip.length);
            java.nio.file.Files.delete(r.zip());
        } finally {
            RENFE.porTrozos = false;
        }
    }

    @Test
    void un200ConElMismoEtagCuentaComoSinCambios() throws Exception {
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), "\"b\"");
        RENFE.ignoraCondicionales = true;
        try {
            var r = descarga(Duration.ofSeconds(10)).descargar("\"b\"", "", false);
            assertThat(r.cambiado()).isFalse();
            assertThat(r.zip()).isNull();
            // Forzada, sí descarga.
            var forzada = descarga(Duration.ofSeconds(10)).descargar("\"b\"", "", true);
            assertThat(forzada.cambiado()).isTrue();
            java.nio.file.Files.delete(forzada.zip());
        } finally {
            RENFE.ignoraCondicionales = false;
        }
    }

    @Test
    void sinTokenConfiguradoElDisparoManualNoExiste() {
        var props = new HorarioProperties(RENFE.url(), "-", false, "", 10_000, 0.5, 200, 25, Duration.ofSeconds(1));
        var controlador = new HorarioController(null, null, props);
        assertThat(controlador.importar("cualquiera", false).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void elEstadoPublicoSoloEnsenaLosErroresDelFichero() {
        assertThat(HorarioController.errorPublico(null)).isNull();
        assertThat(HorarioController.errorPublico(
                "Step failed ← GtfsInvalidoException: stop_times.txt, línea 7: arrival_time no es una hora: 12:7X:00"))
                .isEqualTo("Horario de Renfe no válido: stop_times.txt, línea 7: arrival_time no es una hora: 12:7X:00");
        assertThat(HorarioController.errorPublico("PSQLException: ERROR: relation \"x\" does not exist, /tmp/a.zip"))
                .doesNotContain("PSQL").doesNotContain("/tmp").startsWith("Error interno");
    }
}
