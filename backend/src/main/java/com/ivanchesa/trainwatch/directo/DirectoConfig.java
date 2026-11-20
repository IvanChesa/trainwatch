package com.ivanchesa.trainwatch.directo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ivanchesa.trainwatch.RelojConfig;

/** El estado en memoria es una clase sin Spring (se prueba sola); aquí se crea con la configuración. */
@Configuration(proxyBeanMethods = false)
public class DirectoConfig {

    @Bean
    EstadoDirecto estadoDirecto(FuenteHorario horario, DirectoProperties propiedades) {
        return new EstadoDirecto(horario, RelojConfig.ZONA, propiedades.muestrasParaDesaparecer(),
                propiedades.fraccionIncompleta());
    }
}
