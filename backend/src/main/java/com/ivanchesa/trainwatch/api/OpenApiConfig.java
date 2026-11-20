package com.ivanchesa.trainwatch.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * La documentación OpenAPI ({@code /api/docs}, y la interfaz de Swagger en {@code /api/docs/ui}).
 *
 * <p>Los ejemplos de respuesta salen de {@code docs/api/<operationId>.json}, los mismos ficheros que usa el frontend
 * como datos simulados en sus pruebas: Maven los copia al classpath ({@code ejemplos-api/}) y aquí se cuelgan del 200
 * de cada operación. Así hay un solo ejemplo por endpoint, y una prueba comprueba que encaja con los records.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI trainwatchOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TrainWatch API")
                .version("v1")
                .description("""
                        API pública y de solo lectura de TrainWatch: trenes de Cercanías y Rodalies en directo, con su \
                        retraso, estaciones, líneas y avisos. Proyecto personal **no oficial**, sin relación con \
                        Renfe. Datos: Renfe (CC BY 4.0).

                        Las horas van en hora de Madrid con su desfase; los retrasos, en minutos enteros (como los da \
                        Renfe). Los errores siguen el RFC 9457 (application/problem+json).""")
                .license(new License().name("MIT")));
    }

    @Bean
    OpenApiCustomizer ejemplosDeDocsApi() {
        return api -> {
            if (api.getPaths() == null) {
                return;
            }
            for (PathItem ruta : api.getPaths().values()) {
                for (Map.Entry<PathItem.HttpMethod, Operation> e : ruta.readOperationsMap().entrySet()) {
                    Operation op = e.getValue();
                    Object ejemplo = leer(op.getOperationId());
                    if (ejemplo == null || op.getResponses() == null) {
                        continue;
                    }
                    ApiResponse ok = op.getResponses().get("200");
                    Content contenido = ok == null ? null : ok.getContent();
                    if (contenido == null) {
                        continue;
                    }
                    for (MediaType tipo : contenido.values()) {
                        tipo.addExamples("ejemplo", new Example().summary("Ejemplo con datos reales (docs/api)")
                                .value(ejemplo));
                    }
                }
            }
        };
    }

    /** El ejemplo como árbol JSON (con el Jackson de swagger-core, que es el que lo serializa), o {@code null}. */
    private static Object leer(String operationId) {
        if (operationId == null) {
            return null;
        }
        try (InputStream in = OpenApiConfig.class.getResourceAsStream("/ejemplos-api/" + operationId + ".json")) {
            return in == null ? null
                    : io.swagger.v3.core.util.Json.mapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
