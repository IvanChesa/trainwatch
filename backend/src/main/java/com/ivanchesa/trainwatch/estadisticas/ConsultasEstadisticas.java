package com.ivanchesa.trainwatch.estadisticas;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ivanchesa.trainwatch.estadisticas.Acumulado.Fila;

/** Las lecturas de los agregados (V6) para la API. Todo filtra por días de servicio, de {@code desde} a {@code hasta}. */
@Repository
public class ConsultasEstadisticas {

    /** Una fila de {@code estad_linea_dia}. */
    record LineaDia(LocalDate fecha, int nucleo, String linea, int viajes, int viajesConDatos, int paradasConDatos,
            int inferidas, int sinHora, Integer programados, Integer paradasProgramadas, Integer cancelados) {
    }

    /** Una fila de {@code estad_linea_hora}. */
    record LineaHora(LocalDate fecha, String linea, int hora, Fila fila) {
    }

    /** Una fila de {@code estad_estacion_dia}. */
    record EstacionDia(LocalDate fecha, int nucleo, String linea, int inferidas, int sinHora, Fila fila) {
    }

    /** Un tramo sumado en el periodo, con los nombres de las paradas del horario actual. */
    record TramoSumado(String desde, String nombreDesde, String hasta, String nombreHasta, int pasos, long suma,
            int pierden, int ganan) {
    }

    private final JdbcClient jdbc;

    public ConsultasEstadisticas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LocalDate> primerDia() {
        // El primer día con filas: un día calculado vacío (un recálculo a mano de antes de empezar) no cuenta.
        return jdbc.sql("SELECT min(fecha_servicio) FROM estad_dias WHERE filas_retrasos > 0").query(LocalDate.class)
                .list().stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** Los días calculados del periodo que tienen alguna fila (un día con la ingesta caída no cuenta). */
    List<LocalDate> diasConDatos(LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                SELECT fecha_servicio FROM estad_dias
                WHERE fecha_servicio BETWEEN :d AND :h AND filas_retrasos > 0 ORDER BY 1""")
                .param("d", desde).param("h", hasta).query(LocalDate.class).list();
    }

    boolean hayNucleo(int nucleo) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM estad_linea_dia WHERE nucleo = :n)
                    OR EXISTS (SELECT 1 FROM horario.routes WHERE nucleo = :n AND NOT es_autobus)""")
                .param("n", nucleo).query(Boolean.class).single();
    }

    /** El nombre de la línea como está en los datos (sin distinguir mayúsculas), de los agregados o del horario. */
    Optional<String> linea(int nucleo, String linea) {
        return jdbc.sql("""
                SELECT linea FROM (
                    SELECT linea, 1 AS orden FROM estad_linea_dia WHERE nucleo = :n AND lower(linea) = lower(:l)
                    UNION ALL
                    SELECT route_short_name, 2 FROM horario.routes
                    WHERE nucleo = :n AND NOT es_autobus AND lower(route_short_name) = lower(:l)) x
                ORDER BY orden LIMIT 1""")
                .param("n", nucleo).param("l", linea).query(String.class).optional();
    }

    /** El nombre de la estación en el horario actual, si está ({@code Optional} vacío si no). */
    Optional<String> nombreEstacion(String stopId) {
        return jdbc.sql("SELECT stop_name FROM horario.stops WHERE stop_id = :s").param("s", stopId)
                .query(String.class).optional();
    }

    boolean hayEstacion(String stopId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM horario.stops WHERE stop_id = :s)
                    OR EXISTS (SELECT 1 FROM estad_estacion_dia WHERE stop_id = :s)""")
                .param("s", stopId).query(Boolean.class).single();
    }

    /** Las filas por día y línea de un núcleo (o de una línea, si no es {@code null}). */
    List<LineaDia> lineaDia(int nucleo, String linea, LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                SELECT fecha_servicio, nucleo, linea, viajes, viajes_con_datos, paradas_con_datos, paradas_inferidas,
                       paradas_sin_hora, viajes_programados, paradas_programadas, cancelados
                FROM estad_linea_dia
                WHERE nucleo = :n AND (CAST(:l AS text) IS NULL OR linea = :l) AND fecha_servicio BETWEEN :d AND :h""")
                .param("n", nucleo).param("l", linea).param("d", desde).param("h", hasta)
                .query(ConsultasEstadisticas::lineaDia)
                .list();
    }

    /** Las filas de los núcleos dados, para la cobertura. */
    List<LineaDia> lineaDiaDeNucleos(List<Integer> nucleos, LocalDate desde, LocalDate hasta) {
        if (nucleos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT fecha_servicio, nucleo, linea, viajes, viajes_con_datos, paradas_con_datos, paradas_inferidas,
                       paradas_sin_hora, viajes_programados, paradas_programadas, cancelados
                FROM estad_linea_dia WHERE nucleo IN (:n) AND fecha_servicio BETWEEN :d AND :h""")
                .param("n", nucleos).param("d", desde).param("h", hasta)
                .query(ConsultasEstadisticas::lineaDia)
                .list();
    }

    List<LineaHora> lineaHora(int nucleo, String linea, LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                SELECT fecha_servicio, linea, hora, viajes, paradas, suma_minutos, menos_de_3, de_3_a_5, de_6_a_15,
                       de_16_o_mas, histograma, finales, destinos, suma_destinos, destinos_menos_de_3
                FROM estad_linea_hora
                WHERE nucleo = :n AND (CAST(:l AS text) IS NULL OR linea = :l) AND fecha_servicio BETWEEN :d AND :h""")
                .param("n", nucleo).param("l", linea).param("d", desde).param("h", hasta)
                .query((rs, i) -> new LineaHora(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getInt(3),
                        new Fila(rs.getInt(4), rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                                rs.getInt(10), histograma(rs, 11), rs.getInt(12), rs.getInt(13), rs.getLong(14),
                                rs.getInt(15))))
                .list();
    }

    List<EstacionDia> estacionDia(String stopId, LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                SELECT fecha_servicio, nucleo, linea, paradas_inferidas, paradas_sin_hora, viajes, paradas, suma_minutos,
                       menos_de_3, de_3_a_5, de_6_a_15, de_16_o_mas, histograma
                FROM estad_estacion_dia WHERE stop_id = :s AND fecha_servicio BETWEEN :d AND :h""")
                .param("s", stopId).param("d", desde).param("h", hasta)
                .query((rs, i) -> new EstacionDia(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), new Fila(rs.getInt(6), rs.getInt(7), rs.getLong(8), rs.getInt(9),
                                rs.getInt(10), rs.getInt(11), rs.getInt(12), histograma(rs, 13), 0, 0, 0, 0)))
                .list();
    }

    /**
     * Los tramos de la línea que más minutos pierden de media en el periodo, solo con {@code minimo} pasos o más.
     * Empate: más pasos primero.
     */
    List<TramoSumado> tramos(int nucleo, String linea, LocalDate desde, LocalDate hasta, int minimo, int limite) {
        return jdbc.sql("""
                SELECT t.desde_stop_id, sd.stop_name, t.hasta_stop_id, sh.stop_name, t.pasos, t.suma, t.pierden, t.ganan
                FROM (
                    SELECT desde_stop_id, hasta_stop_id, sum(pasos) AS pasos, sum(suma_diferencia) AS suma,
                           sum(pierden_2_o_mas) AS pierden, sum(ganan_2_o_mas) AS ganan
                    FROM estad_tramo_dia
                    WHERE nucleo = :n AND linea = :l AND fecha_servicio BETWEEN :d AND :h
                    GROUP BY desde_stop_id, hasta_stop_id
                    HAVING sum(pasos) >= :minimo) t
                LEFT JOIN horario.stops sd ON sd.stop_id = t.desde_stop_id
                LEFT JOIN horario.stops sh ON sh.stop_id = t.hasta_stop_id
                ORDER BY t.suma::numeric / t.pasos DESC, t.pasos DESC, t.desde_stop_id, t.hasta_stop_id
                LIMIT :limite""")
                .param("n", nucleo).param("l", linea).param("d", desde).param("h", hasta).param("minimo", minimo)
                .param("limite", limite)
                .query((rs, i) -> new TramoSumado(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getInt(8)))
                .list();
    }

    private static LineaDia lineaDia(ResultSet rs, int i) throws SQLException {
        return new LineaDia(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                rs.getInt(6), rs.getInt(7), rs.getInt(8), (Integer) rs.getObject(9), (Integer) rs.getObject(10),
                (Integer) rs.getObject(11));
    }

    private static int[] histograma(ResultSet rs, int columna) throws SQLException {
        Array a = rs.getArray(columna);
        Integer[] valores = (Integer[]) a.getArray();
        int[] h = new int[Acumulado.POSICIONES];
        for (int i = 0; i < h.length && i < valores.length; i++) {
            h[i] = valores[i];
        }
        return h;
    }
}
