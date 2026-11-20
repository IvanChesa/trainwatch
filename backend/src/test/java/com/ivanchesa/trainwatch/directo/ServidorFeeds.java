package com.ivanchesa.trainwatch.directo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Un servidor HTTP local que hace de {@code gtfsrt.renfe.com}: sirve un fichero por ruta con su {@code ETag}, contesta
 * 304 a {@code If-None-Match} y puede fallar con 500 o tardar a propósito.
 */
final class ServidorFeeds implements AutoCloseable {

    private final HttpServer servidor;
    private final Map<String, byte[]> ficheros = new ConcurrentHashMap<>();
    /** Fallos que quedan por dar (500); -1, siempre. */
    final AtomicInteger fallosPendientes = new AtomicInteger();
    volatile long esperaMs;
    final AtomicInteger peticiones = new AtomicInteger();
    final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();

    ServidorFeeds() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", this::atender);
        servidor.start();
    }

    URI url(String ruta) {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + ruta);
    }

    void servir(String ruta, byte[] contenido) {
        ficheros.put(ruta, contenido);
    }

    private static String etag(byte[] contenido) {
        return "\"" + Integer.toHexString(Arrays.hashCode(contenido)) + "\"";
    }

    private void atender(HttpExchange intercambio) throws IOException {
        try (intercambio) {
            peticiones.incrementAndGet();
            String condicion = intercambio.getRequestHeaders().getFirst("If-None-Match");
            ifNoneMatch.add(condicion == null ? "" : condicion);
            if (esperaMs > 0) {
                try {
                    Thread.sleep(esperaMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int fallos = fallosPendientes.get();
            if (fallos != 0) {
                if (fallos > 0) {
                    fallosPendientes.decrementAndGet();
                }
                intercambio.sendResponseHeaders(500, -1);
                return;
            }
            byte[] contenido = ficheros.get(intercambio.getRequestURI().getPath());
            if (contenido == null) {
                intercambio.sendResponseHeaders(404, -1);
                return;
            }
            String etag = etag(contenido);
            intercambio.getResponseHeaders().add("ETag", etag);
            intercambio.getResponseHeaders().add("Cache-Control", "max-age=30");
            if (etag.equals(condicion)) {
                intercambio.sendResponseHeaders(304, -1);
                return;
            }
            intercambio.sendResponseHeaders(200, contenido.length);
            try (OutputStream out = intercambio.getResponseBody()) {
                out.write(contenido);
            }
        }
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
