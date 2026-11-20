package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.ivanchesa.trainwatch.RelojConfig;

/**
 * El horario importado (esquema {@code horario}, fase 2) visto desde la ingesta, con cachés en memoria:
 * <ul>
 * <li>cada viaje se lee la primera vez que aparece (dos consultas por clave primaria) y se guarda, también si no
 * existe, para no preguntar en cada muestra por los {@code SPECIAL_…};</li>
 * <li>los recorridos, igual (144 en total);</li>
 * <li>los intervalos (primera salida, última llegada) de los viajes de cada día, para contar los que el horario da
 * en marcha.</li>
 * </ul>
 * Las cachés se vacían cuando hay una importación correcta nueva o cambia el día ({@link #refrescarSiCambia}).
 * Son {@link ConcurrentHashMap} por si se leen desde otros hilos, pero hoy solo las llena el hilo de la ingesta.
 */
@Component
public class HorarioDirectoJdbc implements FuenteHorario {

    private record Intervalo(String tripId, Integer nucleo, int inicioS, int finS) {
    }

    private final JdbcClient jdbc;
    private final ZoneId zona = RelojConfig.ZONA;
    private final Map<String, Optional<Viaje>> viajes = new ConcurrentHashMap<>();
    private final Map<String, Optional<Recorrido>> recorridos = new ConcurrentHashMap<>();
    private final Map<LocalDate, List<Intervalo>> intervalos = new ConcurrentHashMap<>();
    private volatile Set<Integer> nucleos;
    private volatile long version = -1;
    private volatile LocalDate dia;

    public HorarioDirectoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Vacía las cachés si hay un horario nuevo (otra importación correcta) o si ha cambiado el día. Es barato. */
    public void refrescarSiCambia(Instant ahora) {
        long actual = jdbc.sql("SELECT coalesce(max(id), 0) FROM importacion_horario WHERE resultado = 'CORRECTA'")
                .query(Long.class).single();
        LocalDate hoy = LocalDate.ofInstant(ahora, zona);
        if (actual != version || !hoy.equals(dia)) {
            viajes.clear();
            recorridos.clear();
            intervalos.clear();
            nucleos = null;
            version = actual;
            dia = hoy;
        }
    }

    @Override
    public Viaje viaje(String tripId) {
        Optional<Viaje> v = viajes.get(tripId);
        if (v == null) {
            v = Optional.ofNullable(cargarViaje(tripId));
            viajes.put(tripId, v);
        }
        return v.orElse(null);
    }

    @Override
    public Set<Integer> nucleos() {
        Set<Integer> n = nucleos;
        if (n == null) {
            n = Set.copyOf(jdbc.sql("""
                    SELECT DISTINCT nucleo FROM horario.routes WHERE NOT es_autobus AND nucleo IS NOT NULL""")
                    .query(Integer.class).list());
            nucleos = n;
        }
        return n;
    }

    @Override
    public Map<Integer, Set<String>> enMarchaSegunHorario(Instant ahora) {
        Map<Integer, Set<String>> resultado = new HashMap<>();
        LocalDate hoy = LocalDate.ofInstant(ahora, zona);
        for (LocalDate fecha : List.of(hoy.minusDays(1), hoy)) {
            List<Intervalo> lista = intervalos.get(fecha);
            if (lista == null) {
                lista = cargarIntervalos(fecha);
                intervalos.put(fecha, lista);
            }
            long segundos = ahora.getEpochSecond() - Viaje.origenDelDia(fecha, zona).getEpochSecond();
            for (Intervalo i : lista) {
                if (i.nucleo() != null && i.inicioS() <= segundos && segundos <= i.finS()) {
                    resultado.computeIfAbsent(i.nucleo(), k -> new HashSet<>()).add(i.tripId());
                }
            }
        }
        return resultado;
    }

    private Viaje cargarViaje(String tripId) {
        record Cabecera(String routeId, String linea, Integer nucleo, String destino, String shapeId, boolean encaja,
                Integer sentido, LocalDate inicio, LocalDate fin) {
        }
        Optional<Cabecera> cabecera = jdbc.sql("""
                SELECT t.route_id, r.route_short_name, r.nucleo, t.trip_headsign, t.shape_id, t.forma_encaja,
                       t.forma_sentido, c.start_date, c.end_date
                FROM horario.trips t
                JOIN horario.routes r ON r.route_id = t.route_id
                LEFT JOIN horario.calendar c ON c.service_id = t.service_id
                WHERE t.trip_id = ? AND NOT t.es_autobus""")
                .param(tripId)
                .query((rs, n) -> new Cabecera(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6), (Integer) rs.getObject(7),
                        rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class)))
                .optional();
        if (cabecera.isEmpty()) {
            return null;
        }
        Cabecera c = cabecera.get();
        Recorrido recorrido = c.encaja() && c.shapeId() != null && c.sentido() != null ? recorrido(c.shapeId()) : null;
        int sentido = c.sentido() == null ? 1 : c.sentido();
        List<ParadaViaje> paradas = jdbc.sql("""
                SELECT st.stop_sequence, st.stop_id, s.stop_name, s.stop_lat, s.stop_lon, st.llegada_s, st.salida_s,
                       pf.posicion
                FROM horario.stop_times st
                JOIN horario.stops s ON s.stop_id = st.stop_id
                LEFT JOIN horario.parada_forma pf ON pf.shape_id = ? AND pf.stop_id = st.stop_id
                WHERE st.trip_id = ?
                ORDER BY st.stop_sequence""")
                .param(c.shapeId()).param(tripId)
                .query((rs, n) -> {
                    Double posicion = (Double) rs.getObject(8);
                    Double avance = recorrido == null || posicion == null ? null : sentido < 0 ? 1 - posicion : posicion;
                    return new ParadaViaje(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getInt(6), rs.getInt(7), avance);
                })
                .list();
        LocalDate fecha = c.inicio() != null && c.inicio().equals(c.fin()) ? c.inicio() : null;
        return new Viaje(tripId, c.routeId(), c.linea(), c.nucleo(), c.destino(), fecha, paradas, recorrido, sentido);
    }

    private Recorrido recorrido(String shapeId) {
        Optional<Recorrido> r = recorridos.get(shapeId);
        if (r == null) {
            r = jdbc.sql("SELECT ST_AsText(recorrido), cerrado FROM horario.shapes WHERE shape_id = ?")
                    .param(shapeId)
                    .query((rs, n) -> deWkt(shapeId, rs.getString(1), rs.getBoolean(2)))
                    .optional();
            recorridos.put(shapeId, r);
        }
        return r.orElse(null);
    }

    /** {@code LINESTRING(lon lat,lon lat,…)} → {@link Recorrido}. */
    static Recorrido deWkt(String shapeId, String wkt, boolean cerrado) {
        String cuerpo = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        String[] puntos = cuerpo.split(",");
        double[] lon = new double[puntos.length];
        double[] lat = new double[puntos.length];
        for (int i = 0; i < puntos.length; i++) {
            String[] xy = puntos[i].trim().split("\\s+");
            lon[i] = Double.parseDouble(xy[0]);
            lat[i] = Double.parseDouble(xy[1]);
        }
        return new Recorrido(shapeId, lon, lat, cerrado);
    }

    private List<Intervalo> cargarIntervalos(LocalDate fecha) {
        // El nombre de la columna sale de un enum, nunca de fuera: no hay inyección posible.
        String columna = switch (fecha.getDayOfWeek()) {
            case MONDAY -> "monday";
            case TUESDAY -> "tuesday";
            case WEDNESDAY -> "wednesday";
            case THURSDAY -> "thursday";
            case FRIDAY -> "friday";
            case SATURDAY -> "saturday";
            case SUNDAY -> "sunday";
        };
        return jdbc.sql("""
                SELECT t.trip_id, r.nucleo, min(st.salida_s), max(st.llegada_s)
                FROM horario.trips t
                JOIN horario.routes r ON r.route_id = t.route_id
                JOIN horario.calendar c ON c.service_id = t.service_id
                JOIN horario.stop_times st ON st.trip_id = t.trip_id
                WHERE NOT t.es_autobus AND c.start_date <= :fecha AND c.end_date >= :fecha AND c.%s
                GROUP BY t.trip_id, r.nucleo""".formatted(columna))
                .param("fecha", fecha)
                .query((rs, n) -> new Intervalo(rs.getString(1), (Integer) rs.getObject(2), rs.getInt(3), rs.getInt(4)))
                .list();
    }
}
