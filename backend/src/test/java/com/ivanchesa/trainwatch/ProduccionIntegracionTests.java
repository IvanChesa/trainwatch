package com.ivanchesa.trainwatch;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Fase 7: el perfil {@code prod} tal como arranca en el servidor (con la base de datos de Testcontainers y la ingesta
 * apagada, del perfil {@code test}). Actuator no está en el puerto público, sino en el de gestión, que no se publica;
 * las cabeceras {@code X-Forwarded-*} de Caddy cambian el esquema y el host; y el pool es pequeño.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // En el servidor es el 8081; aquí, uno libre.
        "management.server.port=0",
        // Las variables que pone desplegar.sh. La conexión de verdad la da Testcontainers (@ServiceConnection).
        "TRAINWATCH_DB_URL=jdbc:postgresql://no-se-usa/trainwatch",
        "TRAINWATCH_DB_USER=no-se-usa",
        "TRAINWATCH_DB_PASSWORD=no-se-usa" })
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({ "prod", "test" })
class ProduccionIntegracionTests {

    @Value("${local.server.port}")
    int puerto;

    @LocalManagementPort
    int puertoGestion;

    @Autowired
    DataSource dataSource;

    RestTestClient publico;
    RestTestClient gestion;

    @BeforeEach
    void preparaClientes() {
        publico = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        gestion = RestTestClient.bindToServer().baseUrl("http://localhost:" + puertoGestion).build();
    }

    @Test
    void actuatorNoEstaEnElPuertoPublico() {
        assertThat(puertoGestion).isNotEqualTo(puerto);
        for (String ruta : new String[] { "/actuator", "/actuator/health", "/actuator/metrics",
                "/actuator/metrics/jvm.memory.used" }) {
            publico.get().uri(ruta).exchange().expectStatus().isNotFound();
        }
        // La API sí.
        publico.get().uri("/api/estado").exchange().expectStatus().isOk();
    }

    @Test
    void actuatorEstaEnElPuertoDeGestionConSusComponentesYMetricas() {
        gestion.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP").jsonPath("$.components.db.status").isEqualTo("UP");
        // La sonda que espera desplegar.sh.
        gestion.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        gestion.get().uri("/actuator/metrics/jvm.memory.used").exchange().expectStatus().isOk();
        // Lo demás de Actuator (entorno, configuración, volcados) no se expone ni en el de gestión.
        gestion.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        gestion.get().uri("/actuator/heapdump").exchange().expectStatus().isNotFound();
    }

    @Test
    void detrasDeCaddyLaDocumentacionDiceHttpsYElHostPublico() {
        // Caddy llega desde la red de Docker (privada): Tomcat se fía de sus X-Forwarded-*.
        publico.get().uri("/api/docs")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "trainwatch-api.ivanchesa.com")
                .header("X-Forwarded-Port", "443")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.servers[0].url").isEqualTo("https://trainwatch-api.ivanchesa.com");
    }

    @Test
    void elPoolDeConexionesEsPequeno() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(6);
    }
}
