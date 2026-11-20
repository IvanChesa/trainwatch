package com.ivanchesa.trainwatch.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.RelojConfig;
import com.ivanchesa.trainwatch.api.Respuestas.Aviso;
import com.ivanchesa.trainwatch.api.Respuestas.FichaTren;
import com.ivanchesa.trainwatch.api.Respuestas.ParadaFicha;
import com.ivanchesa.trainwatch.api.Respuestas.PuntoEvolucion;
import com.ivanchesa.trainwatch.api.Respuestas.TrenMapa;
import com.ivanchesa.trainwatch.directo.EstadoDirecto;
import com.ivanchesa.trainwatch.directo.EstadoTren;
import com.ivanchesa.trainwatch.directo.Nucleos;
import com.ivanchesa.trainwatch.directo.ParadaViaje;
import com.ivanchesa.trainwatch.directo.Tren;
import com.ivanchesa.trainwatch.directo.Viaje;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** La ficha de un tren en directo. */
@RestController
@RequestMapping("/api/trenes")
@Tag(name = "Trenes", description = "Ficha de un tren en directo")
public class TrenesController {

    private final EstadoDirecto estado;
    private final ConsultasApi consultas;

    public TrenesController(EstadoDirecto estado, ConsultasApi consultas) {
        this.estado = estado;
        this.consultas = consultas;
    }

    @GetMapping("/{id}")
    @Operation(operationId = "fichaTren", summary = "Ficha de un tren en directo",
            description = "Línea, origen y destino, paradas con su hora programada y la estimada (programada + retraso"
                    + " actual), retraso actual y su evolución en el viaje (el retraso final con que dejó atrás cada"
                    + " parada). 404 si el tren no está en directo.")
    @RespuestasDeError
    public ResponseEntity<FichaTren> ficha(
            @Parameter(description = "El id de un tren del mapa (trip_id de Renfe)", example = "5123R15001R4")
            @PathVariable String id) {
        Tren t = estado.tren(id);
        if (t == null) {
            throw Problemas.noEncontrado("El tren «" + id + "» no está en directo ahora.");
        }
        TrenMapa m = VistaTrenes.mapa(t);
        Viaje viaje = t.viaje();
        List<PuntoEvolucion> evolucion = viaje == null || t.tripId() == null || t.fechaServicio() == null ? List.of()
                : consultas.evolucion(t.fechaServicio(), t.tripId());
        List<Aviso> avisos = viaje != null ? consultas.avisosDeRutas(List.of(viaje.routeId()))
                : t.nucleo() != null && t.linea() != null ? consultas.avisos(t.nucleo(), t.linea(), null) : List.of();
        FichaTren ficha = new FichaTren(t.id(), t.tripId(), t.vehiculo(), t.linea(), t.nucleo(),
                t.nucleo() == null ? null : Nucleos.nombre(t.nucleo()), t.estado(), t.sinHorario(), t.fechaServicio(),
                VistaTrenes.origen(viaje), m.destino(), m.retrasoMin(), m.lat(), m.lon(), m.fuentePosicion(),
                m.rumbo(), m.actualizado(), paradas(t, evolucion), evolucion, avisos);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic()).body(ficha);
    }

    /**
     * Las paradas del viaje con su situación. La del feed es la actual o la última que ha dejado atrás (fase 0); las
     * anteriores, pasadas; la siguiente, la próxima. La hora estimada es la programada más el retraso actual, solo en
     * las que faltan: no es una predicción, Renfe solo da el retraso para la próxima parada.
     */
    static List<ParadaFicha> paradas(Tren t, List<PuntoEvolucion> evolucion) {
        Viaje viaje = t.viaje();
        if (viaje == null || t.fechaServicio() == null) {
            return List.of();
        }
        Map<Integer, PuntoEvolucion> cerradas = new HashMap<>();
        for (PuntoEvolucion p : evolucion) {
            cerradas.put(p.secuencia(), p);
        }
        int actual = t.estado() == EstadoTren.EN_ORIGEN ? 0 : t.indiceParada();
        int proxima = VistaTrenes.indiceProxima(t);
        List<ParadaFicha> paradas = new ArrayList<>();
        List<ParadaViaje> lista = viaje.paradas();
        for (int i = 0; i < lista.size(); i++) {
            ParadaViaje p = lista.get(i);
            Instant llegada = Viaje.instante(t.fechaServicio(), p.llegadaS(), RelojConfig.ZONA);
            Instant salida = Viaje.instante(t.fechaServicio(), p.salidaS(), RelojConfig.ZONA);
            String situacion = actual < 0 ? "PENDIENTE"
                    : i < actual ? "PASADA" : i == actual ? "ACTUAL" : i == proxima ? "PROXIMA" : "PENDIENTE";
            boolean falta = actual < 0 || i > actual
                    || (i == actual && t.estado() == EstadoTren.EN_ORIGEN);
            Instant estimada = falta && t.estado() != EstadoTren.CANCELADO ? VistaTrenes.conRetraso(llegada, t) : null;
            PuntoEvolucion cerrada = cerradas.get(p.secuencia());
            paradas.add(new ParadaFicha(p.secuencia(), p.stopId(), p.nombre(), p.lat(), p.lon(), VistaTrenes.hora(llegada),
                    VistaTrenes.hora(salida), VistaTrenes.hora(estimada), situacion,
                    cerrada == null ? null : cerrada.retrasoMin(), cerrada == null ? null : cerrada.inferido()));
        }
        return paradas;
    }
}
