package com.ivanchesa.trainwatch.horario;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Descarga condicional del ZIP: manda el {@code ETag} y el {@code Last-Modified} de la última importación correcta.
 * Si Renfe contesta 304, o devuelve el mismo {@code ETag}, el fichero no ha cambiado y no se importa.
 *
 * <p>El ZIP comprimido (unos 15,6 MB) se guarda en un fichero temporal para poder leer sus ficheros uno detrás de
 * otro; lo que nunca se escribe a disco es su contenido descomprimido (295 MB).
 */
@Component
public class DescargaHorario {

    private static final Logger log = LoggerFactory.getLogger(DescargaHorario.class);

    /** Tamaño máximo del ZIP: hoy son 15,6 MB; más de 200 MB no es un horario de Cercanías. */
    static final long BYTES_MAXIMOS = 200L * 1024 * 1024;

    private final HorarioProperties propiedades;
    private final HttpClient cliente;

    public DescargaHorario(HorarioProperties propiedades) {
        this.propiedades = propiedades;
        this.cliente = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /** Lo que ha pasado con la descarga. {@code zip} es null si no ha cambiado. */
    public record Resultado(boolean cambiado, Path zip, String etag, String lastModified, long bytes) {
    }

    /**
     * @param etagAnterior         ETag de la última importación correcta, o vacío
     * @param lastModifiedAnterior Last-Modified de la última importación correcta, o vacío
     * @param forzar               descarga y reimporta aunque no haya cambiado
     */
    public Resultado descargar(String etagAnterior, String lastModifiedAnterior, boolean forzar)
            throws IOException, InterruptedException {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(propiedades.url())
                .timeout(propiedades.tiempoMaximoDescarga())
                .header("User-Agent", "TrainWatch (proyecto no oficial)")
                .GET();
        if (!forzar && !etagAnterior.isBlank()) {
            peticion.header("If-None-Match", etagAnterior);
        }
        if (!forzar && !lastModifiedAnterior.isBlank()) {
            peticion.header("If-Modified-Since", lastModifiedAnterior);
        }
        Path destino = Files.createTempFile("trainwatch-horario-", ".zip");
        try {
            // HttpRequest.timeout solo limita la espera de las cabeceras, no la del cuerpo: si el servidor se queda a
            // medias, send() esperaría para siempre con la importación ocupada. Se limita el tiempo total.
            CompletableFuture<HttpResponse<Path>> futuro =
                    cliente.sendAsync(peticion.build(), HttpResponse.BodyHandlers.ofFile(destino));
            HttpResponse<Path> respuesta;
            try {
                respuesta = futuro.get(propiedades.tiempoMaximoDescarga().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                futuro.cancel(true);
                throw new IOException("La descarga del horario no terminó en " + propiedades.tiempoMaximoDescarga());
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            }
            String etag = respuesta.headers().firstValue("ETag").orElse(null);
            String lastModified = respuesta.headers().firstValue("Last-Modified").orElse(null);
            int estado = respuesta.statusCode();
            if (estado == 304) {
                Files.deleteIfExists(destino);
                log.info("Horario sin cambios (304, ETag {})", etagAnterior);
                return new Resultado(false, null, etagAnterior, lastModifiedAnterior, 0);
            }
            if (estado != 200) {
                throw new IOException("La descarga del horario devolvió HTTP " + estado + " (" + propiedades.url() + ")");
            }
            boolean mismoEtag = etag != null && etag.equals(etagAnterior);
            boolean mismaFechaSinEtag = etag == null && lastModified != null && lastModified.equals(lastModifiedAnterior);
            if (!forzar && (mismoEtag || mismaFechaSinEtag)) {
                Files.deleteIfExists(destino);
                log.info("Horario sin cambios (200 con el mismo ETag {} o Last-Modified {})", etag, lastModified);
                return new Resultado(false, null, etag, lastModified, 0);
            }
            long bytes = Files.size(destino);
            if (bytes > BYTES_MAXIMOS) {
                throw new IOException("El horario descargado ocupa " + bytes + " bytes, más del máximo (" + BYTES_MAXIMOS + ")");
            }
            log.info("Horario descargado: {} bytes, ETag {}, Last-Modified {}", bytes, etag, lastModified);
            return new Resultado(true, destino, etag, lastModified, bytes);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(destino);
            throw e;
        }
    }
}
