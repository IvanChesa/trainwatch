package com.ivanchesa.trainwatch.directo;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Escribe lo que sale de la ingesta en {@code posiciones}, {@code retrasos} y {@code avisos}, por lotes. */
@Repository
public class RepositorioDirecto {

    /** Un aviso colgado de una línea o de una estación. */
    public record Aviso(String avisoId, String routeId, String stopId, Integer nucleo, String texto,
            java.time.Instant inicio, java.time.Instant fin, java.time.Instant recibido) {
    }

    private final JdbcTemplate jdbc;

    public RepositorioDirecto(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Una fila por tren que ha cambiado. Devuelve las filas escritas. */
    public int guardarPosiciones(List<Tren> trenes) {
        if (trenes.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                INSERT INTO posiciones (instante, trip_id, vehiculo, fecha_servicio, nucleo, linea, estado, estado_feed,
                    stop_id, stop_sequence, lat, lon, retraso_s, fraccion, lat_estimada, lon_estimada, rumbo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", trenes, 500, (ps, t) -> {
            PosicionEstimada e = t.estimada();
            ps.setTimestamp(1, Timestamp.from(t.marca()));
            ps.setString(2, t.tripId() != null ? t.tripId() : t.id());
            ps.setString(3, t.vehiculo());
            ps.setDate(4, t.fechaServicio() == null ? null : Date.valueOf(t.fechaServicio()));
            setInt(ps, 5, t.nucleo());
            ps.setString(6, t.linea());
            ps.setString(7, t.estado().name());
            ps.setString(8, t.estadoFeed());
            ps.setString(9, t.stopId());
            setInt(ps, 10, t.stopSequence());
            setDouble(ps, 11, t.lat());
            setDouble(ps, 12, t.lon());
            setInt(ps, 13, t.retrasoS());
            setDouble(ps, 14, e == null ? null : e.fraccion());
            setDouble(ps, 15, e == null ? null : e.lat());
            setDouble(ps, 16, e == null ? null : e.lon());
            setDouble(ps, 17, e == null ? null : e.rumbo());
        });
        return trenes.size();
    }

    /**
     * Los retrasos finales cerrados. Si la misma parada se cierra dos veces (un reinicio, un viaje que vuelve atrás
     * en el feed), manda la última.
     */
    public int guardarRetrasos(List<RetrasoFinal> retrasos) {
        if (retrasos.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                INSERT INTO retrasos (fecha_servicio, trip_id, stop_sequence, stop_id, route_id, linea, nucleo, minutos,
                    registrado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fecha_servicio, trip_id, stop_sequence)
                DO UPDATE SET minutos = EXCLUDED.minutos, registrado = EXCLUDED.registrado""", retrasos, 500,
                (ps, r) -> {
                    ps.setDate(1, Date.valueOf(r.fechaServicio()));
                    ps.setString(2, r.tripId());
                    ps.setInt(3, r.stopSequence());
                    ps.setString(4, r.stopId());
                    ps.setString(5, r.routeId());
                    ps.setString(6, r.linea());
                    setInt(ps, 7, r.nucleo());
                    ps.setInt(8, r.minutos());
                    ps.setTimestamp(9, Timestamp.from(r.registrado()));
                });
        return retrasos.size();
    }

    /** Sustituye todos los avisos por los de la muestra nueva, en una transacción: nadie ve la tabla vacía. */
    @Transactional
    public int sustituirAvisos(List<Aviso> avisos) {
        jdbc.update("DELETE FROM avisos");
        if (avisos.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                INSERT INTO avisos (aviso_id, route_id, stop_id, nucleo, texto, inicio, fin, recibido)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", avisos, 500, (ps, a) -> {
            ps.setString(1, a.avisoId());
            ps.setString(2, a.routeId());
            ps.setString(3, a.stopId());
            setInt(ps, 4, a.nucleo());
            ps.setString(5, a.texto());
            ps.setTimestamp(6, a.inicio() == null ? null : Timestamp.from(a.inicio()));
            ps.setTimestamp(7, a.fin() == null ? null : Timestamp.from(a.fin()));
            ps.setTimestamp(8, Timestamp.from(a.recibido()));
        });
        return avisos.size();
    }

    private static void setInt(PreparedStatement ps, int i, Integer valor) throws SQLException {
        if (valor == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, valor);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double valor) throws SQLException {
        if (valor == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, valor);
        }
    }
}
