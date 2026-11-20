package com.ivanchesa.trainwatch.estado;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import com.ivanchesa.trainwatch.RelojConfig;

/** Prueba unitaria, sin Spring ni base de datos: versión y hora con un reloj fijo. */
class EstadoControllerTests {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-20T09:30:00Z"), RelojConfig.ZONA);

    @Test
    void devuelveLaVersionDelBuildYLaHoraDeMadrid() {
        Properties propiedades = new Properties();
        propiedades.setProperty("version", "1.2.3");
        StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
        fabrica.addBean("buildProperties", new BuildProperties(propiedades));

        Estado estado = new EstadoController(RELOJ, fabrica.getBeanProvider(BuildProperties.class)).estado();

        assertThat(estado.aplicacion()).isEqualTo("TrainWatch");
        assertThat(estado.version()).isEqualTo("1.2.3");
        assertThat(estado.hora()).hasToString("2026-11-20T10:30+01:00");
    }

    @Test
    void sinBuildInfoLaVersionEsDesconocida() {
        StaticListableBeanFactory vacia = new StaticListableBeanFactory();

        Estado estado = new EstadoController(RELOJ, vacia.getBeanProvider(BuildProperties.class)).estado();

        assertThat(estado.version()).isEqualTo(EstadoController.VERSION_DESCONOCIDA);
    }
}
