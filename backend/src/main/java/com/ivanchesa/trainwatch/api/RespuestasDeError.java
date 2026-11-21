package com.ivanchesa.trainwatch.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Para OpenAPI: los errores de un endpoint, como {@code ProblemDetail} (RFC 9457), y su 200. El 200 hace falta: con
 * alguna {@code @ApiResponse} en el método, springdoc ya no añade el suyo, y sin él no salía la respuesta correcta ni su
 * ejemplo de {@code docs/api/} (fase 6). Sin {@code content}, springdoc lo saca del tipo que devuelve el método.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "200", description = "Correcto")
@ApiResponse(responseCode = "400", description = "Parámetro que falta o no vale",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "No existe (o el tren ya no está en directo)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public @interface RespuestasDeError {
}
