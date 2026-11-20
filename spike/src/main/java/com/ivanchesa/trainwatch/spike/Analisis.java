package com.ivanchesa.trainwatch.spike;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.VehicleStopStatus;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Analiza una captura de los feeds GTFS Realtime de Renfe contra el GTFS estático y escribe un
 * informe en Markdown con las cifras de calidad y volumen.
 *
 * <p>Uso: {@code Analisis <carpeta de .pb> <fomento_transit.zip> <salida.md> [fecha AAAA-MM-DD]}.
 */
public final class Analisis {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Locale ES = Locale.forLanguageTag("es-ES");
    static final DateTimeFormatter NOMBRE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(MADRID);

    /** trip_id de Renfe: núcleo (2 cifras) + código de día (p. ej. 23V) + número de tren + línea. */
    static final Pattern VIAJE = Pattern.compile("^(\\d{2})(\\d{2}[A-Z])(\\d+)([A-Za-z].*)$");

    static final Map<String, String> NUCLEOS = new LinkedHashMap<>();

    static {
        NUCLEOS.put("10", "Madrid");
        NUCLEOS.put("20", "Asturias");
        NUCLEOS.put("30", "Sevilla");
        NUCLEOS.put("31", "Cádiz");
        NUCLEOS.put("32", "Málaga");
        NUCLEOS.put("40", "Valencia");
        NUCLEOS.put("41", "Murcia/Alicante");
        NUCLEOS.put("45", "Cartagena (ancho métrico)");
        NUCLEOS.put("46", "Ferrol (ancho métrico)");
        NUCLEOS.put("47", "León (ancho métrico)");
        NUCLEOS.put("51", "Rodalies de Catalunya");
        NUCLEOS.put("60", "Bilbao");
        NUCLEOS.put("61", "San Sebastián");
        NUCLEOS.put("62", "Santander");
        NUCLEOS.put("70", "Zaragoza");
    }

    record Muestra(Instant descarga, FeedMessage vp, FeedMessage tu, FeedMessage al, Map<String, Integer> bytes) {
    }

    /** Lo que sabemos de cada viaje visto en directo durante la captura. */
    static final class ViajeVisto {
        String id;
        String nucleo;
        String linea;
        int observaciones;
        int sinPosicion;
        boolean enHorario;        // trip_id exacto en trips.txt
        boolean servicioDelDia;   // y su service_id circula hoy
        boolean porNumero;        // no está exacto, pero el mismo núcleo + número + línea sí está (otro día)
        boolean conTripUpdate;
    }

    public static void main(String[] args) throws Exception {
        Path datos = Path.of(args[0]);
        Path zip = Path.of(args[1]);
        Path salida = Path.of(args[2]);
        LocalDate dia = args.length > 3 ? LocalDate.parse(args[3]) : null;

        List<Muestra> muestras = leerMuestras(datos);
        if (muestras.isEmpty()) {
            throw new IllegalStateException("No hay muestras completas en " + datos);
        }
        if (dia == null) {
            dia = muestras.getFirst().descarga().atZone(MADRID).toLocalDate();
        }

        Set<String> viajesEnDirecto = new HashSet<>();
        for (Muestra m : muestras) {
            m.vp().getEntityList().forEach(e -> viajesEnDirecto.add(e.getVehicle().getTrip().getTripId()));
            m.tu().getEntityList().forEach(e -> viajesEnDirecto.add(e.getTripUpdate().getTrip().getTripId()));
        }
        long t0 = System.nanoTime();
        GtfsEstatico g = GtfsEstatico.cargar(zip, dia, viajesEnDirecto);
        long msCarga = (System.nanoTime() - t0) / 1_000_000;

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(salida, StandardCharsets.UTF_8))) {
            new Analisis(out, muestras, g, dia, msCarga).escribir();
        }
        System.out.println("Informe escrito en " + salida);
    }

    // ---------------------------------------------------------------- lectura

    static List<Muestra> leerMuestras(Path carpeta) throws IOException {
        Map<String, Map<String, Path>> porMarca = new TreeMap<>();
        try (Stream<Path> s = Files.list(carpeta)) {
            s.filter(p -> p.getFileName().toString().endsWith(".pb")).forEach(p -> {
                String n = p.getFileName().toString();
                int i = n.indexOf('_');
                porMarca.computeIfAbsent(n.substring(0, i), k -> new HashMap<>())
                        .put(n.substring(i + 1, n.length() - 3), p);
            });
        }
        List<Muestra> out = new ArrayList<>();
        for (var e : porMarca.entrySet()) {
            Map<String, Path> f = e.getValue();
            try {
                Map<String, Integer> bytes = new HashMap<>();
                FeedMessage vp = leer(f.get("vehicle_positions"), bytes, "vehicle_positions");
                FeedMessage tu = leer(f.get("trip_updates"), bytes, "trip_updates");
                FeedMessage al = leer(f.get("alerts"), bytes, "alerts");
                out.add(new Muestra(Instant.from(NOMBRE.parse(e.getKey())), vp, tu, al, bytes));
            } catch (IOException | RuntimeException ex) {
                System.err.println("Muestra descartada " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return out;
    }

    static FeedMessage leer(Path p, Map<String, Integer> bytes, String feed) throws IOException {
        if (p == null) {
            throw new IOException("falta " + feed);
        }
        byte[] b = Files.readAllBytes(p);
        bytes.put(feed, b.length);
        return FeedMessage.parseFrom(b);
    }

    // ---------------------------------------------------------------- análisis

    private final PrintWriter out;
    private final List<Muestra> muestras;
    private final GtfsEstatico g;
    private final LocalDate dia;
    private final long msCarga;
    private final long medianocheDia;   // epoch de las 00:00 del día de servicio (hora de Madrid)
    private final Map<String, Set<String>> indicePorNumero = new HashMap<>();
    private double coberturaHorario = 1;   // fracción de viajes en marcha que salen en el directo (§4)

    Analisis(PrintWriter out, List<Muestra> muestras, GtfsEstatico g, LocalDate dia, long msCarga) {
        this.out = out;
        this.muestras = muestras;
        this.g = g;
        this.dia = dia;
        this.msCarga = msCarga;
        // GTFS: las horas cuentan desde «mediodía menos 12 h», que es la medianoche salvo en los días de cambio de hora.
        this.medianocheDia = dia.atTime(12, 0).atZone(MADRID).minusHours(12).toEpochSecond();
        for (String id : g.viajes.keySet()) {
            String clave = claveNumero(id);
            if (clave != null) {
                indicePorNumero.computeIfAbsent(clave, k -> new HashSet<>()).add(id);
            }
        }
    }

    void escribir() {
        Instant ini = muestras.getFirst().descarga();
        Instant fin = muestras.getLast().descarga();
        out.printf("# Resultados de la captura (generado por `Analisis.java`)%n%n");
        out.printf("- Día de servicio: **%s** (%s)%n", dia, dia.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, ES));
        out.printf("- Captura: de %s a %s (hora de Madrid), **%d muestras**, %s min%n",
                HORA.format(ini), HORA.format(fin), muestras.size(), num((fin.getEpochSecond() - ini.getEpochSecond()) / 60.0, 1));
        out.printf("- GTFS estático cargado en %s s%n%n", num(msCarga / 1000.0, 1));

        estatico();
        frescura();
        Map<String, ViajeVisto> vistos = cobertura();
        programadosFrenteADirecto();
        retrasos();
        diagnostico(vistos);
        avisos();
        volumen(vistos);
    }

    // ------------------------------------------------ 1. GTFS estático

    void estatico() {
        out.printf("## 1. GTFS estático%n%n| Fichero | Tamaño sin comprimir | Filas |%n|---|---:|---:|%n");
        g.tablas.forEach((n, b) -> out.printf("| `%s` | %s MB | %s |%n", n, num(b / 1_048_576.0, 1),
                num(g.filas.getOrDefault(n, -1L))));
        long viajesDia = g.viajes.values().stream().filter(v -> g.serviciosDelDia.contains(v.servicioId())).count();
        long conShape = g.viajes.values().stream()
                .filter(v -> g.serviciosDelDia.contains(v.servicioId()))
                .filter(v -> !v.shapeId().isEmpty() && g.shapes.contains(v.shapeId())).count();
        Set<String> nucleosConShape = new TreeSet<>();
        g.shapes.forEach(s -> nucleosConShape.add(s.contains("_") ? s.substring(0, s.indexOf('_')) : s));
        out.printf("%n- Servicios (service_id) que circulan el %s: %d. Viajes del día: **%s**.%n", dia, g.serviciosDelDia.size(), num(viajesDia));
        out.printf("- Viajes del día con `shape_id` presente en `shapes.txt`: %s de %s (%s).%n", num(conShape), num(viajesDia), pct(conShape, viajesDia));
        out.printf("- `shapes.txt`: %d recorridos distintos; prefijos: %s.%n", g.shapes.size(), nucleosConShape);
        out.printf("- `calendar_dates.txt`: %s.%n%n", g.tablas.containsKey("calendar_dates.txt") ? "presente (no se ha leído)" : "no viene en el ZIP");
    }

    // ------------------------------------------------ 2. Frescura

    void frescura() {
        out.printf("## 2. Cada cuánto se actualiza el feed%n%n");
        out.printf("Con muestras cada 20 s, un intervalo de 20 s entre cabeceras solo dice que el feed cambia **al menos** tan a menudo como se muestrea. "
                + "La antigüedad se mide desde el inicio de la muestra (redondeado al segundo), antes de descargar los tres feeds, "
                + "así que en `trip_updates` y `alerts` puede salir algo menor de lo real y hasta negativa.%n%n");
        out.printf("| Feed | Cabeceras distintas / muestras | Intervalo entre cabeceras nuevas (mediana · mín · máx) | Antigüedad de la cabecera al descargar (mediana · p95 · máx) |%n|---|---:|---|---|%n");
        for (String feed : List.of("vehicle_positions", "trip_updates", "alerts")) {
            List<Long> cabeceras = new ArrayList<>();
            List<Long> edad = new ArrayList<>();
            for (Muestra m : muestras) {
                FeedMessage f = switch (feed) {
                    case "vehicle_positions" -> m.vp();
                    case "trip_updates" -> m.tu();
                    default -> m.al();
                };
                long ts = f.getHeader().getTimestamp();
                cabeceras.add(ts);
                edad.add(m.descarga().getEpochSecond() - ts);
            }
            List<Long> distintas = cabeceras.stream().distinct().sorted().toList();
            List<Long> saltos = new ArrayList<>();
            for (int i = 1; i < distintas.size(); i++) {
                saltos.add(distintas.get(i) - distintas.get(i - 1));
            }
            out.printf("| `%s` | %d / %d | %s | %s |%n", feed, distintas.size(), muestras.size(),
                    saltos.isEmpty() ? "—" : "%s · %s · %s s".formatted(p(saltos, 50), Collections.min(saltos), Collections.max(saltos)),
                    "%s · %s · %s s".formatted(p(edad, 50), p(edad, 95), Collections.max(edad)));
        }

        // Marca de tiempo de cada tren frente a la cabecera, e intervalo entre marcas nuevas del mismo tren.
        List<Long> edadTren = new ArrayList<>();
        Map<String, Long> ultimaMarca = new HashMap<>();
        Map<String, double[]> ultimaPos = new HashMap<>();
        List<Long> intervaloTren = new ArrayList<>();
        long pares = 0;
        long paresMovidos = 0;
        long marcaIgualCabecera = 0;
        long total = 0;
        for (Muestra m : muestras) {
            long cab = m.vp().getHeader().getTimestamp();
            for (FeedEntity e : m.vp().getEntityList()) {
                VehiclePosition v = e.getVehicle();
                if (!v.hasTimestamp()) {
                    continue;
                }
                total++;
                edadTren.add(cab - v.getTimestamp());
                if (Math.abs(cab - v.getTimestamp()) <= 5) {
                    marcaIgualCabecera++;
                }
                String clave = v.getTrip().getTripId();
                Long previa = ultimaMarca.get(clave);
                if (previa != null && v.getTimestamp() != previa) {
                    intervaloTren.add(v.getTimestamp() - previa);
                }
                ultimaMarca.put(clave, v.getTimestamp());
                if (!v.hasPosition()) {
                    ultimaPos.remove(clave);
                } else {
                    double[] pos = {v.getPosition().getLatitude(), v.getPosition().getLongitude()};
                    double[] antes = ultimaPos.put(clave, pos);
                    if (antes != null) {
                        pares++;
                        if (antes[0] != pos[0] || antes[1] != pos[1]) {
                            paresMovidos++;
                        }
                    }
                }
            }
        }
        out.printf("%n- Marca de tiempo de cada tren respecto a la cabecera del feed: mediana %s s, p95 %s s, máx. %s s. "
                        + "A ≤ 5 s de la cabecera: %s de las observaciones.%n",
                p(edadTren, 50), p(edadTren, 95), edadTren.isEmpty() ? 0 : Collections.max(edadTren), pct(marcaIgualCabecera, total));
        out.printf("- Intervalo entre marcas nuevas del mismo tren: mediana %s s, p25 %s s, p75 %s s.%n",
                p(intervaloTren, 50), p(intervaloTren, 25), p(intervaloTren, 75));
        out.printf("- Entre dos muestras seguidas, el tren cambia de coordenadas en el %s de los casos (%s pares).%n%n",
                pct(paresMovidos, pares), num(pares));
    }

    // ------------------------------------------------ 3. Cobertura

    Map<String, ViajeVisto> cobertura() {
        Map<String, ViajeVisto> vistos = new LinkedHashMap<>();
        Set<String> conTu = new HashSet<>();
        long tuTotal = 0;
        long tuEnHorario = 0;
        Set<String> viajesTu = new HashSet<>();
        for (Muestra m : muestras) {
            for (FeedEntity e : m.tu().getEntityList()) {
                String id = e.getTripUpdate().getTrip().getTripId();
                conTu.add(id);
                viajesTu.add(id);
            }
        }
        for (String id : viajesTu) {
            tuTotal++;
            if (g.viajes.containsKey(id)) {
                tuEnHorario++;
            }
        }
        long obsTotal = 0;
        long obsEnHorario = 0;
        Map<String, Integer> sinPosicionPorNucleo = new TreeMap<>();
        Map<String, Integer> obsPorNucleo = new TreeMap<>();
        for (Muestra m : muestras) {
            for (FeedEntity e : m.vp().getEntityList()) {
                VehiclePosition v = e.getVehicle();
                String id = v.getTrip().getTripId();
                ViajeVisto vv = vistos.computeIfAbsent(id, k -> nuevoVisto(k, v.getVehicle().getLabel()));
                vv.observaciones++;
                obsTotal++;
                obsPorNucleo.merge(vv.nucleo, 1, Integer::sum);
                if (vv.enHorario) {
                    obsEnHorario++;
                }
                boolean sinPos = !v.hasPosition() || (v.getPosition().getLatitude() == 0 && v.getPosition().getLongitude() == 0);
                if (sinPos) {
                    vv.sinPosicion++;
                    sinPosicionPorNucleo.merge(vv.nucleo, 1, Integer::sum);
                }
            }
        }
        vistos.values().forEach(v -> v.conTripUpdate = conTu.contains(v.id));

        out.printf("## 3. ¿Encuentran los trenes en directo su viaje en el horario?%n%n");
        out.printf("Se cuenta cada `trip_id` distinto visto en `vehicle_positions` durante la captura. "
                + "«Exacto» = el `trip_id` está en `trips.txt`. «Por número» = no está, pero sí el mismo núcleo + número de tren + línea con otro código de día. "
                + "«Con retraso» = el viaje aparece también en `trip_updates`.%n%n");

        out.printf("### Por núcleo%n%n");
        tablaCobertura(vistos, v -> v.nucleo + " " + NUCLEOS.getOrDefault(v.nucleo, "?"));
        out.printf("%n### Rodalies de Catalunya, por línea%n%n");
        tablaCobertura(vistos.entrySet().stream().filter(e -> "51".equals(e.getValue().nucleo))
                .collect(LinkedHashMap::new, (mm, e) -> mm.put(e.getKey(), e.getValue()), Map::putAll), v -> v.linea);
        out.printf("%n### Resto de núcleos, por línea%n%n");
        tablaCobertura(vistos.entrySet().stream().filter(e -> !"51".equals(e.getValue().nucleo))
                .collect(LinkedHashMap::new, (mm, e) -> mm.put(e.getKey(), e.getValue()), Map::putAll),
                v -> v.nucleo + " " + v.linea);

        out.printf("%n- Ponderado por observaciones (tren × muestra): %s de %s observaciones tienen su viaje exacto (%s).%n",
                num(obsEnHorario), num(obsTotal), pct(obsEnHorario, obsTotal));
        out.printf("- `trip_updates`: %s viajes distintos, %s con su viaje en el horario (%s).%n", num(tuTotal), num(tuEnHorario), pct(tuEnHorario, tuTotal));
        long sinTu = vistos.values().stream().filter(v -> !v.conTripUpdate).count();
        out.printf("- Trenes en `vehicle_positions` sin entrada en `trip_updates`: %d de %d.%n", sinTu, vistos.size());
        out.printf("- Observaciones sin coordenadas (sin `position` o 0,0): %s; por núcleo %s (de %s).%n",
                num(sinPosicionPorNucleo.values().stream().mapToLong(Integer::longValue).sum()), sinPosicionPorNucleo, obsPorNucleo);

        List<String> noEncontrados = vistos.values().stream().filter(v -> !v.enHorario).map(v -> v.id).sorted().toList();
        out.printf("- `trip_id` sin viaje exacto (%d): %s%n%n", noEncontrados.size(),
                noEncontrados.stream().limit(60).map(s -> "`" + s + "`").toList());
        return vistos;
    }

    ViajeVisto nuevoVisto(String id, String etiqueta) {
        ViajeVisto v = new ViajeVisto();
        v.id = id;
        Matcher mt = VIAJE.matcher(id);
        Matcher especial = Pattern.compile("^SPECIAL_(\\d{2})_").matcher(id);
        v.nucleo = mt.matches() ? mt.group(1) : especial.find() ? especial.group(1) : "??";
        String desdeEtiqueta = etiqueta.contains("-") ? etiqueta.substring(0, etiqueta.indexOf('-')) : etiqueta;
        v.linea = !desdeEtiqueta.isBlank() ? desdeEtiqueta : (mt.matches() ? mt.group(4) : "?");
        GtfsEstatico.Viaje viaje = g.viajes.get(id);
        v.enHorario = viaje != null;
        v.servicioDelDia = viaje != null && g.serviciosDelDia.contains(viaje.servicioId());
        String clave = claveNumero(id);
        v.porNumero = !v.enHorario && clave != null && indicePorNumero.containsKey(clave);
        return v;
    }

    static String claveNumero(String id) {
        Matcher mt = VIAJE.matcher(id);
        return mt.matches() ? mt.group(1) + "|" + mt.group(3) + "|" + mt.group(4) : null;
    }

    interface Agrupador {
        String grupo(ViajeVisto v);
    }

    void tablaCobertura(Map<String, ViajeVisto> vistos, Agrupador agrupar) {
        Map<String, long[]> t = new TreeMap<>();
        for (ViajeVisto v : vistos.values()) {
            long[] c = t.computeIfAbsent(agrupar.grupo(v), k -> new long[6]);
            c[0]++;
            if (v.enHorario) {
                c[1]++;
            }
            if (v.servicioDelDia) {
                c[2]++;
            }
            if (v.porNumero) {
                c[3]++;
            }
            if (v.conTripUpdate) {
                c[4]++;
            }
            if (v.sinPosicion == v.observaciones) {
                c[5]++;
            }
        }
        out.printf("| Grupo | Trenes distintos | Exacto | %% exacto | Del día | Por número | Con retraso | Nunca con coordenadas |%n|---|---:|---:|---:|---:|---:|---:|---:|%n");
        long[] tot = new long[6];
        t.forEach((k, c) -> {
            out.printf("| %s | %d | %d | %s | %d | %d | %d | %d |%n", k, c[0], c[1], pct(c[1], c[0]), c[2], c[3], c[4], c[5]);
            for (int i = 0; i < 6; i++) {
                tot[i] += c[i];
            }
        });
        out.printf("| **Total** | **%d** | **%d** | **%s** | %d | %d | %d | %d |%n", tot[0], tot[1], pct(tot[1], tot[0]), tot[2], tot[3], tot[4], tot[5]);
    }

    // ------------------------------------------------ 4. Programados frente a directo

    void programadosFrenteADirecto() {
        // En cada muestra: viajes de tren (sin autobuses) que según el horario están entre su primera salida y su
        // última llegada, y cuántos de ellos salen en vehicle_positions con el mismo trip_id. Además, cuántos trenes
        // del directo están esperando en origen antes de su hora de salida.
        Map<String, long[]> c = new TreeMap<>();
        long esperando = 0;
        long totalDirecto = 0;
        long canceladosEnMarcha = 0;
        for (Muestra m : muestras) {
            long t = m.descarga().getEpochSecond() - medianocheDia;
            Set<String> enDirecto = new HashSet<>();
            m.vp().getEntityList().forEach(e -> enDirecto.add(e.getVehicle().getTrip().getTripId()));
            for (String id : enDirecto) {
                totalDirecto++;
                GtfsEstatico.Resumen r = g.resumenDelDia.get(id);
                if (r != null && t < r.inicio()) {
                    esperando++;
                }
            }
            Set<String> cancelados = new HashSet<>();
            m.tu().getEntityList().stream().map(FeedEntity::getTripUpdate)
                    .filter(tu -> tu.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED)
                    .forEach(tu -> cancelados.add(tu.getTrip().getTripId()));
            canceladosEnMarcha += cancelados.stream().filter(id -> {
                GtfsEstatico.Resumen r = g.resumenDelDia.get(id);
                return r != null && r.inicio() <= t && t <= r.fin();
            }).count();
            g.resumenDelDia.forEach((id, r) -> {
                if (r.inicio() <= t && t <= r.fin() && !cancelados.contains(id)) {
                    String k = grupoProgramado(id);
                    long[] a = c.computeIfAbsent(k, x -> new long[2]);
                    a[0]++;
                    if (enDirecto.contains(id)) {
                        a[1]++;
                    }
                }
            });
        }
        int n = muestras.size();
        long buses = g.viajes.values().stream()
                .filter(v -> g.serviciosDelDia.contains(v.servicioId()))
                .filter(v -> g.rutas.containsKey(v.rutaId()) && "3".equals(g.rutas.get(v.rutaId()).tipo())).count();
        out.printf("## 4. Al revés: ¿salen en el directo los trenes que el horario da en marcha?%n%n");
        out.printf("Media por muestra. Un viaje cuenta como «en marcha» entre su primera salida y su última llegada programadas. "
                + "Se excluyen los %s viajes de autobús del día (`route_type` 3: servicios alternativos por obras), que nunca salen en el directo, "
                + "y los que `trip_updates` marca como `CANCELED` en esa muestra (%s por muestra).%n%n", num(buses), num(canceladosEnMarcha / (double) muestras.size(), 1));
        Map<String, Integer> delDia = new TreeMap<>();
        g.resumenDelDia.keySet().forEach(id -> delDia.merge(grupoProgramado(id), 1, Integer::sum));
        Set<String> grupos = new TreeSet<>(delDia.keySet());
        grupos.addAll(c.keySet());
        out.printf("| Núcleo · línea | Viajes de tren del día | En marcha según horario | De ellos, en el directo | %% en directo |%n|---|---:|---:|---:|---:|%n");
        long[] tot = new long[2];
        for (String k : grupos) {
            long[] a = c.getOrDefault(k, new long[2]);
            out.printf("| %s | %d | %s | %s | %s |%n", k, delDia.getOrDefault(k, 0), num(a[0] / (double) n, 1), num(a[1] / (double) n, 1), pct(a[1], a[0]));
            tot[0] += a[0];
            tot[1] += a[1];
        }
        coberturaHorario = tot[0] == 0 ? 1 : tot[1] / (double) tot[0];
        out.printf("| **Total** | **%d** | **%s** | **%s** | **%s** |%n%n", g.resumenDelDia.size(),
                num(tot[0] / (double) n, 1), num(tot[1] / (double) n, 1), pct(tot[1], tot[0]));
        out.printf("- Del lado contrario, %s de los trenes del directo (%s por muestra) aún no han empezado su viaje: esperan en la estación de origen "
                + "antes de su hora de salida (ver §5 bis).%n%n", pct(esperando, totalDirecto), num(esperando / (double) n, 1));
    }

    String grupoProgramado(String id) {
        String n = id.substring(0, 2);
        String base = n + " " + NUCLEOS.getOrDefault(n, "?");
        if (!"51".equals(n)) {
            return base;
        }
        GtfsEstatico.Viaje v = g.viajes.get(id);
        GtfsEstatico.Ruta ruta = v == null ? null : g.rutas.get(v.rutaId());
        return base + " · " + (ruta == null ? "?" : ruta.nombreCorto());
    }

    // ------------------------------------------------ 5. Retrasos

    void retrasos() {
        out.printf("## 5. Retrasos de `trip_updates`%n%n");
        Map<Integer, Integer> paradasPorTu = new TreeMap<>();
        Map<String, Integer> relacion = new TreeMap<>();
        long conDelayViaje = 0;
        long conDelayParada = 0;
        long multiplo60 = 0;
        long totalDelays = 0;
        long coherentes = 0;
        long comprobables = 0;
        List<Long> desvioHorario = new ArrayList<>();
        Map<String, Integer> ultimoRetraso = new HashMap<>();

        // Coherencia con las posiciones: cuando un tren aparece por primera vez detenido en una parada,
        // su retraso «observado» es la marca del tren menos la llegada programada a esa parada.
        Map<String, Boolean> visto = new HashMap<>();
        List<Long> diferencia = new ArrayList<>();
        long mismaParada = 0;
        long comparablesParada = 0;
        boolean primera = true;
        Set<String> viajesVistos = new HashSet<>();
        for (Muestra m : muestras) {
            Map<String, TripUpdate> tuPorViaje = new HashMap<>();
            for (FeedEntity e : m.tu().getEntityList()) {
                TripUpdate tu = e.getTripUpdate();
                tuPorViaje.put(tu.getTrip().getTripId(), tu);
                if (primera) {
                    paradasPorTu.merge(tu.getStopTimeUpdateCount(), 1, Integer::sum);
                    relacion.merge(tu.getTrip().getScheduleRelationship().name(), 1, Integer::sum);
                    if (tu.hasDelay()) {
                        conDelayViaje++;
                    }
                }
                Integer r = retraso(tu);
                if (r != null) {
                    ultimoRetraso.put(tu.getTrip().getTripId(), r);
                    if (r != 0) {
                        totalDelays++;
                        if (r % 60 == 0) {
                            multiplo60++;
                        }
                    }
                }
                List<GtfsEstatico.ParadaProgramada> h = g.horarios.get(tu.getTrip().getTripId());
                for (StopTimeUpdate stu : tu.getStopTimeUpdateList()) {
                    if (primera && stu.hasArrival() && stu.getArrival().hasDelay()) {
                        conDelayParada++;
                    }
                    if (h != null && stu.hasArrival() && stu.getArrival().hasTime() && stu.getArrival().hasDelay()) {
                        GtfsEstatico.ParadaProgramada pp = buscar(h, stu.getStopId());
                        if (pp != null) {
                            comprobables++;
                            long d = (stu.getArrival().getTime() - stu.getArrival().getDelay()) - (medianocheDia + pp.llegada());
                            desvioHorario.add(Math.abs(d));
                            if (Math.abs(d) <= 60) {
                                coherentes++;
                            }
                        }
                    }
                }
            }
            for (FeedEntity e : m.vp().getEntityList()) {
                VehiclePosition v = e.getVehicle();
                String id = v.getTrip().getTripId();
                TripUpdate tu = tuPorViaje.get(id);
                if (tu != null && v.hasStopId() && !"00000".equals(v.getStopId()) && tu.getStopTimeUpdateCount() > 0
                        && v.getCurrentStatus() != VehicleStopStatus.STOPPED_AT) {
                    comparablesParada++;
                    if (tu.getStopTimeUpdate(0).getStopId().equals(v.getStopId())) {
                        mismaParada++;
                    }
                }
                boolean yaVisto = !viajesVistos.add(id);
                if (v.getCurrentStatus() != VehicleStopStatus.STOPPED_AT || !v.hasStopId()) {
                    continue;
                }
                String clave = id + "|" + v.getStopId();
                if (visto.putIfAbsent(clave, Boolean.TRUE) != null || primera || !yaVisto) {
                    continue; // solo una llegada vista: el tren ya estaba en el feed en otra parada o en otro estado
                }
                List<GtfsEstatico.ParadaProgramada> h = g.horarios.get(id);
                Integer r = tu == null ? null : retraso(tu);
                GtfsEstatico.ParadaProgramada pp = h == null ? null : buscar(h, v.getStopId());
                if (pp == null || r == null || !v.hasTimestamp() || pp == h.getFirst()) {
                    continue; // el origen no es una llegada
                }
                long observado = v.getTimestamp() - (medianocheDia + pp.llegada());
                diferencia.add(observado - r);
            }
            primera = false;
        }

        Map<String, Long> cubos = new LinkedHashMap<>();
        for (String c : List.of("adelantado (< 0)", "0 min", "1-2 min", "3-5 min", "6-10 min", "11-30 min", "> 30 min")) {
            cubos.put(c, 0L);
        }
        for (int r : ultimoRetraso.values()) {
            int min = Math.floorDiv(r, 60);
            String c = r < 0 ? "adelantado (< 0)" : min == 0 ? "0 min" : min <= 2 ? "1-2 min" : min <= 5 ? "3-5 min"
                    : min <= 10 ? "6-10 min" : min <= 30 ? "11-30 min" : "> 30 min";
            cubos.merge(c, 1L, Long::sum);
        }
        out.printf("- Paradas (`stop_time_update`) por viaje en la primera muestra: %s (número de paradas → viajes).%n", paradasPorTu);
        out.printf("- `schedule_relationship` en la primera muestra: %s.%n", relacion);
        out.printf("- Viajes con `delay` a nivel de viaje: %d; paradas con `arrival.delay`: %d (primera muestra).%n", conDelayViaje, conDelayParada);
        out.printf("- Retrasos distintos de 0 que son múltiplos de 60 s: %s de %s lecturas%s.%n", pct(multiplo60, totalDelays), num(totalDelays),
                multiplo60 == totalDelays && totalDelays > 0 ? " → **la resolución es de minutos**" : "");
        out.printf("- `arrival.time − arrival.delay` coincide (±60 s) con la llegada programada de esa parada en `stop_times.txt`: "
                + "%s de %s (mediana del desvío %s s).%n", pct(coherentes, comprobables), num(comprobables), p(desvioHorario, 50));
        out.printf("- La parada de `trip_updates` es la misma que la próxima parada de `vehicle_positions`: %s de %s lecturas.%n%n",
                pct(mismaParada, comparablesParada), num(comparablesParada));
        out.printf("**Distribución del último retraso conocido de cada viaje** (%d viajes):%n%n| Retraso | Viajes | %% |%n|---|---:|---:|%n", ultimoRetraso.size());
        cubos.forEach((k, v) -> out.printf("| %s | %d | %s |%n", k, v, pct(v, ultimoRetraso.size())));
        long dentro60 = diferencia.stream().filter(d -> Math.abs(d) <= 60).count();
        long dentro120 = diferencia.stream().filter(d -> Math.abs(d) <= 120).count();
        long dentro300 = diferencia.stream().filter(d -> Math.abs(d) <= 300).count();
        List<Long> abs = diferencia.stream().map(Math::abs).toList();
        out.printf("%n**¿Cuadra el retraso con la posición?** Cuando un tren que ya estaba en el feed aparece detenido en una parada intermedia por primera vez, "
                        + "se compara (marca del tren − llegada programada a esa parada) con el retraso de `trip_updates` en esa misma muestra. "
                        + "Llegadas comparadas: %d. |diferencia| mediana %s s, p90 %s s. Dentro de ±60 s: %s; ±120 s: %s; ±300 s: %s.%n%n",
                diferencia.size(), p(abs, 50), p(abs, 90), pct(dentro60, diferencia.size()), pct(dentro120, diferencia.size()), pct(dentro300, diferencia.size()));
    }

    // ------------------------------------------------ 5 bis. Diagnóstico de posiciones y ventanas de horario

    void diagnostico(Map<String, ViajeVisto> vistos) {
        out.printf("## 5 bis. Diagnóstico: posiciones, paradas y horario%n%n");
        Map<String, Integer> estados = new TreeMap<>();
        List<Long> distParadaCercana = new ArrayList<>();
        List<Long> distParadaIndicada = new ArrayList<>();
        Map<String, Set<String>> posicionesPorViaje = new HashMap<>();
        Map<String, Set<String>> paradasPorViaje = new HashMap<>();
        Map<String, Integer> deltaSecuencia = new TreeMap<>();
        long antes = 0;
        long dentro = 0;
        long despues = 0;
        long despuesConRetraso = 0;
        long sinHorario = 0;
        long antesEnOrigen = 0;
        long antesJuntoAlOrigen = 0;
        Map<String, List<Long>> distanciaPorEstado = new TreeMap<>();
        List<Long> minutosAntes = new ArrayList<>();
        Map<String, Integer> lineaDistinta = new TreeMap<>();
        Set<String> comprobadas = new HashSet<>();
        for (Muestra m : muestras) {
            long t = m.descarga().getEpochSecond() - medianocheDia;
            Map<String, TripUpdate> tuPorViaje = new HashMap<>();
            m.tu().getEntityList().forEach(e -> tuPorViaje.put(e.getTripUpdate().getTrip().getTripId(), e.getTripUpdate()));
            for (FeedEntity e : m.vp().getEntityList()) {
                VehiclePosition v = e.getVehicle();
                String id = v.getTrip().getTripId();
                estados.merge(v.getCurrentStatus().name(), 1, Integer::sum);
                if (v.hasPosition() && v.getPosition().getLatitude() != 0) {
                    double la = v.getPosition().getLatitude();
                    double lo = v.getPosition().getLongitude();
                    double mejor = Double.MAX_VALUE;
                    for (double[] pp : g.paradas.values()) {
                        mejor = Math.min(mejor, metros(la, lo, pp[0], pp[1]));
                    }
                    distParadaCercana.add(Math.round(mejor));
                    distanciaPorEstado.computeIfAbsent(v.getCurrentStatus().name(), k -> new ArrayList<>()).add(Math.round(mejor));
                    double[] indicada = g.paradas.get(v.getStopId());
                    if (indicada != null) {
                        distParadaIndicada.add(Math.round(metros(la, lo, indicada[0], indicada[1])));
                    }
                    posicionesPorViaje.computeIfAbsent(id, k -> new HashSet<>()).add(la + "," + lo);
                    paradasPorViaje.computeIfAbsent(id, k -> new HashSet<>()).add(v.getStopId());
                }
                List<GtfsEstatico.ParadaProgramada> h = g.horarios.get(id);
                TripUpdate tu = tuPorViaje.get(id);
                if (h != null && tu != null && tu.getStopTimeUpdateCount() > 0) {
                    int iv = indice(h, v.getStopId());
                    int it = indice(h, tu.getStopTimeUpdate(0).getStopId());
                    String k = v.getCurrentStatus().name() + ": " + (iv < 0 || it < 0 ? "parada fuera del viaje" : "TU − VP = " + (it - iv));
                    deltaSecuencia.merge(k, 1, Integer::sum);
                }
                if (h == null || h.isEmpty()) {
                    sinHorario++;
                } else {
                    int ini = h.stream().mapToInt(GtfsEstatico.ParadaProgramada::salida).min().getAsInt();
                    int fin = h.stream().mapToInt(GtfsEstatico.ParadaProgramada::llegada).max().getAsInt();
                    Integer r = tu == null ? null : retraso(tu);
                    if (t < ini) {
                        antes++;
                        minutosAntes.add((ini - t) / 60);
                        if (h.getFirst().paradaId().equals(v.getStopId())) {
                            antesEnOrigen++;
                        }
                        double[] origen = g.paradas.get(h.getFirst().paradaId());
                        if (origen != null && v.hasPosition()
                                && metros(v.getPosition().getLatitude(), v.getPosition().getLongitude(), origen[0], origen[1]) <= 300) {
                            antesJuntoAlOrigen++;
                        }
                    } else if (t <= fin) {
                        dentro++;
                    } else if (r != null && t <= fin + r + 120) {
                        despuesConRetraso++;
                    } else {
                        despues++;
                    }
                }
                ViajeVisto vv = vistos.get(id);
                GtfsEstatico.Viaje viaje = g.viajes.get(id);
                if (vv != null && viaje != null && comprobadas.add(id)) {
                    GtfsEstatico.Ruta ruta = g.rutas.get(viaje.rutaId());
                    String corta = ruta == null ? "?" : ruta.nombreCorto();
                    if (!corta.equals(vv.linea)) {
                        lineaDistinta.merge(vv.linea + " (etiqueta) → " + corta + " (route)", 1, Integer::sum);
                    }
                }
            }
        }
        long cerca50 = distParadaCercana.stream().filter(d -> d <= 50).count();
        long indicada100 = distParadaIndicada.stream().filter(d -> d <= 100).count();
        double posDistintas = posicionesPorViaje.values().stream().mapToInt(Set::size).average().orElse(0);
        double parDistintas = paradasPorViaje.values().stream().mapToInt(Set::size).average().orElse(0);
        long total = antes + dentro + despues + despuesConRetraso;
        out.printf("- `current_status`: %s.%n", estados);
        out.printf("- Distancia de la posición a la parada más cercana del GTFS: mediana %s m, p90 %s m; a ≤ 50 m: %s.%n",
                p(distParadaCercana, 50), p(distParadaCercana, 90), pct(cerca50, distParadaCercana.size()));
        out.printf("- Distancia a la parada que indica el propio tren (`stop_id`): mediana %s m; a ≤ 100 m: %s.%n",
                p(distParadaIndicada, 50), pct(indicada100, distParadaIndicada.size()));
        out.printf("- Por tren, durante la captura: %s posiciones distintas de media y %s paradas (`stop_id`) distintas.%n",
                num(posDistintas, 1), num(parDistintas, 1));
        out.printf("- Parada de `trip_updates` frente a la de `vehicle_positions` (posición en la secuencia del viaje): %s.%n", deltaSecuencia);
        out.printf("- Momento de cada observación frente al horario de su viaje: antes de la primera salida %s (mediana %s min antes), "
                        + "dentro %s, después de la última llegada pero dentro del retraso %s, después sin explicación %s; sin horario %d.%n",
                pct(antes, total), p(minutosAntes, 50), pct(dentro, total), pct(despuesConRetraso, total), pct(despues, total), sinHorario);
        out.printf("- De las observaciones anteriores a la salida: con `stop_id` = parada de origen %s; a ≤ 300 m del origen %s.%n",
                pct(antesEnOrigen, antes), pct(antesJuntoAlOrigen, antes));
        distanciaPorEstado.forEach((k, l) -> out.printf("- Distancia a la parada más cercana con `%s`: mediana %s m, p75 %s m, p90 %s m; a ≤ 50 m: %s.%n",
                k, p(l, 50), p(l, 75), p(l, 90), pct(l.stream().filter(d -> d <= 50).count(), l.size())));
        out.printf("- Viajes cuya línea de la etiqueta no coincide con `route_short_name`: %s.%n%n", lineaDistinta.isEmpty() ? "ninguno" : lineaDistinta);

    }

    static int indice(List<GtfsEstatico.ParadaProgramada> h, String parada) {
        for (int i = 0; i < h.size(); i++) {
            if (h.get(i).paradaId().equals(parada)) {
                return i;
            }
        }
        return -1;
    }

    static double metros(double la1, double lo1, double la2, double lo2) {
        double r = 6_371_000;
        double dla = Math.toRadians(la2 - la1);
        double dlo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dla / 2) * Math.sin(dla / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    static Integer retraso(TripUpdate tu) {
        if (tu.hasDelay()) {
            return tu.getDelay();
        }
        for (StopTimeUpdate s : tu.getStopTimeUpdateList()) {
            if (s.hasArrival() && s.getArrival().hasDelay()) {
                return s.getArrival().getDelay();
            }
            if (s.hasDeparture() && s.getDeparture().hasDelay()) {
                return s.getDeparture().getDelay();
            }
        }
        return null;
    }

    static GtfsEstatico.ParadaProgramada buscar(List<GtfsEstatico.ParadaProgramada> h, String parada) {
        for (GtfsEstatico.ParadaProgramada pp : h) {
            if (pp.paradaId().equals(parada)) {
                return pp;
            }
        }
        return null;
    }

    // ------------------------------------------------ 6. Avisos

    void avisos() {
        Map<String, Alert> todos = new LinkedHashMap<>();
        Map<String, Integer> apariciones = new HashMap<>();
        for (Muestra m : muestras) {
            for (FeedEntity e : m.al().getEntityList()) {
                todos.putIfAbsent(e.getId(), e.getAlert());
                apariciones.merge(e.getId(), 1, Integer::sum);
            }
        }
        Set<String> primeros = new HashSet<>();
        muestras.getFirst().al().getEntityList().forEach(e -> primeros.add(e.getId()));
        Set<String> ultimos = new HashSet<>();
        muestras.getLast().al().getEntityList().forEach(e -> ultimos.add(e.getId()));

        Map<String, Integer> porNucleo = new TreeMap<>();
        Map<String, Integer> tipoEntidad = new TreeMap<>();
        Map<String, Integer> causas = new TreeMap<>();
        Map<String, Integer> efectos = new TreeMap<>();
        Map<String, Integer> idiomas = new TreeMap<>();
        Map<String, Integer> etiquetas = new TreeMap<>();
        int conFin = 0;
        int conCabecera = 0;
        int conUrl = 0;
        List<Long> antiguedadDias = new ArrayList<>();
        List<Integer> largos = new ArrayList<>();
        List<String> rodalies = new ArrayList<>();
        long ahora = muestras.getLast().descarga().getEpochSecond();
        Pattern hashtag = Pattern.compile("#\\p{L}+\\w*");
        for (var en : todos.entrySet()) {
            Alert a = en.getValue();
            Set<String> nucleos = new TreeSet<>();
            for (EntitySelector s : a.getInformedEntityList()) {
                if (s.hasRouteId()) {
                    tipoEntidad.merge("route_id", 1, Integer::sum);
                    nucleos.add(s.getRouteId().substring(0, 2));
                }
                if (s.hasStopId()) {
                    tipoEntidad.merge("stop_id", 1, Integer::sum);
                }
                if (s.hasTrip()) {
                    tipoEntidad.merge("trip", 1, Integer::sum);
                    nucleos.add(s.getTrip().getTripId().substring(0, 2));
                }
                if (s.hasAgencyId()) {
                    tipoEntidad.merge("agency_id", 1, Integer::sum);
                }
            }
            if (nucleos.isEmpty()) {
                nucleos.add("solo estaciones");
            }
            nucleos.forEach(n -> porNucleo.merge(NUCLEOS.getOrDefault(n, n), 1, Integer::sum));
            causas.merge(a.hasCause() ? a.getCause().name() : "(sin cause)", 1, Integer::sum);
            efectos.merge(a.hasEffect() ? a.getEffect().name() : "(sin effect)", 1, Integer::sum);
            a.getDescriptionText().getTranslationList().forEach(t -> idiomas.merge(t.getLanguage(), 1, Integer::sum));
            if (a.getActivePeriodList().stream().anyMatch(p -> p.hasEnd())) {
                conFin++;
            }
            if (a.hasHeaderText()) {
                conCabecera++;
            }
            if (a.hasUrl()) {
                conUrl++;
            }
            a.getActivePeriodList().stream().filter(p -> p.hasStart()).mapToLong(p -> p.getStart()).min()
                    .ifPresent(s -> antiguedadDias.add((ahora - s) / 86400));
            String texto = a.getDescriptionText().getTranslationCount() > 0 ? a.getDescriptionText().getTranslation(0).getText() : "";
            largos.add(texto.length());
            Matcher h = hashtag.matcher(texto);
            while (h.find()) {
                etiquetas.merge(h.group(), 1, Integer::sum);
            }
            if (nucleos.contains("51")) {
                rodalies.add(en.getKey() + ": " + recortar(texto, 140));
            }
        }
        out.printf("## 6. Avisos (`alerts`)%n%n");
        out.printf("- Avisos distintos en la captura: **%d** (primera muestra %d, última %d; aparecen %d nuevos y desaparecen %d).%n",
                todos.size(), primeros.size(), ultimos.size(),
                todos.keySet().stream().filter(k -> !primeros.contains(k)).count(),
                primeros.stream().filter(k -> !ultimos.contains(k)).count());
        out.printf("- Por núcleo (según `route_id`): %s.%n", porNucleo);
        out.printf("- Entidades afectadas por tipo: %s.%n", tipoEntidad);
        out.printf("- `cause`: %s. `effect`: %s.%n", causas, efectos);
        out.printf("- Idiomas de la descripción: %s. Con `header_text`: %d. Con `url`: %d. Con fecha de fin: %d de %d.%n",
                idiomas, conCabecera, conUrl, conFin, todos.size());
        out.printf("- Antigüedad del inicio (días): mediana %s, máx. %s. Largo del texto: mediana %s caracteres, máx. %s.%n",
                p(antiguedadDias, 50), antiguedadDias.isEmpty() ? 0 : Collections.max(antiguedadDias),
                p(largos.stream().map(Integer::longValue).toList(), 50), largos.isEmpty() ? 0 : Collections.max(largos));
        out.printf("- Etiquetas tipo hashtag más usadas: %s.%n", etiquetas.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue()).limit(12).map(e -> e.getKey() + "×" + e.getValue()).toList());
        out.printf("- Avisos de Rodalies (%d):%n", rodalies.size());
        rodalies.forEach(r -> out.printf("  - %s%n", r));
        out.println();
    }

    static String recortar(String s, int n) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    // ------------------------------------------------ 7. Volumen

    void volumen(Map<String, ViajeVisto> vistos) {
        out.printf("## 7. Volumen%n%n");
        double vpMedia = muestras.stream().mapToInt(m -> m.vp().getEntityCount()).average().orElse(0);
        int vpMax = muestras.stream().mapToInt(m -> m.vp().getEntityCount()).max().orElse(0);
        int vpMin = muestras.stream().mapToInt(m -> m.vp().getEntityCount()).min().orElse(0);
        double tuMedia = muestras.stream().mapToInt(m -> m.tu().getEntityCount()).average().orElse(0);
        double alMedia = muestras.stream().mapToInt(m -> m.al().getEntityCount()).average().orElse(0);
        Map<String, Double> bytes = new LinkedHashMap<>();
        for (String f : List.of("vehicle_positions", "trip_updates", "alerts")) {
            bytes.put(f, muestras.stream().mapToInt(m -> m.bytes().get(f)).average().orElse(0));
        }

        // Ocupación del día según el horario: trenes en marcha a cada minuto.
        int[] enMarcha = new int[30 * 60];
        long trenSegundos = 0;
        long eventosParada = 0;
        for (GtfsEstatico.Resumen r : g.resumenDelDia.values()) {
            trenSegundos += Math.max(0, r.fin() - r.inicio());
            eventosParada += r.paradas();
            for (int mnt = r.inicio() / 60; mnt <= Math.min(r.fin() / 60, enMarcha.length - 1); mnt++) {
                enMarcha[mnt]++;
            }
        }
        int pico = 0;
        int minutoPico = 0;
        for (int i = 0; i < enMarcha.length; i++) {
            if (enMarcha[i] > pico) {
                pico = enMarcha[i];
                minutoPico = i;
            }
        }
        // Proporción directo / programado durante la captura, para escalar el día.
        double prog = 0;
        for (Muestra m : muestras) {
            long t = m.descarga().getEpochSecond() - medianocheDia;
            prog += g.resumenDelDia.values().stream().filter(r -> r.inicio() <= t && t <= r.fin()).count();
        }
        prog /= muestras.size();
        double factor = prog == 0 ? 1 : vpMedia / prog;

        double filasPos20s = trenSegundos / 20.0 * factor;

        // ¿Cuántas observaciones traen algo nuevo (coordenadas, parada o estado) respecto a la anterior del mismo tren?
        Map<String, String> ultimoEstado = new HashMap<>();
        long comparadas = 0;
        long conCambio = 0;
        for (Muestra m : muestras) {
            for (FeedEntity e : m.vp().getEntityList()) {
                VehiclePosition v = e.getVehicle();
                String estado = v.getPosition().getLatitude() + "," + v.getPosition().getLongitude() + "|" + v.getStopId() + "|" + v.getCurrentStatus();
                String antes = ultimoEstado.put(v.getTrip().getTripId(), estado);
                if (antes != null) {
                    comparadas++;
                    if (!antes.equals(estado)) {
                        conCambio++;
                    }
                }
            }
        }
        double fraccionCambio = comparadas == 0 ? 1 : conCambio / (double) comparadas;
        long viajesDia = g.resumenDelDia.size();
        out.printf("| Medida | Valor |%n|---|---:|%n");
        out.printf("| Trenes por muestra en `vehicle_positions` (media · mín · máx) | %s · %d · %d |%n", num(vpMedia, 1), vpMin, vpMax);
        out.printf("| Entidades por muestra en `trip_updates` · `alerts` | %s · %s |%n", num(tuMedia, 1), num(alMedia, 1));
        out.printf("| Tamaño medio del .pb: posiciones · retrasos · avisos | %s · %s · %s KB |%n",
                num(bytes.get("vehicle_positions") / 1024, 1), num(bytes.get("trip_updates") / 1024, 1), num(bytes.get("alerts") / 1024, 1));
        out.printf("| Viajes de tren programados el día analizado (todos los núcleos, sin autobuses) | %s |%n", num(viajesDia));
        out.printf("| Pico de trenes en marcha según el horario | %d a las %02d:%02d |%n", pico, minutoPico / 60, minutoPico % 60);
        out.printf("| Horas-tren programadas en el día | %s |%n", num(trenSegundos / 3600.0, 0));
        out.printf("| Entidades del feed por cada viaje programado en marcha, durante la captura (incluye trenes esperando en origen; no es cobertura) | %s |%n",
                num(factor, 2));
        out.printf("| Filas de posiciones al día con el horario completo (horas-tren × 180) | %s |%n", num(trenSegundos / 20));
        out.printf("| **Filas de posiciones al día** (lo anterior × entidades por viaje en marcha) | **%s** |%n", num(Math.round(filasPos20s)));
        out.printf("| Filas de posiciones en 48 h | %s |%n", num(Math.round(filasPos20s * 2)));
        out.printf("| Observaciones que cambian algo (coordenadas, parada o estado) respecto a la anterior del mismo tren | %s |%n",
                pct(conCambio, comparadas));
        out.printf("| Filas de posiciones al día guardando solo los cambios | %s |%n", num(Math.round(filasPos20s * fraccionCambio)));
        out.printf("| Filas de retraso final por parada al día, como máximo (todas las paradas programadas del día) | %s |%n", num(eventosParada));
        out.printf("| **Filas de retraso final por parada al día** (lo anterior × %s de viajes que salen en el directo, §4) | **%s** |%n",
                pct(coberturaHorario, 1.0), num(Math.round(eventosParada * coberturaHorario)));
        out.printf("| Filas de retraso al año | %s |%n", num(Math.round(eventosParada * coberturaHorario * 365)));
        out.printf("| Descarga diaria de los tres feeds cada 20 s | %s MB |%n",
                num((bytes.values().stream().mapToDouble(Double::doubleValue).sum()) * 4320 / 1_048_576, 0));
        out.println();
    }

    // ---------------------------------------------------------------- formato

    static String p(List<Long> valores, int percentil) {
        if (valores.isEmpty()) {
            return "—";
        }
        List<Long> s = new ArrayList<>(valores);
        Collections.sort(s);
        int i = (int) Math.ceil(percentil / 100.0 * s.size()) - 1;
        return String.valueOf(s.get(Math.max(0, Math.min(i, s.size() - 1))));
    }

    static String pct(long a, long b) {
        return b == 0 ? "—" : String.format(ES, "%.1f %%", 100.0 * a / b);
    }

    static String pct(double a, double b) {
        return b == 0 ? "—" : String.format(ES, "%.1f %%", 100.0 * a / b);
    }

    static String num(long n) {
        return String.format(ES, "%,d", n);
    }

    static String num(double d, int decimales) {
        return String.format(ES, "%,." + decimales + "f", d);
    }
}
