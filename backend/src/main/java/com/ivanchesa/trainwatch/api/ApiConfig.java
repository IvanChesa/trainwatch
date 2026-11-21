package com.ivanchesa.trainwatch.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS y {@code ETag} de la API.
 *
 * <p><b>CORS</b> solo para la web ({@code https://trainwatch.ivanchesa.com}) y el servidor de Vite en local
 * ({@code http://localhost:5173}), y solo para leer: es una API pública de solo lectura, sin cookies ni cabeceras de
 * autenticación. {@code Last-Event-ID} se admite porque el navegador la manda al reconectarse al directo.
 *
 * <p><b>{@code ETag}</b> en las líneas, las estaciones y las estadísticas: el cuerpo se calcula igual, pero si no ha cambiado se responde
 * un 304 sin cuerpo. No se aplica al directo ({@code /api/directo}): el filtro guarda la respuesta entera en memoria
 * antes de mandarla, y un flujo SSE no termina nunca.
 */
@Configuration(proxyBeanMethods = false)
public class ApiConfig implements WebMvcConfigurer {

    private final ApiProperties propiedades;

    public ApiConfig(ApiProperties propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/api/**")
                .allowedOrigins(propiedades.origenesPermitidos().toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("Last-Event-ID", "Cache-Control", "If-None-Match")
                .exposedHeaders("ETag", "Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagLineasYEstaciones() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registro = new FilterRegistrationBean<>(
                new ShallowEtagHeaderFilter());
        registro.addUrlPatterns("/api/lineas/*", "/api/estaciones", "/api/estaciones/*", "/api/estadisticas/*");
        registro.setName("etagLineasYEstaciones");
        return registro;
    }
}
