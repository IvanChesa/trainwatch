package com.ivanchesa.trainwatch.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Los errores propios de la API, como {@link ErrorResponseException}: Spring los convierte en {@code ProblemDetail}
 * (RFC 9457) con su estado, su título en español y sus cabeceras ({@link ErroresApi}).
 */
public final class Problemas {

    private Problemas() {
    }

    /** Un 404: el núcleo, el tren, la estación o la línea no existen (o el tren ya no está en directo). */
    public static ErrorResponseException noEncontrado(String detalle) {
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problema(HttpStatus.NOT_FOUND, "No encontrado", detalle),
                null);
    }

    /** Un 400: un parámetro con un valor que no vale (formato o rango). */
    public static ErrorResponseException parametroInvalido(String parametro, String detalle) {
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Parámetro no válido", detalle);
        p.setProperty("parametro", parametro);
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, p, null);
    }

    /**
     * Un 503 con {@code Retry-After}: se ha llegado al máximo de conexiones del directo
     * ({@code trainwatch.api.directo.maximo-conexiones}).
     */
    public static ErrorResponseException directoCompleto(int maximo, int reintentarEnS) {
        ErrorResponseException e = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                problema(HttpStatus.SERVICE_UNAVAILABLE, "Directo completo", "Hay " + maximo
                        + " conexiones al directo abiertas, el máximo. Vuelve a probar en " + reintentarEnS + " s."),
                null);
        e.getHeaders().set("Retry-After", String.valueOf(reintentarEnS));
        return e;
    }

    /** Un 503 con {@code Retry-After}: no se puede atender ahora (por ejemplo, la aplicación se está apagando). */
    public static ErrorResponseException noDisponible(String detalle, int reintentarEnS) {
        ErrorResponseException e = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", detalle), null);
        e.getHeaders().set("Retry-After", String.valueOf(reintentarEnS));
        return e;
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        return p;
    }
}
