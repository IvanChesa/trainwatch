package com.ivanchesa.trainwatch.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.api.ConsultasApi.Extension;
import com.ivanchesa.trainwatch.api.Respuestas.Cobertura;
import com.ivanchesa.trainwatch.api.Respuestas.Limites;
import com.ivanchesa.trainwatch.api.Respuestas.Nucleo;
import com.ivanchesa.trainwatch.api.Respuestas.Punto;
import com.ivanchesa.trainwatch.api.Respuestas.TrenMapa;
import com.ivanchesa.trainwatch.directo.IngestaDirecto;
import com.ivanchesa.trainwatch.directo.Nucleos;
import com.ivanchesa.trainwatch.directo.ResumenDirecto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Los núcleos (dónde centrar el mapa y el estado de su directo) y los trenes de cada uno. */
@RestController
@RequestMapping("/api/nucleos")
@Tag(name = "Núcleos", description = "Núcleos de Cercanías y Rodalies y sus trenes en directo")
public class NucleosController {

    /** Tamaño de mapa para el que se calcula el zoom sugerido, con un 10 % de margen. */
    static final double ANCHO_PX = 800 * 0.9;
    static final double ALTO_PX = 600 * 0.9;
    /** MapLibre usa teselas de 512 px: a zoom z, el mundo mide 512 · 2^z px. */
    static final double TESELA_PX = 512;

    private final ConsultasApi consultas;
    private final IngestaDirecto ingesta;
    private final DifusorDirecto difusor;

    public NucleosController(ConsultasApi consultas, IngestaDirecto ingesta, DifusorDirecto difusor) {
        this.consultas = consultas;
        this.ingesta = ingesta;
        this.difusor = difusor;
    }

    @GetMapping
    @Operation(operationId = "nucleos", summary = "Núcleos con su mapa y el estado del directo",
            description = "Todos los núcleos con trenes en el horario o en el feed: centro y zoom para el mapa, estado"
                    + " del directo («con directo», «sin datos en directo», «sin servicio ahora») y cobertura.")
    public ResponseEntity<List<Nucleo>> nucleos() {
        Map<Integer, Extension> extensiones = new HashMap<>();
        for (Extension e : consultas.extensiones()) {
            extensiones.put(e.nucleo(), e);
        }
        Map<Integer, ResumenDirecto.Nucleo> directo = new HashMap<>();
        ResumenDirecto resumen = ingesta.resumen();
        for (ResumenDirecto.Nucleo n : resumen.nucleos()) {
            directo.put(n.nucleo(), n);
        }
        TreeSet<Integer> ids = new TreeSet<>(extensiones.keySet());
        ids.addAll(directo.keySet());
        List<Nucleo> nucleos = new ArrayList<>();
        for (int id : ids) {
            nucleos.add(nucleo(id, extensiones.get(id), directo.get(id)));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic())
                .body(nucleos);
    }

    @GetMapping("/{id}/trenes")
    @Operation(operationId = "trenesDeNucleo", summary = "Trenes actuales de un núcleo",
            description = "Los mismos que manda la instantánea del directo (GET /api/directo): posición estimada,"
                    + " rumbo, retraso, próxima parada y, si está en origen, la hora de salida.")
    @RespuestasDeError
    public ResponseEntity<List<TrenMapa>> trenes(
            @Parameter(description = "Código del núcleo", example = "51") @PathVariable int id) {
        comprobarNucleo(id);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic())
                .body(difusor.trenes(id));
    }

    /** 404 si el núcleo no existe: ni es uno de Renfe, ni está en el horario, ni ha tenido trenes en directo. */
    void comprobarNucleo(int id) {
        if (Nucleos.NOMBRES.containsKey(id) || difusor.conoce(id)) {
            return;
        }
        for (Extension e : consultas.extensiones()) {
            if (e.nucleo() == id) {
                return;
            }
        }
        throw Problemas.noEncontrado("No hay ningún núcleo con el código " + id + ".");
    }

    static Nucleo nucleo(int id, Extension e, ResumenDirecto.Nucleo d) {
        String estado = d == null ? "SIN_DATOS_EN_DIRECTO" : d.estado();
        Cobertura cobertura = d == null ? null : new Cobertura(d.enMarchaHorario(), d.enMarchaVistos(), d.coberturaPct());
        Punto centro = e == null ? null : new Punto(redondear((e.latMin() + e.latMax()) / 2, 4),
                redondear((e.lonMin() + e.lonMax()) / 2, 4));
        Limites limites = e == null ? null : new Limites(e.latMin(), e.lonMin(), e.latMax(), e.lonMax());
        return new Nucleo(id, Nucleos.nombre(id), centro, e == null ? null : zoom(e), limites, estado, texto(estado),
                d == null ? 0 : d.enDirecto(), cobertura);
    }

    static String texto(String estado) {
        return switch (estado) {
            case "EN_DIRECTO" -> "con directo";
            case "SIN_SERVICIO" -> "sin servicio ahora";
            default -> "sin datos en directo";
        };
    }

    /**
     * El zoom de MapLibre con el que el rectángulo cabe en un mapa de 800 × 600 px (con margen): el menor de los que
     * hacen caber el ancho (longitud) y el alto (latitud en Mercator). Entre 4 y 14, con un decimal, hacia abajo.
     */
    static double zoom(Extension e) {
        double ancho = Math.max(e.lonMax() - e.lonMin(), 0.01);
        double alto = Math.max(mercator(e.latMax()) - mercator(e.latMin()), 0.0002);
        double zAncho = log2(ANCHO_PX * 360 / (TESELA_PX * ancho));
        double zAlto = log2(ALTO_PX * 2 * Math.PI / (TESELA_PX * alto));
        double z = Math.clamp(Math.min(zAncho, zAlto), 4, 14);
        return Math.floor(z * 10) / 10;
    }

    private static double mercator(double lat) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double redondear(double x, int decimales) {
        double f = Math.pow(10, decimales);
        return Math.round(x * f) / f;
    }
}
