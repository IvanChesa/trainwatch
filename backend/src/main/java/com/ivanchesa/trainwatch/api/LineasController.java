package com.ivanchesa.trainwatch.api;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.api.ConsultasApi.DatosLinea;
import com.ivanchesa.trainwatch.api.Respuestas.Aviso;
import com.ivanchesa.trainwatch.api.Respuestas.Linea;
import com.ivanchesa.trainwatch.directo.Nucleos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Las líneas (con su recorrido en GeoJSON) y los avisos de Renfe. */
@RestController
@RequestMapping("/api")
@Tag(name = "Líneas y avisos", description = "Recorrido y paradas de una línea; avisos de Renfe")
public class LineasController {

    private final ConsultasApi consultas;

    public LineasController(ConsultasApi consultas) {
        this.consultas = consultas;
    }

    @GetMapping("/lineas/{nucleo}/{linea}")
    @Operation(operationId = "linea", summary = "Una línea con su recorrido, paradas y avisos",
            description = "El recorrido en GeoJSON (una LineString por recorrido del GTFS), las paradas en el orden del"
                    + " recorrido más usado y los avisos. El recorrido y las paradas solo cambian con el horario de"
                    + " cada noche: la respuesta lleva ETag y se puede guardar 1 min.")
    @RespuestasDeError
    public ResponseEntity<Linea> linea(
            @Parameter(description = "Código del núcleo", example = "51") @PathVariable int nucleo,
            @Parameter(description = "Nombre corto de la línea, sin distinguir mayúsculas", example = "R4")
            @PathVariable String linea) {
        DatosLinea d = consultas.linea(nucleo, linea).orElseThrow(() -> Problemas.noEncontrado(
                "No hay ninguna línea «" + linea + "» en el núcleo " + nucleo + "."));
        java.util.List<Aviso> avisos = consultas.avisosDeRutas(d.rutas());
        Linea respuesta = new Linea(d.nucleo(), Nucleos.nombre(d.nucleo()), d.linea(), d.nombreLargo(), d.color(),
                d.colorTexto(), d.rutas(), d.recorrido(), d.paradas(), avisos);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(respuesta);
    }

    @GetMapping("/avisos")
    @Operation(operationId = "avisos", summary = "Avisos de Renfe",
            description = "Los avisos del último alerts.pb de Renfe, tal cual (no se filtran por sus fechas de inicio"
                    + " y fin, que van en cada aviso), con las líneas y estaciones a las que afectan. Los filtros se"
                    + " combinan; sin filtros, todos.")
    @RespuestasDeError
    public ResponseEntity<java.util.List<Aviso>> avisos(
            @Parameter(description = "Código del núcleo", example = "51") @RequestParam(required = false) Integer nucleo,
            @Parameter(description = "Nombre corto de la línea", example = "R4") @RequestParam(required = false) String linea,
            @Parameter(description = "stop_id de la estación", example = "71801") @RequestParam(required = false) String estacion) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(consultas.avisos(nucleo, vacio(linea), vacio(estacion)));
    }

    private static String vacio(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
