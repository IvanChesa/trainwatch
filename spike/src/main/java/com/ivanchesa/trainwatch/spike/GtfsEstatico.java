package com.ivanchesa.trainwatch.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Carga el GTFS estático de Renfe Cercanías (fomento_transit.zip).
 *
 * <p>Los campos vienen rellenos con espacios, así que todo se recorta. {@code stop_times.txt}
 * pesa unos 270 MB: solo se guarda el detalle de los viajes que interesan (los vistos en directo)
 * y un resumen (primera hora, última hora y número de paradas) de los viajes de tren de la fecha pedida
 * (sin los autobuses, {@code route_type} 3).
 */
public final class GtfsEstatico {

    public record Ruta(String id, String nombreCorto, String nombreLargo, String tipo) {
    }

    public record Viaje(String id, String rutaId, String servicioId, String shapeId) {
    }

    /** Hora programada en segundos desde la medianoche del día de servicio (puede pasar de 24 h). */
    public record ParadaProgramada(String paradaId, int secuencia, int llegada, int salida) {
    }

    /** Resumen de un viaje del día: primera salida, última llegada y número de paradas. */
    public record Resumen(int inicio, int fin, int paradas) {
    }

    public final Map<String, Long> tablas = new LinkedHashMap<>();       // fichero -> bytes sin comprimir
    public final Map<String, Long> filas = new LinkedHashMap<>();        // fichero -> filas de datos
    public final Map<String, Ruta> rutas = new HashMap<>();
    public final Map<String, Viaje> viajes = new HashMap<>();
    public final Set<String> serviciosDelDia = new HashSet<>();
    public final Set<String> shapes = new HashSet<>();
    public final Map<String, double[]> paradas = new HashMap<>();        // stop_id -> {lat, lon}
    public final Map<String, String> nombresParada = new HashMap<>();
    public final Map<String, List<ParadaProgramada>> horarios = new HashMap<>();
    public final Map<String, Resumen> resumenDelDia = new HashMap<>();

    public static GtfsEstatico cargar(Path zip, LocalDate dia, Set<String> viajesConDetalle) throws IOException {
        GtfsEstatico g = new GtfsEstatico();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().forEach(e -> g.tablas.put(e.getName(), e.getSize()));

            leer(z, "routes.txt", g, c -> g.rutas.put(c.get("route_id"),
                    new Ruta(c.get("route_id"), c.get("route_short_name"), c.get("route_long_name"), c.get("route_type"))));

            String fecha = dia.format(DateTimeFormatter.BASIC_ISO_DATE);
            String columnaDia = dia.getDayOfWeek().name().toLowerCase();
            leer(z, "calendar.txt", g, c -> {
                boolean enRango = c.get("start_date").compareTo(fecha) <= 0 && c.get("end_date").compareTo(fecha) >= 0;
                if (enRango && "1".equals(c.get(columnaDia))) {
                    g.serviciosDelDia.add(c.get("service_id"));
                }
            });

            leer(z, "trips.txt", g, c -> g.viajes.put(c.get("trip_id"),
                    new Viaje(c.get("trip_id"), c.get("route_id"), c.get("service_id"), c.get("shape_id"))));

            leer(z, "stops.txt", g, c -> {
                g.paradas.put(c.get("stop_id"), new double[]{
                        Double.parseDouble(c.get("stop_lat")), Double.parseDouble(c.get("stop_lon"))});
                g.nombresParada.put(c.get("stop_id"), c.get("stop_name"));
            });
            leer(z, "agency.txt", g, c -> { });
            leer(z, "transfers.txt", g, c -> { });

            leer(z, "shapes.txt", g, c -> g.shapes.add(c.get("shape_id")));

            Map<String, int[]> resumen = new HashMap<>();
            leer(z, "stop_times.txt", g, c -> {
                String viaje = c.get("trip_id");
                Viaje v = g.viajes.get(viaje);
                Ruta ruta = v == null ? null : g.rutas.get(v.rutaId());
                boolean autobus = ruta != null && "3".equals(ruta.tipo());
                boolean delDia = v != null && !autobus && g.serviciosDelDia.contains(v.servicioId());
                boolean detalle = viajesConDetalle.contains(viaje);
                if (!delDia && !detalle) {
                    return;
                }
                int llegada = segundos(c.get("arrival_time"));
                int salida = segundos(c.get("departure_time"));
                if (delDia) {
                    int[] r = resumen.computeIfAbsent(viaje, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0});
                    r[0] = Math.min(r[0], salida);
                    r[1] = Math.max(r[1], llegada);
                    r[2]++;
                }
                if (detalle) {
                    g.horarios.computeIfAbsent(viaje, k -> new ArrayList<>()).add(new ParadaProgramada(
                            c.get("stop_id"), Integer.parseInt(c.get("stop_sequence")), llegada, salida));
                }
            });
            resumen.forEach((k, r) -> g.resumenDelDia.put(k, new Resumen(r[0], r[1], r[2])));
            g.horarios.values().forEach(l -> l.sort(java.util.Comparator.comparingInt(ParadaProgramada::secuencia)));
        }
        return g;
    }

    /** Lee un CSV del ZIP fila a fila, con las columnas por nombre y los valores recortados. */
    private static void leer(ZipFile z, String nombre, GtfsEstatico g, Consumer<Columnas> fila) throws IOException {
        ZipEntry e = z.getEntry(nombre);
        if (e == null) {
            return;
        }
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8), 1 << 16)) {
            String cabecera = r.readLine();
            if (cabecera == null) {
                return;
            }
            if (cabecera.startsWith("﻿")) {
                cabecera = cabecera.substring(1);
            }
            Map<String, Integer> indice = new HashMap<>();
            List<String> nombres = campos(cabecera);
            for (int i = 0; i < nombres.size(); i++) {
                indice.put(nombres.get(i), i);
            }
            String linea;
            while ((linea = r.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                n++;
                fila.accept(new Columnas(indice, campos(linea)));
            }
        }
        g.filas.put(nombre, n);
    }

    record Columnas(Map<String, Integer> indice, List<String> valores) {
        String get(String columna) {
            Integer i = indice.get(columna);
            return i == null || i >= valores.size() ? "" : valores.get(i);
        }
    }

    /** CSV sencillo con comillas; recorta los espacios de relleno. */
    static List<String> campos(String linea) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char ch = linea.charAt(i);
            if (ch == '"') {
                if (comillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    comillas = !comillas;
                }
            } else if (ch == ',' && !comillas) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString().trim());
        return out;
    }

    static int segundos(String hhmmss) {
        String[] p = hhmmss.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
    }

    private GtfsEstatico() {
    }
}
