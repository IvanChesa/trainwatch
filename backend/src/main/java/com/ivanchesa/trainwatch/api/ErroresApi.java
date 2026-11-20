package com.ivanchesa.trainwatch.api;

import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Todos los errores de la API como {@code ProblemDetail} (RFC 9457, {@code application/problem+json}), con el título
 * en español. Los de Spring (parámetro que falta o de otro tipo, ruta que no existe, método no permitido) los
 * convierte {@link ResponseEntityExceptionHandler}; los propios se crean en {@link Problemas}.
 *
 * <p>El tipo de contenido se fija en la respuesta: así el error llega aunque el cliente solo acepte
 * {@code text/event-stream} (el {@code EventSource} del navegador al pedir el directo).
 */
@RestControllerAdvice
public class ErroresApi extends ResponseEntityExceptionHandler {

    private static final Map<Integer, String> TITULOS = Map.of(
            400, "Petición incorrecta",
            404, "No encontrado",
            405, "Método no permitido",
            406, "Formato no disponible",
            500, "Error interno",
            503, "Servicio no disponible");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ErroresApi.class);

    /**
     * Cualquier otro error (la base de datos caída, una consulta que pasa de 15 s...): un 500 como
     * {@code ProblemDetail} sin detalles internos (ni SQL ni trazas), que van al registro.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    ResponseEntity<Object> errorInterno(Exception ex, WebRequest request) {
        log.error("Error interno en la API ({})", request.getDescription(false), ex);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se ha podido atender la petición. Vuelve a probar en unos segundos.");
        p.setTitle(TITULOS.get(500));
        return handleExceptionInternal(ex, p, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse respuesta) {
            // Lo mismo que haría la clase base, pero antes, para poder traducirlo.
            body = respuesta.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail p) {
            // El título que pone Spring es la frase del estado en inglés («Not Found»); los propios ya van en español.
            HttpStatus estado = HttpStatus.resolve(statusCode.value());
            boolean deSpring = p.getTitle() == null || estado != null && p.getTitle().equals(estado.getReasonPhrase());
            if (deSpring && TITULOS.containsKey(statusCode.value())) {
                p.setTitle(TITULOS.get(statusCode.value()));
            }
            if (ex instanceof MissingServletRequestParameterException m) {
                p.setDetail("Falta el parámetro «" + m.getParameterName() + "».");
                p.setProperty("parametro", m.getParameterName());
            } else if (ex instanceof MethodArgumentTypeMismatchException m) {
                p.setDetail("El parámetro «" + m.getName() + "» no tiene un valor válido: «" + m.getValue() + "».");
                p.setProperty("parametro", m.getName());
            }
        }
        HttpHeaders conTipo = new HttpHeaders();
        if (headers != null) {
            conTipo.putAll(headers);
        }
        conTipo.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(ex, body, conTipo, statusCode, request);
    }
}
