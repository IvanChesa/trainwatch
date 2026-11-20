package com.ivanchesa.trainwatch.api;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** El directo de un núcleo por Server-Sent Events (ADR 0015). */
@RestController
@Tag(name = "Directo", description = "Trenes de un núcleo en directo, por Server-Sent Events")
public class DirectoSseController {

    private final DifusorDirecto difusor;
    private final NucleosController nucleos;

    public DirectoSseController(DifusorDirecto difusor, NucleosController nucleos) {
        this.difusor = difusor;
        this.nucleos = nucleos;
    }

    @GetMapping(path = "/api/directo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "directo", summary = "Directo de un núcleo (SSE)",
            description = """
                    Un flujo text/event-stream para EventSource. Eventos:
                    - «instantanea» (el primero): todos los trenes del núcleo (Instantanea);
                    - «cambios» (cada ~7 s si hay alguno): altas, trenes que han cambiado y bajas (Cambios);
                    - «latido» (cada ~15 s): la conexión sigue viva y la antigüedad del último dato (Latido).
                    Las instantáneas y los cambios llevan su secuencia como id. Al reconectarse (Last-Event-ID), se
                    recibe una instantánea nueva. Cada conexión dura como mucho 30 min; el navegador se reconecta solo.
                    Si se llega al máximo de conexiones, 503 con Retry-After.""")
    @ApiResponse(responseCode = "200", description = "Flujo de eventos",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = Respuestas.Instantanea.class)))
    @ApiResponse(responseCode = "404", description = "El núcleo no existe",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Máximo de conexiones alcanzado; vuelve a probar tras Retry-After",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SseEmitter directo(
            @Parameter(description = "Código del núcleo", example = "51") @RequestParam int nucleo,
            @Parameter(description = "El id del último evento recibido (lo manda el navegador al reconectarse)")
            @RequestHeader(name = "Last-Event-ID", required = false) String ultimoId,
            HttpServletResponse respuesta) {
        nucleos.comprobarNucleo(nucleo);
        // Fase 7 (ADR 0018): detrás de Caddy y de Cloudflare, el flujo no se guarda en caché, no se comprime
        // (no-transform: Cloudflare no lo toca) y no se acumula en búferes (X-Accel-Buffering, la convención de los
        // proxies). El latido de 15 s queda muy por debajo de los 100 s que Cloudflare espera sin datos.
        respuesta.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        respuesta.setHeader("X-Accel-Buffering", "no");
        return difusor.suscribir(nucleo, ultimoId);
    }
}
