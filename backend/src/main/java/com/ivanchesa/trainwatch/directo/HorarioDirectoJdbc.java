package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * <li>cada viaje se lee la primera vez que aparece (una consulta, con sus paradas y la geometría de su recorrido,
 * aunque el recorrido ya esté en caché: es una vez por viaje y día) y se guarda,
 * también si no existe, para no preguntar en cada muestra por los {@code SPECIAL_…}. Si la consulta falla, no se
 * guarda nada y se vuelve a intentar en la muestra siguiente;</li>
 * <li>los recorridos (144 en total), que comparten muchos viajes;</li>
 * <li>los intervalos (primera salida, última llegada) de los viajes de cada día, para contar los que el horario da
 * en marcha.</li>
 * </ul>
 * Las cachés se vacían cuando hay una importación correcta nueva o cambia el día ({@link #refrescarSiCambia}). La
 * importación apunta su versión en la misma transacción que el cambio de esquema (fase 3b), así que una versión
 * nueva siempre corresponde al horario que ya está en uso.
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
    public boolean enCache(String tripId) {
        return viajes.containsKey(tripId);
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

    /**
     * El viaje con sus paradas y, si no está ya en caché, su recorrido, en <b>una sola consulta</b> (fase 3b). Antes
     * eran dos (la cabecera y luego las paradas): si el horario cambiaba de esquema entre las dos, el viaje mezclaba
     * el horario viejo y el nuevo. Una sentencia ve una sola foto de la base de datos y resuelve {@code horario.x} una
     * sola vez.
     */
    private Viaje cargarViaje(String tripId) {
        record Fila(String routeId, String linea, Integer nucleo, String destino, String shapeId, boolean encaja,
                Integer sentido, LocalDate inicio, LocalDate fin, Integer secuencia, String stopId, String nombre,
                double lat, double lon, int llegada, int salida, Double posicion, String wkt, Boolean cerrado) {
        }
        List<Fila> filas = jdbc.sql("""
                SELECT t.route_id, r.route_short_name, r.nucleo, t.trip_headsign, t.shape_id, t.forma_encaja,
                       t.forma_sentido, c.start_date, c.end_date,
                       st.stop_sequence, st.stop_id, s.stop_name, s.stop_lat, s.stop_lon, st.llegada_s, st.salida_s,
                       pf.posicion,
                       CASE WHEN row_number() OVER (ORDER BY st.stop_sequence) = 1 THEN ST_AsText(f.recorrido) END,
                       f.cerrado
                FROM horario.trips t
                JOIN horario.routes r ON r.route_id = t.route_id
                LEFT JOIN horario.calendar c ON c.service_id = t.service_id
                LEFT JOIN (horario.stop_times st JOIN horario.stops s ON s.stop_id = st.stop_id)
                       ON st.trip_id = t.trip_id
                LEFT JOIN horario.parada_forma pf ON pf.shape_id = t.shape_id AND pf.stop_id = st.stop_id
                LEFT JOIN horario.shapes f ON f.shape_id = t.shape_id
                WHERE t.trip_id = :trip AND NOT t.es_autobus
                ORDER BY st.stop_sequence""")
                .param("trip", tripId)
                .query((rs, n) -> new Fila(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6), (Integer) rs.getObject(7),
                        rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class), (Integer) rs.getObject(10),
                        rs.getString(11), rs.getString(12), rs.getDouble(13), rs.getDouble(14), rs.getInt(15),
                        rs.getInt(16), (Double) rs.getObject(17), rs.getString(18), (Boolean) rs.getObject(19)))
                .list();
        if (filas.isEmpty()) {
            return null;
        }
        Fila c = filas.getFirst();
        Recorrido recorrido = null;
        if (c.encaja() && c.shapeId() != null && c.sentido() != null) {
            Optional<Recorrido> r = recorridos.get(c.shapeId());
            if (r == null) {
                r = c.wkt() == null ? Optional.empty() : Optional.of(deWkt(c.shapeId(), c.wkt(), Boolean.TRUE.equals(c.cerrado())));
                recorridos.put(c.shapeId(), r);
            }
            recorrido = r.orElse(null);
        }
        int sentido = c.sentido() == null ? 1 : c.sentido();
        List<ParadaViaje> paradas = new ArrayList<>();
        for (Fila f : filas) {
            if (f.secuencia() == null) {
                continue; // viaje sin horas de paso (LEFT JOIN)
            }
            Double avance = recorrido == null || f.posicion() == null ? null
                    : sentido < 0 ? 1 - f.posicion() : f.posicion();
            paradas.add(new ParadaViaje(f.secuencia(), f.stopId(), f.nombre(), f.lat(), f.lon(), f.llegada(),
                    f.salida(), avance));
        }
        LocalDate fecha = c.inicio() != null && c.inicio().equals(c.fin()) ? c.inicio() : null;
        return new Viaje(tripId, c.routeId(), c.linea(), c.nucleo(), c.destino(), fecha, paradas, recorrido, sentido);
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
