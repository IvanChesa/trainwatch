package com.ivanchesa.trainwatch.api;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ivanchesa.trainwatch.api.Respuestas.Aviso;
import com.ivanchesa.trainwatch.api.Respuestas.EstacionCercana;
import com.ivanchesa.trainwatch.api.Respuestas.LineaEstacion;
import com.ivanchesa.trainwatch.api.Respuestas.ParadaLinea;
import com.ivanchesa.trainwatch.api.Respuestas.PuntoEvolucion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las consultas de la API a la base de datos: el horario (esquema {@code horario}), {@code retrasos} y {@code avisos}.
 * Lo que está en directo no se consulta aquí: sale del estado en memoria.
 *
 * <p>Lo que solo cambia con un horario nuevo (la extensión de cada núcleo, las líneas con su recorrido) se guarda en
 * memoria con la versión del horario: la primera importación correcta que se encuentre distinta lo vacía.
 */
@Repository
public class ConsultasApi {

    /** Extensión de las estaciones de un núcleo. */
    public record Extension(int nucleo, double lonMin, double latMin, double lonMax, double latMax) {
    }

    /** Una estación del horario. */
    public record Estacion(String id, String nombre, double lat, double lon) {
    }

    /** Las líneas que paran en una estación, con sus {@code route_id} (para los avisos). */
    public record LineasDeEstacion(List<LineaEstacion> lineas, List<String> rutas) {
    }

    /** Un paso programado de un viaje por una estación. */
    public record Paso(String tripId, int secuencia, int salidaS, String linea, Integer nucleo, String destino) {
    }

    /** Lo que no cambia de una línea hasta el horario siguiente. */
    public record DatosLinea(int nucleo, String linea, String nombreLargo, String color, String colorTexto,
            List<String> rutas, JsonNode recorrido, List<ParadaLinea> paradas) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private volatile long version = -1;
    private volatile List<Extension> extensiones;
    private final Map<String, DatosLinea> lineas = new ConcurrentHashMap<>();

    public ConsultasApi(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Vacía las cachés si hay un horario nuevo. Es una consulta muy barata (un índice). */
    private void alDia() {
        long actual = jdbc.sql("SELECT coalesce(max(id), 0) FROM importacion_horario WHERE resultado = 'CORRECTA'")
                .query(Long.class).single();
        if (actual != version) {
            extensiones = null;
            lineas.clear();
            version = actual;
        }
    }

    // ---------------------------------------------------------------- núcleos

    /** Por núcleo, el rectángulo de sus estaciones de tren (sin autobuses). Recorre las horas de paso: se guarda. */
    public List<Extension> extensiones() {
        alDia();
        List<Extension> e = extensiones;
        if (e == null) {
            e = jdbc.sql("""
                    WITH paradas AS (
                        SELECT DISTINCT r.nucleo, st.stop_id
                        FROM horario.routes r
                        JOIN horario.trips t ON t.route_id = r.route_id
                        JOIN horario.stop_times st ON st.trip_id = t.trip_id
                        WHERE NOT r.es_autobus AND r.nucleo IS NOT NULL)
                    SELECT p.nucleo, min(s.stop_lon), min(s.stop_lat), max(s.stop_lon), max(s.stop_lat)
                    FROM paradas p JOIN horario.stops s ON s.stop_id = p.stop_id
                    GROUP BY p.nucleo ORDER BY p.nucleo""")
                    .query((rs, n) -> new Extension(rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5)))
                    .list();
            extensiones = e;
        }
        return e;
    }

    // ---------------------------------------------------------------- estaciones

    public Optional<Estacion> estacion(String id) {
        return jdbc.sql("SELECT stop_id, stop_name, stop_lat, stop_lon FROM horario.stops WHERE stop_id = :id")
                .param("id", id)
                .query((rs, n) -> new Estacion(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)))
                .optional();
    }

    public LineasDeEstacion lineasDeEstacion(String id) {
        List<String> rutas = new ArrayList<>();
        List<LineaEstacion> lineas = jdbc.sql("""
                SELECT r.nucleo, r.route_short_name, min(r.route_color), min(r.route_text_color),
                       array_agg(DISTINCT r.route_id)
                FROM horario.stop_times st
                JOIN horario.trips t ON t.trip_id = st.trip_id
                JOIN horario.routes r ON r.route_id = t.route_id
                WHERE st.stop_id = :id AND NOT r.es_autobus
                GROUP BY r.nucleo, r.route_short_name
                ORDER BY r.nucleo, r.route_short_name""")
                .param("id", id)
                .query((rs, n) -> {
                    rutas.addAll(textos(rs.getArray(5)));
                    return new LineaEstacion((Integer) rs.getObject(1), rs.getString(2), color(rs.getString(3)),
                            color(rs.getString(4)));
                })
                .list();
        return new LineasDeEstacion(lineas, rutas);
    }

    /**
     * Los trenes que el horario hace pasar por una estación un día de servicio, con la salida entre {@code desdeS} y
     * {@code hastaS} (segundos desde el origen de ese día: pueden pasar de 86.400). Sin autobuses.
     */
    public List<Paso> pasos(String stopId, LocalDate fecha, long desdeS, long hastaS) {
        return jdbc.sql("""
                SELECT st.trip_id, st.stop_sequence, st.salida_s, r.route_short_name, r.nucleo,
                       coalesce(nullif(trim(t.trip_headsign), ''),
                                (SELECT s2.stop_name FROM horario.stop_times st2
                                 JOIN horario.stops s2 ON s2.stop_id = st2.stop_id
                                 WHERE st2.trip_id = t.trip_id ORDER BY st2.stop_sequence DESC LIMIT 1))
                FROM horario.stop_times st
                JOIN horario.trips t ON t.trip_id = st.trip_id
                JOIN horario.routes r ON r.route_id = t.route_id
                JOIN horario.calendar c ON c.service_id = t.service_id
                WHERE st.stop_id = :stop AND st.salida_s BETWEEN :desde AND :hasta AND NOT t.es_autobus
                  AND c.start_date <= :fecha AND c.end_date >= :fecha AND c.%s
                ORDER BY st.salida_s""".formatted(columnaDia(fecha)))
                .param("stop", stopId).param("desde", desdeS).param("hasta", hastaS).param("fecha", fecha)
                .query((rs, n) -> new Paso(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4),
                        (Integer) rs.getObject(5), rs.getString(6)))
                .list();
    }

    /**
     * Las estaciones a menos de {@code radioM} metros, de la más cercana a la más lejana. Usa el índice GiST sobre
     * {@code ubicacion::geography} (fase 2, ADR 0005): {@code ST_DWithin} filtra y {@code <->} ordena por distancia.
     */
    public List<EstacionCercana> cercanas(double lat, double lon, int radioM, int limite) {
        return jdbc.sql("""
                SELECT stop_id, stop_name, stop_lat, stop_lon,
                       round(ST_Distance(ubicacion::geography, p.punto))::int
                FROM horario.stops, (SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography AS punto) p
                WHERE ST_DWithin(ubicacion::geography, p.punto, :radio)
                ORDER BY ubicacion::geography <-> p.punto
                LIMIT :limite""")
                .param("lat", lat).param("lon", lon).param("radio", radioM).param("limite", limite)
                .query((rs, n) -> new EstacionCercana(rs.getString(1), rs.getString(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getInt(5)))
                .list();
    }

    // ---------------------------------------------------------------- líneas

    /** La línea por núcleo y nombre corto (sin distinguir mayúsculas), sin autobuses. Se guarda hasta otro horario. */
    public Optional<DatosLinea> linea(int nucleo, String linea) {
        alDia();
        long v = version;
        String clave = nucleo + "/" + linea.toUpperCase(java.util.Locale.ROOT);
        DatosLinea d = lineas.get(clave);
        if (d == null) {
            // Fuera de computeIfAbsent (no se consulta la base de datos con el mapa bloqueado) y sin guardar las que
            // no existen: la clave viene del cliente y el mapa crecería sin límite (revisión de la fase 4).
            d = cargarLinea(nucleo, linea);
            if (d != null && v == version) {
                lineas.put(clave, d);
            }
        }
        return Optional.ofNullable(d);
    }

    private DatosLinea cargarLinea(int nucleo, String linea) {
        record Ruta(String id, String corto, String largo, String color, String colorTexto) {
        }
        List<Ruta> rutas = jdbc.sql("""
                SELECT route_id, route_short_name, route_long_name, route_color, route_text_color
                FROM horario.routes
                WHERE nucleo = :nucleo AND upper(route_short_name) = upper(:linea) AND NOT es_autobus
                ORDER BY route_id""")
                .param("nucleo", nucleo).param("linea", linea)
                .query((rs, n) -> new Ruta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .list();
        if (rutas.isEmpty()) {
            return null;
        }
        List<String> ids = rutas.stream().map(Ruta::id).toList();
        // Recorridos de la línea, del más usado al menos usado: el primero ordena las paradas.
        List<String> formas = jdbc.sql("""
                SELECT shape_id FROM horario.trips
                WHERE route_id IN (:rutas) AND shape_id IS NOT NULL
                GROUP BY shape_id ORDER BY count(*) DESC, shape_id""")
                .param("rutas", ids).query(String.class).list();
        String geojson = formas.isEmpty() ? null : jdbc.sql("""
                SELECT json_build_object('type', 'FeatureCollection', 'features', json_agg(
                           json_build_object('type', 'Feature', 'properties', json_build_object('shapeId', shape_id),
                                             'geometry', ST_AsGeoJSON(recorrido, 6)::json) ORDER BY shape_id))::text
                FROM horario.shapes WHERE shape_id IN (:formas)""")
                .param("formas", formas).query(String.class).single();
        JsonNode recorrido = json.readTree(geojson != null ? geojson : "{\"type\":\"FeatureCollection\",\"features\":[]}");
        List<ParadaLinea> paradas = jdbc.sql("""
                SELECT s.stop_id, s.stop_name, s.stop_lat, s.stop_lon
                FROM horario.stops s
                JOIN (SELECT DISTINCT st.stop_id FROM horario.stop_times st
                      JOIN horario.trips t ON t.trip_id = st.trip_id WHERE t.route_id IN (:rutas)) x
                  ON x.stop_id = s.stop_id
                LEFT JOIN horario.parada_forma pf ON pf.shape_id = :principal AND pf.stop_id = s.stop_id
                ORDER BY pf.posicion NULLS LAST, s.stop_name""")
                .param("rutas", ids).param("principal", formas.isEmpty() ? "" : formas.getFirst())
                .query((rs, n) -> new ParadaLinea(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)))
                .list();
        Ruta primera = rutas.getFirst();
        return new DatosLinea(nucleo, primera.corto(), primera.largo(), color(primera.color()),
                color(primera.colorTexto()), ids, recorrido, paradas);
    }

    // ---------------------------------------------------------------- retrasos

    /** El retraso final de cada parada que el viaje ya ha dejado atrás ese día, en orden. */
    public List<PuntoEvolucion> evolucion(LocalDate fecha, String tripId) {
        return jdbc.sql("""
                SELECT r.stop_sequence, r.stop_id, s.stop_name, r.minutos, r.inferido, r.llegada_programada,
                       r.registrado
                FROM retrasos r LEFT JOIN horario.stops s ON s.stop_id = r.stop_id
                WHERE r.fecha_servicio = :fecha AND r.trip_id = :trip
                ORDER BY r.stop_sequence""")
                .param("fecha", fecha).param("trip", tripId)
                .query((rs, n) -> new PuntoEvolucion(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getBoolean(5), hora(rs, 6), hora(rs, 7)))
                .list();
    }

    // ---------------------------------------------------------------- avisos

    /** Avisos filtrados; un filtro {@code null} no filtra. Cada aviso, entero (con todas sus líneas y estaciones). */
    public List<Aviso> avisos(Integer nucleo, String linea, String estacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (nucleo != null) {
            condiciones.add("a2.nucleo = :nucleo");
            params.put("nucleo", nucleo);
        }
        if (linea != null) {
            condiciones.add("upper(r2.route_short_name) = upper(:linea)");
            params.put("linea", linea);
        }
        if (estacion != null) {
            condiciones.add("a2.stop_id = :estacion");
            params.put("estacion", estacion);
        }
        return avisosDonde(condiciones.isEmpty() ? "true" : String.join(" AND ", condiciones), params);
    }

    /** Avisos de una estación o de alguna de sus rutas. */
    public List<Aviso> avisosDeEstacion(String estacion, List<String> rutas) {
        Map<String, Object> params = new HashMap<>();
        params.put("estacion", estacion);
        if (rutas.isEmpty()) {
            return avisosDonde("a2.stop_id = :estacion", params);
        }
        params.put("rutas", rutas);
        return avisosDonde("(a2.stop_id = :estacion OR a2.route_id IN (:rutas))", params);
    }

    /** Avisos de alguna de estas rutas. */
    public List<Aviso> avisosDeRutas(List<String> rutas) {
        if (rutas.isEmpty()) {
            return List.of();
        }
        return avisosDonde("a2.route_id IN (:rutas)", Map.of("rutas", rutas));
    }

    /** {@code condicion} se compone solo con texto fijo de esta clase; los valores van siempre como parámetros. */
    private List<Aviso> avisosDonde(String condicion, Map<String, Object> params) {
        record Fila(String id, String routeId, String stopId, Integer nucleo, String texto, OffsetDateTime inicio,
                OffsetDateTime fin, String linea) {
        }
        List<Fila> filas = jdbc.sql("""
                SELECT a.aviso_id, a.route_id, a.stop_id, a.nucleo, a.texto, a.inicio, a.fin, r.route_short_name
                FROM avisos a LEFT JOIN horario.routes r ON r.route_id = a.route_id
                WHERE a.aviso_id IN (SELECT a2.aviso_id FROM avisos a2
                                     LEFT JOIN horario.routes r2 ON r2.route_id = a2.route_id
                                     WHERE %s)
                ORDER BY a.aviso_id""".formatted(condicion))
                .params(params)
                .query((rs, n) -> new Fila(rs.getString(1), rs.getString(2), rs.getString(3),
                        (Integer) rs.getObject(4), rs.getString(5), hora(rs, 6), hora(rs, 7), rs.getString(8)))
                .list();
        Map<String, List<Fila>> porAviso = new LinkedHashMap<>();
        for (Fila f : filas) {
            porAviso.computeIfAbsent(f.id(), k -> new ArrayList<>()).add(f);
        }
        List<Aviso> avisos = new ArrayList<>();
        for (List<Fila> grupo : porAviso.values()) {
            TreeSet<Integer> nucleos = new TreeSet<>();
            TreeSet<String> lineas = new TreeSet<>();
            TreeSet<String> estaciones = new TreeSet<>();
            for (Fila f : grupo) {
                if (f.nucleo() != null) {
                    nucleos.add(f.nucleo());
                }
                if (f.linea() != null) {
                    lineas.add(f.linea());
                } else if (f.routeId() != null) {
                    lineas.add(f.routeId()); // ruta que no está en el horario: mejor el id que nada
                }
                if (f.stopId() != null) {
                    estaciones.add(f.stopId());
                }
            }
            Fila f = grupo.getFirst();
            avisos.add(new Aviso(f.id(), f.texto(), f.inicio(), f.fin(), List.copyOf(nucleos), List.copyOf(lineas),
                    List.copyOf(estaciones)));
        }
        return avisos;
    }

    // ---------------------------------------------------------------- ayudas

    /** El nombre de la columna del día de la semana en {@code calendar}: sale de un enum, nunca de fuera. */
    static String columnaDia(LocalDate fecha) {
        return switch (fecha.getDayOfWeek()) {
            case MONDAY -> "monday";
            case TUESDAY -> "tuesday";
            case WEDNESDAY -> "wednesday";
            case THURSDAY -> "thursday";
            case FRIDAY -> "friday";
            case SATURDAY -> "saturday";
            case SUNDAY -> "sunday";
        };
    }

    private static OffsetDateTime hora(ResultSet rs, int columna) throws SQLException {
        OffsetDateTime t = rs.getObject(columna, OffsetDateTime.class);
        return t == null ? null : VistaTrenes.hora(t.toInstant());
    }

    /** {@code 9A4A9E} → {@code #9A4A9E}; vacío → {@code null}. */
    static String color(String gtfs) {
        if (gtfs == null || gtfs.isBlank()) {
            return null;
        }
        String c = gtfs.trim();
        return c.startsWith("#") ? c : "#" + c;
    }

    private static List<String> textos(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

}
