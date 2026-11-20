package com.ivanchesa.trainwatch;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Un único reloj para toda la aplicación, en hora de Madrid (los horarios de Renfe van en hora local).
 * Las pruebas pueden sustituirlo por un {@link Clock#fixed} y no depender de la hora real.
 */
@Configuration(proxyBeanMethods = false)
public class RelojConfig {

    public static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    @Bean
    Clock reloj() {
        return Clock.system(ZONA);
    }
}
