package com.ivanchesa.trainwatch.api;

import java.util.regex.Pattern;

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

/** El directo de un núcleo, o de todos a la vez, por Server-Sent Events (ADR 0015). */
@RestController
@Tag(name = "Directo", description = "Trenes de un núcleo en directo, por Server-Sent Events")
public class DirectoSseController {

    /** El valor de {@code nucleo} para el directo de todos los núcleos («Toda España» en la web). */
    static final String TODOS = "todos";
    private static final Pattern CODIGO = Pattern.compile("[0-9]+");

    private final DifusorDirecto difusor;
    private final NucleosController nucleos;

    public DirectoSseController(DifusorDirecto difusor, NucleosController nucleos) {
        this.difusor = difusor;
        this.nucleos = nucleos;
    }

    @GetMapping(path = "/api/directo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "directo", summary = "Directo de un núcleo o de todos (SSE)",
            description = """
                    Un flujo text/event-stream para EventSource, de un núcleo (nucleo=51) o de todos a la vez
                    (nucleo=todos: unos 400 trenes; la instantánea pesa unos 10 veces la de un núcleo). Eventos:
                    - «instantanea» (el primero): todos los trenes del núcleo (Instantanea);
                    - «cambios» (cada ~7 s si hay alguno): altas, trenes que han cambiado y bajas (Cambios);
                    - «latido» (cada ~15 s): la conexión sigue viva y la antigüedad del último dato (Latido).
                    Las instantáneas y los cambios llevan su secuencia como id. Al reconectarse (Last-Event-ID), se
                    recibe una instantánea nueva. Cada conexión dura como mucho 30 min; el navegador se reconecta solo.
                    Si se llega al máximo de conexiones, 503 con Retry-After.""")
    @ApiResponse(responseCode = "200", description = "Flujo de eventos",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = Respuestas.Instantanea.class)))
    @ApiResponse(responseCode = "400", description = "«nucleo» no es un número ni «todos»",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "El núcleo no existe",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Máximo de conexiones alcanzado; vuelve a probar tras Retry-After",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SseEmitter directo(
            @Parameter(description = "Código del núcleo, o «todos» para los trenes de todos los núcleos", example = "51",
                    schema = @Schema(type = "string", pattern = "^([0-9]+|todos)$")) @RequestParam String nucleo,
            @Parameter(description = "El id del último evento recibido (lo manda el navegador al reconectarse)")
            @RequestHeader(name = "Last-Event-ID", required = false) String ultimoId,
            HttpServletResponse respuesta) {
        Integer id = TODOS.equals(nucleo) ? null : codigo(nucleo);
        if (id != null) {
            nucleos.comprobarNucleo(id);
        }
        // Fase 7 (ADR 0018): detrás de Caddy y de Cloudflare, el flujo no se guarda en caché, no se comprime
        // (no-transform: Cloudflare no lo toca) y no se acumula en búferes (X-Accel-Buffering, la convención de los
        // proxies). El latido de 15 s queda muy por debajo de los 100 s que Cloudflare espera sin datos.
        respuesta.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        respuesta.setHeader("X-Accel-Buffering", "no");
        return id == null ? difusor.suscribirTodos(ultimoId) : difusor.suscribir(id, ultimoId);
    }

    /** El código de un núcleo, o un 400 como el de cualquier parámetro con un valor que no vale. */
    private static int codigo(String nucleo) {
        // Lo mismo que dice OpenAPI (^([0-9]+|todos)$): ni signos, ni espacios, ni cifras que no sean ASCII.
        if (nucleo.length() <= 9 && CODIGO.matcher(nucleo).matches()) {
            return Integer.parseInt(nucleo);
        }
        throw Problemas.parametroInvalido("nucleo", "El parámetro «nucleo» no tiene un valor válido: «" + nucleo
                + "». Tiene que ser el código de un núcleo o «todos».");
    }
}
