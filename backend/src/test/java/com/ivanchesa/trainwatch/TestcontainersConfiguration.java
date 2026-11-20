package com.ivanchesa.trainwatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL con PostGIS de verdad para las pruebas, con la misma imagen que infra/docker-compose.yml.
 * {@code @ServiceConnection} pasa la URL, el usuario y la clave del contenedor a Spring sin configurarlos a mano.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Fijada: si cambia aquí, cambia también en infra/docker-compose.yml. */
    public static final String IMAGEN_POSTGIS = "postgis/postgis:18-3.7";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGEN_POSTGIS).asCompatibleSubstituteFor("postgres"));
    }
}
