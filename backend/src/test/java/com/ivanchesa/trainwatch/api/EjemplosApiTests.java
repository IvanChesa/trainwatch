package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ivanchesa.trainwatch.api.Respuestas.Aviso;
import com.ivanchesa.trainwatch.api.Respuestas.Cambios;
import com.ivanchesa.trainwatch.api.Respuestas.EstacionCercana;
import com.ivanchesa.trainwatch.api.Respuestas.FichaEstacion;
import com.ivanchesa.trainwatch.api.Respuestas.FichaTren;
import com.ivanchesa.trainwatch.api.Respuestas.Instantanea;
import com.ivanchesa.trainwatch.api.Respuestas.Latido;
import com.ivanchesa.trainwatch.api.Respuestas.Linea;
import com.ivanchesa.trainwatch.api.Respuestas.Nucleo;
import com.ivanchesa.trainwatch.api.Respuestas.TrenMapa;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadEstacion;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadLinea;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadNucleo;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * El contrato para el frontend: cada ejemplo de {@code docs/api/} (que Maven copia a {@code ejemplos-api/}) se lee con
 * el record de su respuesta y, al volver a escribirlo, sale igual. Si alguien cambia un record y no el ejemplo (o al
 * revés), esta prueba falla: el frontend nunca prueba contra un JSON que la API ya no da.
 */
class EjemplosApiTests {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .build();

    static Stream<Arguments> ejemplos() {
        return Stream.of(
                Arguments.of("nucleos", new TypeReference<List<Nucleo>>() { }),
                Arguments.of("trenesDeNucleo", new TypeReference<List<TrenMapa>>() { }),
                Arguments.of("fichaTren", new TypeReference<FichaTren>() { }),
                Arguments.of("fichaEstacion", new TypeReference<FichaEstacion>() { }),
                Arguments.of("estacionesCercanas", new TypeReference<List<EstacionCercana>>() { }),
                Arguments.of("linea", new TypeReference<Linea>() { }),
                Arguments.of("avisos", new TypeReference<List<Aviso>>() { }),
                Arguments.of("directo", new TypeReference<Instantanea>() { }),
                Arguments.of("directo-cambios", new TypeReference<Cambios>() { }),
                Arguments.of("directo-latido", new TypeReference<Latido>() { }),
                Arguments.of("puntualidadNucleo", new TypeReference<PuntualidadNucleo>() { }),
                Arguments.of("puntualidadLinea", new TypeReference<PuntualidadLinea>() { }),
                Arguments.of("puntualidadEstacion", new TypeReference<PuntualidadEstacion>() { }));
    }

    @ParameterizedTest(name = "{0}.json")
    @MethodSource("ejemplos")
    void cadaEjemploEncajaConSuRecord(String nombre, TypeReference<?> tipo) throws IOException {
        JsonNode original;
        try (InputStream in = EjemplosApiTests.class.getResourceAsStream("/ejemplos-api/" + nombre + ".json")) {
            assertThat(in).as("docs/api/" + nombre + ".json").isNotNull();
            original = JSON.readTree(in);
        }
        Object leido = JSON.readValue(JSON.writeValueAsString(original), tipo);
        // Se compara el texto: en el árbol, un 23 leído (int) y uno escrito desde un long no son el mismo nodo.
        assertThat(JSON.writeValueAsString(leido)).as(nombre).isEqualTo(JSON.writeValueAsString(original));
    }
}
