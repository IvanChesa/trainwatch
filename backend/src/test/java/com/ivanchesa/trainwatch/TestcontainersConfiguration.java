package com.ivanchesa.trainwatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.images.builder.Transferable;
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

    /**
     * Script de arranque de la imagen que crea postgis, postgis_topology, fuzzystrmatch y postgis_tiger_geocoder
     * en la base de datos inicial. Se sustituye por uno vacío para que sea la migración de Flyway quien active
     * PostGIS, como pasará en un PostgreSQL que no sea esta imagen. Igual que en infra/docker-compose.yml.
     */
    static final String SCRIPT_INICIO_POSTGIS = "/docker-entrypoint-initdb.d/10_postgis.sh";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGEN_POSTGIS).asCompatibleSubstituteFor("postgres"))
                .withCopyToContainer(Transferable.of("# Desactivado: PostGIS lo activa Flyway\n"), SCRIPT_INICIO_POSTGIS);
    }
}
