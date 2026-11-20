package com.ivanchesa.trainwatch.horario;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;

/**
 * Un servidor HTTP local que hace de Renfe: sirve un ZIP con su {@code ETag} y su {@code Last-Modified}, y contesta
 * 304 si la petición trae el mismo {@code ETag} en {@code If-None-Match}. Apunta las cabeceras condicionales que le
 * llegan para que las pruebas lo comprueben.
 */
public final class ServidorGtfs implements AutoCloseable {

    public static final String LAST_MODIFIED = "Fri, 20 Nov 2026 03:08:54 GMT";

    private final HttpServer servidor;
    private volatile byte[] zip = new byte[0];
    private volatile String etag = "\"0\"";
    public final List<String> ifNoneMatchRecibidos = new CopyOnWriteArrayList<>();

    public ServidorGtfs() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/fomento_transit.zip", intercambio -> {
            String ifNoneMatch = intercambio.getRequestHeaders().getFirst("If-None-Match");
            ifNoneMatchRecibidos.add(ifNoneMatch == null ? "" : ifNoneMatch);
            intercambio.getResponseHeaders().add("ETag", etag);
            intercambio.getResponseHeaders().add("Last-Modified", LAST_MODIFIED);
            if (etag.equals(ifNoneMatch)) {
                intercambio.sendResponseHeaders(304, -1);
            } else {
                byte[] cuerpo = zip;
                intercambio.getResponseHeaders().add("Content-Type", "application/zip");
                intercambio.sendResponseHeaders(200, cuerpo.length);
                try (OutputStream salida = intercambio.getResponseBody()) {
                    salida.write(cuerpo);
                }
            }
            intercambio.close();
        });
        servidor.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/fomento_transit.zip");
    }

    public void sirve(byte[] zip, String etag) {
        this.zip = zip;
        this.etag = etag;
    }

    /** Un ZIP con los ficheros de {@code src/test/resources/gtfs/ejemplo}, con cambios opcionales por fichero. */
    public static byte[] zipDeEjemplo(Map<String, UnaryOperator<String>> cambios) {
        Path carpeta = Path.of("src/test/resources/gtfs/ejemplo");
        Map<String, String> ficheros = new TreeMap<>();
        try (Stream<Path> lista = Files.list(carpeta)) {
            for (Path p : lista.toList()) {
                ficheros.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cambios.forEach((nombre, cambio) -> ficheros.put(nombre, cambio.apply(ficheros.get(nombre))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream salida = new ZipOutputStream(bytes)) {
            for (var f : ficheros.entrySet()) {
                if (f.getValue() == null) {
                    continue;
                }
                salida.putNextEntry(new ZipEntry(f.getKey()));
                salida.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                salida.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static byte[] zipDeEjemplo() {
        return zipDeEjemplo(Map.of());
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
