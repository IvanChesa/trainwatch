package com.ivanchesa.trainwatch.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.RelojConfig;
import com.ivanchesa.trainwatch.api.ConsultasApi.Estacion;
import com.ivanchesa.trainwatch.api.ConsultasApi.LineasDeEstacion;
import com.ivanchesa.trainwatch.api.ConsultasApi.Paso;
import com.ivanchesa.trainwatch.api.Respuestas.EstacionCercana;
import com.ivanchesa.trainwatch.api.Respuestas.FichaEstacion;
import com.ivanchesa.trainwatch.api.Respuestas.ProximoTren;
import com.ivanchesa.trainwatch.directo.EstadoDirecto;
import com.ivanchesa.trainwatch.directo.EstadoTren;
import com.ivanchesa.trainwatch.directo.Tren;
import com.ivanchesa.trainwatch.directo.Viaje;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Las estaciones: la ficha (próximos trenes, líneas y avisos) y la búsqueda por cercanía con PostGIS. */
@RestController
@RequestMapping("/api/estaciones")
@Tag(name = "Estaciones", description = "Ficha de una estación y estaciones cercanas a un punto")
public class EstacionesController {

    /** Próximos trenes: los que salen en la próxima hora. */
    static final Duration VENTANA = Duration.ofMinutes(60);
    /** Y los que tenían que haber salido hasta hace 30 min, por si van con retraso y aún no han pasado. */
    static final Duration RETRASO_MAXIMO = Duration.ofMinutes(30);
    /** Un tren sin directo cuya hora pasó hace más de esto se da por pasado. */
    static final Duration MARGEN = Duration.ofMinutes(1);
    static final int MAXIMO_PROXIMOS = 15;

    static final int RADIO_POR_DEFECTO_M = 1000;
    static final int RADIO_MAXIMO_M = 20_000;
    static final int LIMITE_MAXIMO = 50;

    private final ConsultasApi consultas;
    private final EstadoDirecto estado;
    private final Clock reloj;

    public EstacionesController(ConsultasApi consultas, EstadoDirecto estado, Clock reloj) {
        this.consultas = consultas;
        this.estado = estado;
        this.reloj = reloj;
    }

    @GetMapping
    @Operation(operationId = "estacionesCercanas", summary = "Estaciones cercanas a un punto",
            description = "Las estaciones a menos de «radio» metros del punto, de la más cercana a la más lejana"
                    + " (PostGIS, distancia sobre el elipsoide).")
    @RespuestasDeError
    public ResponseEntity<List<EstacionCercana>> cercanas(
            @Parameter(description = "Punto «lat,lon» en WGS 84", example = "41.3790,2.1400")
            @RequestParam String cerca,
            @Parameter(description = "Radio en metros, de 1 a 20.000", example = "1500")
            @RequestParam(defaultValue = "" + RADIO_POR_DEFECTO_M) int radio,
            @Parameter(description = "Cuántas como mucho, de 1 a 50", example = "10")
            @RequestParam(defaultValue = "10") int limite) {
        double[] punto = punto(cerca);
        if (radio < 1 || radio > RADIO_MAXIMO_M) {
            throw Problemas.parametroInvalido("radio", "El radio tiene que estar entre 1 y " + RADIO_MAXIMO_M + " m.");
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw Problemas.parametroInvalido("limite", "El límite tiene que estar entre 1 y " + LIMITE_MAXIMO + ".");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(consultas.cercanas(punto[0], punto[1], radio, limite));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "fichaEstacion", summary = "Ficha de una estación",
            description = "Las líneas que paran, los próximos trenes (horario de hoy cruzado con el directo: hora"
                    + " programada, estimada y retraso) y los avisos de la estación y de sus líneas.")
    @RespuestasDeError
    public ResponseEntity<FichaEstacion> ficha(
            @Parameter(description = "stop_id de Renfe", example = "71801") @PathVariable String id) {
        Estacion e = consultas.estacion(id)
                .orElseThrow(() -> Problemas.noEncontrado("No hay ninguna estación con el id «" + id + "»."));
        LineasDeEstacion lineas = consultas.lineasDeEstacion(id);
        Instant ahora = reloj.instant();
        FichaEstacion ficha = new FichaEstacion(e.id(), e.nombre(), e.lat(), e.lon(), lineas.lineas(),
                proximos(id, ahora), consultas.avisosDeEstacion(id, lineas.rutas()), VistaTrenes.hora(ahora));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic()).body(ficha);
    }

    /**
     * Los próximos trenes: el horario de ayer (los viajes que pasan de medianoche) y de hoy, cruzado con el directo.
     * Un tren en directo que ya ha dejado atrás la estación no sale; uno que no está en directo sale con la hora
     * programada hasta un minuto después.
     */
    List<ProximoTren> proximos(String stopId, Instant ahora) {
        Map<String, Tren> enDirecto = new HashMap<>();
        for (Tren t : estado.trenes()) {
            if (t.tripId() != null) {
                enDirecto.put(t.tripId(), t);
            }
        }
        List<ProximoTren> proximos = new ArrayList<>();
        LocalDate hoy = LocalDate.ofInstant(ahora, RelojConfig.ZONA);
        for (LocalDate fecha : List.of(hoy.minusDays(1), hoy)) {
            long s = ahora.getEpochSecond() - Viaje.origenDelDia(fecha, RelojConfig.ZONA).getEpochSecond();
            for (Paso p : consultas.pasos(stopId, fecha, s - RETRASO_MAXIMO.toSeconds(), s + VENTANA.toSeconds())) {
                Instant programada = Viaje.instante(fecha, p.salidaS(), RelojConfig.ZONA);
                Tren t = enDirecto.get(p.tripId());
                if (t != null && t.fechaServicio() != null && !t.fechaServicio().equals(fecha)) {
                    t = null; // el mismo viaje, pero de otro día
                }
                Instant estimada = programada;
                if (t != null) {
                    Integer secuencia = t.stopSequence();
                    if (t.estado() == EstadoTren.TERMINADO || secuencia != null && secuencia > p.secuencia()) {
                        continue; // ya ha pasado
                    }
                    if (t.estado() != EstadoTren.CANCELADO) {
                        estimada = VistaTrenes.conRetraso(programada, t);
                    }
                    boolean enLaEstacion = secuencia != null && secuencia == p.secuencia();
                    if (!enLaEstacion && estimada.isBefore(ahora.minus(MARGEN))) {
                        continue;
                    }
                } else if (programada.isBefore(ahora.minus(MARGEN))) {
                    continue;
                }
                proximos.add(new ProximoTren(t != null ? t.id() : null, p.linea(), p.nucleo(), p.destino(),
                        VistaTrenes.hora(programada), VistaTrenes.hora(estimada),
                        t == null ? null : VistaTrenes.minutos(t.retrasoS()), t != null, t == null ? null : t.estado()));
            }
        }
        proximos.sort(Comparator.comparing(ProximoTren::salidaEstimada).thenComparing(ProximoTren::salidaProgramada));
        return proximos.size() > MAXIMO_PROXIMOS ? List.copyOf(proximos.subList(0, MAXIMO_PROXIMOS)) : proximos;
    }

    /** «41.3790,2.1400» → {lat, lon}, o un 400 que explica el formato. */
    static double[] punto(String cerca) {
        String[] partes = cerca.split(",");
        if (partes.length == 2) {
            try {
                double lat = Double.parseDouble(partes[0].trim());
                double lon = Double.parseDouble(partes[1].trim());
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                    return new double[] { lat, lon };
                }
            } catch (NumberFormatException e) {
                // abajo
            }
        }
        throw Problemas.parametroInvalido("cerca",
                "«cerca» tiene que ser «lat,lon» en grados, por ejemplo «41.3790,2.1400».");
    }
}
