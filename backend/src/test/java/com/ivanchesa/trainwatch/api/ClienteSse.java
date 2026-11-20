package com.ivanchesa.trainwatch.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Un cliente SSE mínimo para las pruebas, como el {@code EventSource} del navegador: lee el flujo en un hilo virtual,
 * separa los eventos por líneas en blanco y los deja en una cola. Lee el cuerpo en UTF-8, como manda el estándar.
 */
final class ClienteSse implements AutoCloseable {

    record Evento(String id, String nombre, String datos, Long reintento) {
    }

    private final HttpClient http = HttpClient.newHttpClient();
    final BlockingQueue<Evento> eventos = new LinkedBlockingQueue<>();
    final HttpResponse<InputStream> respuesta;
    private final Thread lector;

    ClienteSse(URI uri, String ultimoId) throws IOException, InterruptedException {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10));
        if (ultimoId != null) {
            peticion.header("Last-Event-ID", ultimoId);
        }
        respuesta = http.send(peticion.build(), HttpResponse.BodyHandlers.ofInputStream());
        // Solo un 200 es un flujo de eventos; el cuerpo de un error (ProblemDetail) se deja para la prueba.
        lector = respuesta.statusCode() == 200 ? Thread.ofVirtual().start(this::leer) : null;
    }

    int estado() {
        return respuesta.statusCode();
    }

    Evento siguiente(Duration espera) throws InterruptedException {
        return eventos.poll(espera.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** El siguiente evento con ese nombre (descarta los demás, como los latidos), o {@code null}. */
    Evento siguiente(String nombre, Duration espera) throws InterruptedException {
        long fin = System.nanoTime() + espera.toNanos();
        while (System.nanoTime() < fin) {
            Evento e = eventos.poll(Math.max(1, (fin - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (e != null && nombre.equals(e.nombre())) {
                return e;
            }
        }
        return null;
    }

    private void leer() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(respuesta.body(), StandardCharsets.UTF_8))) {
            String id = null;
            String nombre = "message";
            StringBuilder datos = new StringBuilder();
            Long reintento = null;
            String linea;
            while ((linea = r.readLine()) != null) {
                if (linea.isEmpty()) {
                    if (!datos.isEmpty() || reintento != null) {
                        eventos.add(new Evento(id, nombre, datos.toString(), reintento));
                    }
                    id = null;
                    nombre = "message";
                    datos.setLength(0);
                    reintento = null;
                    continue;
                }
                int dos = linea.indexOf(':');
                String campo = dos < 0 ? linea : linea.substring(0, dos);
                String valor = dos < 0 ? "" : linea.substring(dos + 1);
                if (valor.startsWith(" ")) {
                    valor = valor.substring(1);
                }
                switch (campo) {
                    case "id" -> id = valor;
                    case "event" -> nombre = valor;
                    case "data" -> datos.append(datos.isEmpty() ? "" : "\n").append(valor);
                    case "retry" -> reintento = Long.valueOf(valor);
                    default -> { // comentario u otro campo: se ignora
                    }
                }
            }
        } catch (IOException e) {
            // conexión cerrada
        }
    }

    @Override
    public void close() {
        try {
            respuesta.body().close();
        } catch (IOException e) {
            // da igual
        }
        if (lector != null) {
            lector.interrupt();
        }
        http.close();
    }
}
