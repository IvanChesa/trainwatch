package com.ivanchesa.trainwatch.spike;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Descarga los tres feeds GTFS Realtime de Renfe cada N segundos y guarda los .pb crudos.
 *
 * <p>Uso: {@code Captura <carpeta> [minutos=30] [segundos=20]}. Cada fichero se llama
 * {@code <instante UTC>_<feed>.pb}, el mismo formato que lee {@link Analisis}.
 */
public final class Captura {

    static final String BASE = "https://gtfsrt.renfe.com/";
    static final List<String> FEEDS = List.of("vehicle_positions", "trip_updates", "alerts");
    static final DateTimeFormatter NOMBRE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        Path carpeta = Path.of(args.length > 0 ? args[0] : "datos");
        int minutos = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int segundos = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        Files.createDirectories(carpeta);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        int muestras = minutos * 60 / segundos;
        for (int i = 0; i < muestras; i++) {
            long inicio = System.nanoTime();
            String marca = NOMBRE.format(Instant.now());
            for (String feed : FEEDS) {
                try {
                    byte[] cuerpo = descargar(http, feed);
                    FeedMessage mensaje = FeedMessage.parseFrom(cuerpo); // comprueba que decodifica
                    Files.write(carpeta.resolve(marca + "_" + feed + ".pb"), cuerpo);
                    System.out.printf("%s %s %d B, %d entidades%n", marca, feed, cuerpo.length, mensaje.getEntityCount());
                } catch (IOException e) {
                    System.out.printf("%s %s ERROR %s%n", marca, feed, e.getMessage());
                }
            }
            long espera = segundos * 1000L - (System.nanoTime() - inicio) / 1_000_000;
            if (espera > 0 && i < muestras - 1) {
                Thread.sleep(espera);
            }
        }
    }

    static byte[] descargar(HttpClient http, String feed) throws IOException, InterruptedException {
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(BASE + feed + ".pb"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "trainwatch-spike")
                .GET().build();
        HttpResponse<byte[]> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofByteArray());
        if (respuesta.statusCode() != 200) {
            throw new IOException("HTTP " + respuesta.statusCode());
        }
        return respuesta.body();
    }

    private Captura() {
    }
}
