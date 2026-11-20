package com.ivanchesa.trainwatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Aceptación de la fase 1: arranca la aplicación entera contra PostGIS en Testcontainers y comprueba que Flyway
 * aplicó la migración, que PostGIS está activo y que la salud y el estado responden por HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CimientosIntegracionTests {

    @Value("${local.server.port}")
    int puerto;

    @Autowired
    JdbcClient jdbc;

    RestTestClient cliente;

    @BeforeEach
    void preparaCliente() {
        cliente = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void flywayAplicaLaPrimeraMigracion() {
        Integer correctas = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
                .query(Integer.class).single();
        assertThat(correctas).isEqualTo(1);
    }

    @Test
    void laExtensionPostgisEstaActiva() {
        String version = jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'postgis'")
                .query(String.class).single();
        assertThat(version).startsWith("3.");
    }

    @Test
    void postgisLoActivaFlywayYNoLaImagen() {
        // El contenedor arranca sin el script de inicio de la imagen (TestcontainersConfiguration): si la
        // migración no hiciera CREATE EXTENSION, la prueba anterior fallaría. Aquí se comprueba además que
        // no queda ninguna de las extensiones extra que crea ese script.
        var extensiones = jdbc.sql("SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' ORDER BY extname")
                .query(String.class).list();
        assertThat(extensiones).containsExactly("postgis");
    }

    @Test
    void laTablaDePruebaGuardaGeometriaEnWgs84() {
        Integer srid = jdbc.sql("SELECT ST_SRID(posicion) FROM prueba_cimientos WHERE nombre = 'Lleida Pirineus'")
                .query(Integer.class).single();
        // ST_Distance sobre geography da metros: Lleida Pirineus está a unos 130 km en línea recta de Barcelona Sants.
        Double metros = jdbc.sql("""
                SELECT ST_Distance(posicion::geography,
                                   ST_SetSRID(ST_MakePoint(2.1404, 41.3790), 4326)::geography)
                FROM prueba_cimientos WHERE nombre = 'Lleida Pirineus'
                """).query(Double.class).single();

        assertThat(srid).isEqualTo(4326);
        assertThat(metros).isBetween(120_000.0, 140_000.0);
    }

    @Test
    void healthDaUpConLaBaseDeDatos() {
        cliente.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.db.status").isEqualTo("UP");
    }

    @Test
    void estadoDevuelveVersionYHora() {
        cliente.get().uri("/api/estado").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.aplicacion").isEqualTo("TrainWatch")
                .jsonPath("$.version").isNotEmpty()
                .jsonPath("$.hora").isNotEmpty();
    }
}
