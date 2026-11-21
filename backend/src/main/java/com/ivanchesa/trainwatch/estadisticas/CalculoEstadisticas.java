package com.ivanchesa.trainwatch.estadisticas;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ivanchesa.trainwatch.RelojConfig;
import com.ivanchesa.trainwatch.horario.ImportadorHorario;

/**
 * Calcula los agregados de puntualidad de un día de servicio (ADR 0025 y 0026) a partir de {@code retrasos}, del
 * horario (viajes programados y orden de las paradas) y de {@code posiciones} (cancelados).
 *
 * <p>Cada día va en una transacción que borra lo que hubiera de ese día y lo vuelve a escribir: recalcular da lo
 * mismo. Solo lee las tablas de la ingesta ({@code ACCESS SHARE}, compatible con sus {@code INSERT}) y solo escribe en
 * las suyas. Si el horario ya no cubre el día o su partición de {@code posiciones} ya se borró, conserva los viajes
 * programados o los cancelados que tuviera.
 */
@Component
public class CalculoEstadisticas {

    private static final Logger log = LoggerFactory.getLogger(CalculoEstadisticas.class);

    /** Primera clave del cerrojo consultivo por día ({@code pg_advisory_xact_lock(6, día)}): la fase 6. */
    private static final int CLASE_CERROJO = 6;

    /** Lo que sale del cálculo de un día. */
    public record Resultado(LocalDate fecha, int filasRetrasos, int lineasHora, int lineasDia, int estaciones,
            int tramos, boolean conHorario, boolean conPosiciones, long duracionMs) {
    }

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transaccion;
    private final EstadisticasProperties propiedades;
    private final Clock reloj;

    public CalculoEstadisticas(JdbcClient jdbc, DataSource dataSource, PlatformTransactionManager transacciones,
            EstadisticasProperties propiedades, Clock reloj) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transaccion = new TransactionTemplate(transacciones);
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    /**
     * Cada noche, a las 03:45 de Madrid: ayer ya ha terminado (los últimos trenes llegan hacia las 01:30) y el horario
     * nuevo no llega hasta las 04:30. Calcula ayer y recupera los días anteriores que no estén calculados.
     */
    @Scheduled(cron = "${trainwatch.estadisticas.cron:0 45 3 * * *}", zone = "Europe/Madrid")
    void nocturna() {
        LocalDate ayer = LocalDate.now(reloj.withZone(RelojConfig.ZONA)).minusDays(1);
        List<LocalDate> dias = new ArrayList<>();
        for (int i = propiedades.diasARecuperar(); i >= 1; i--) {
            LocalDate d = ayer.minusDays(i);
            if (!calculado(d)) {
                dias.add(d);
            }
        }
        dias.add(ayer);
        for (LocalDate d : dias) {
            try {
                Resultado r = calcular(d);
                log.info("Estadísticas del {}: {} filas de retrasos, {} líneas y horas, {} tramos en {} ms{}{}", d,
                        r.filasRetrasos(), r.lineasHora(), r.tramos(), r.duracionMs(),
                        r.conHorario() ? "" : " (sin horario: se conservan los viajes programados)",
                        r.conPosiciones() ? "" : " (sin posiciones: se conservan los cancelados)");
            } catch (RuntimeException e) {
                log.error("No se pudieron calcular las estadísticas del {}", d, e);
            }
        }
    }

    /** Recalcula los días de {@code desde} a {@code hasta}, ambos incluidos, cada uno en su transacción. */
    public List<Resultado> recalcular(LocalDate desde, LocalDate hasta) {
        validar(desde, hasta);
        List<Resultado> resultados = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            resultados.add(calcular(d));
        }
        return resultados;
    }

    /** Un intervalo que se puede recalcular de una vez: {@code desde ≤ hasta} y no más del periodo máximo. */
    void validar(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
        long dias = java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) + 1;
        if (dias > propiedades.periodoMaximoDias()) {
            throw new IllegalArgumentException("Como mucho " + propiedades.periodoMaximoDias() + " días de una vez");
        }
    }

    boolean calculado(LocalDate fecha) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM estad_dias WHERE fecha_servicio = :f)").param("f", fecha)
                .query(Boolean.class).single();
    }

    /**
     * Calcula (o recalcula) un día de servicio entero, en una transacción:
     * <ul>
     * <li>{@code statement_timeout} y {@code lock_timeout} solo en esta transacción, y el plazo de red de la conexión
     * (el {@code socketTimeout} de 30 s del driver) un minuto por encima, para que una sentencia lenta la corte
     * PostgreSQL con un error limpio y no el driver a los 30 s. Hikari devuelve el plazo de red al soltar la
     * conexión;</li>
     * <li>el cerrojo consultivo de la importación del horario, en modo compartido: mientras dura el cálculo, el horario
     * no cambia de esquema a mitad (si la importación está en marcha, se espera como mucho {@code lock_timeout} y
     * falla); y un cerrojo por día, para que la nocturna y un recálculo a mano no escriban el mismo día a la vez.</li>
     * </ul>
     */
    public Resultado calcular(LocalDate fecha) {
        long t0 = System.nanoTime();
        return transaccion.execute(estado -> {
            Connection conexion = DataSourceUtils.getConnection(dataSource); // la de esta transacción
            try {
                conexion.setNetworkTimeout(Runnable::run,
                        (int) propiedades.plazoSentencia().plusMinutes(1).toMillis());
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo ampliar el plazo de red de la conexión", e);
            }
            jdbc.sql("SELECT set_config('statement_timeout', :v, true)")
                    .param("v", propiedades.plazoSentencia().toMillis() + "ms").query(String.class).single();
            jdbc.sql("SELECT set_config('lock_timeout', :v, true)")
                    .param("v", propiedades.esperaCerrojo().toMillis() + "ms").query(String.class).single();
            jdbc.sql("SELECT pg_advisory_xact_lock_shared(:clave)").param("clave", ImportadorHorario.CLAVE_CERROJO)
                    .query((rs, n) -> 1).single();
            jdbc.sql("SELECT pg_advisory_xact_lock(:clase, :dia)").param("clase", CLASE_CERROJO)
                    .param("dia", (int) fecha.toEpochDay()).query((rs, n) -> 1).single();
            return calcularEnTransaccion(fecha, t0);
        });
    }

    private Resultado calcularEnTransaccion(LocalDate fecha, long t0) {
        boolean conHorario = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM horario.trips t JOIN horario.calendar c ON c.service_id = t.service_id
                    WHERE NOT t.es_autobus AND c.start_date <= :f AND c.end_date >= :f AND c.%s)"""
                .formatted(columnaDia(fecha)))
                .param("f", fecha).query(Boolean.class).single();
        // La partición del día unida a posiciones (no una tabla separada a medio borrar: DETACH hecho, DROP no).
        boolean conPosiciones = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = 'posiciones'::regclass AND c.relname = :nombre AND NOT i.inhdetachpending)""")
                .param("nombre", "posiciones_p" + fecha.toString().replace("-", "")).query(Boolean.class).single();
        int filas = jdbc.sql("SELECT count(*) FROM retrasos WHERE fecha_servicio = :f").param("f", fecha)
                .query(Integer.class).single();

        // Lo que no se puede volver a sacar si su fuente ya no está: viajes y paradas programados y llegadas a destino
        // (horario), cancelados (posiciones) y tramos (el orden de las paradas sale del horario).
        jdbc.sql("""
                CREATE TEMP TABLE estad_anterior_hora ON COMMIT DROP AS
                SELECT nucleo, linea, hora, destinos, suma_destinos, destinos_menos_de_3
                FROM estad_linea_hora WHERE fecha_servicio = :f""")
                .param("f", fecha).update();
        boolean conservarTramos = !conHorario && jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM estad_tramo_dia WHERE fecha_servicio = :f)").param("f", fecha)
                .query(Boolean.class).single();
        jdbc.sql("""
                CREATE TEMP TABLE estad_anterior ON COMMIT DROP AS
                SELECT nucleo, linea, viajes_programados, paradas_programadas, cancelados
                FROM estad_linea_dia WHERE fecha_servicio = :f""")
                .param("f", fecha).update();
        for (String tabla : List.of("estad_linea_hora", "estad_linea_dia", "estad_estacion_dia", "estad_tramo_dia",
                "estad_dias")) {
            if (!(conservarTramos && tabla.equals("estad_tramo_dia"))) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE fecha_servicio = :f").param("f", fecha).update();
            }
        }

        int lineasHora = jdbc.sql(SQL_LINEA_HORA).param("f", fecha).param("conHorario", conHorario).update();
        OffsetDateTime inicio = fecha.atStartOfDay(RelojConfig.ZONA).toOffsetDateTime();
        OffsetDateTime fin = fecha.plusDays(2).atStartOfDay(RelojConfig.ZONA).toOffsetDateTime();
        int lineasDia = jdbc.sql(SQL_LINEA_DIA.formatted(columnaDia(fecha))).param("f", fecha)
                .param("conHorario", conHorario).param("conPosiciones", conPosiciones)
                .param("inicio", inicio).param("fin", fin).update();
        int estaciones = jdbc.sql(SQL_ESTACION_DIA).param("f", fecha).update();
        int tramos = conservarTramos
                ? jdbc.sql("SELECT count(*) FROM estad_tramo_dia WHERE fecha_servicio = :f").param("f", fecha)
                        .query(Integer.class).single()
                : jdbc.sql(SQL_TRAMO_DIA).param("f", fecha).update();
        jdbc.sql("""
                INSERT INTO estad_dias (fecha_servicio, calculado, filas_retrasos, con_horario, con_posiciones)
                VALUES (:f, :ahora, :filas, :conHorario, :conPosiciones)""")
                .param("f", fecha).param("ahora", OffsetDateTime.now(reloj)).param("filas", filas)
                .param("conHorario", conHorario).param("conPosiciones", conPosiciones).update();
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        return new Resultado(fecha, filas, lineasHora, lineasDia, estaciones, tramos, conHorario, conPosiciones, ms);
    }

    /** La columna de {@code horario.calendar} del día de la semana. Sale de un enum, nunca de fuera. */
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

    // ---------------------------------------------------------------- SQL

    /**
     * Las filas del día que pueden entrar: con núcleo y línea, sin autobuses. {@code valida}: vista (no inferida) y con
     * hora programada, las únicas que entran en las cifras (ADR 0025). {@code hora}: la programada, en hora de Madrid.
     */
    static final String BASE = """
            base AS (
                SELECT r.trip_id, r.stop_sequence, r.stop_id, r.nucleo, r.linea, r.minutos, r.inferido,
                       r.llegada_programada,
                       (NOT r.inferido AND r.llegada_programada IS NOT NULL) AS valida,
                       extract(hour FROM r.llegada_programada AT TIME ZONE 'Europe/Madrid')::smallint AS hora
                FROM retrasos r
                WHERE r.fecha_servicio = :f AND r.nucleo IS NOT NULL AND r.linea IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM horario.routes b WHERE b.route_id = r.route_id AND b.es_autobus))""";

    /**
     * El histograma por minuto (61 posiciones: de 0 o menos a 60 o más) de las filas válidas de {@code base}, por las
     * columnas que se pasan. Cada grupo tiene sus 61 posiciones, aunque estén a 0 ({@code generate_series}).
     */
    static String histogramas(String claves) {
        String[] cs = claves.split(",\\s*");
        StringBuilder igual = new StringBuilder();
        StringBuilder deK = new StringBuilder();
        for (String c : cs) {
            igual.append(" AND p.").append(c).append(" = k.").append(c);
            deK.append("k.").append(c).append(", ");
        }
        return """
                por_minuto AS (
                    SELECT %1$s, least(greatest(minutos, 0), 60) AS m, count(*)::int AS n
                    FROM base WHERE valida GROUP BY %1$s, least(greatest(minutos, 0), 60)),
                histogramas AS (
                    SELECT %2$sarray_agg(coalesce(p.n, 0) ORDER BY g.m) AS histograma
                    FROM (SELECT DISTINCT %1$s FROM base WHERE valida) k
                    CROSS JOIN generate_series(0, 60) AS g(m)
                    LEFT JOIN por_minuto p ON p.m = g.m%3$s
                    GROUP BY %4$s)""".formatted(claves, deK, igual, deK.substring(0, deK.length() - 2));
    }

    static final String SQL_LINEA_HORA = """
            WITH %s,
            %s,
            ordenadas AS (
                SELECT b.*, row_number() OVER (PARTITION BY b.trip_id ORDER BY b.stop_sequence DESC) = 1 AS final
                FROM base b WHERE b.valida),
            vistas AS (
                -- destino: la última parada vista del viaje es la última que le da el horario.
                SELECT o.*, o.final AND o.stop_sequence = (SELECT max(s.stop_sequence) FROM horario.stop_times s
                                                           WHERE s.trip_id = o.trip_id) AS destino
                FROM ordenadas o)
            INSERT INTO estad_linea_hora (fecha_servicio, nucleo, linea, hora, viajes, paradas, suma_minutos, menos_de_3,
                de_3_a_5, de_6_a_15, de_16_o_mas, histograma, finales, destinos, suma_destinos, destinos_menos_de_3)
            SELECT CAST(:f AS date), v.nucleo, v.linea, v.hora, count(DISTINCT v.trip_id), count(*), sum(v.minutos),
                   count(*) FILTER (WHERE v.minutos < 3),
                   count(*) FILTER (WHERE v.minutos BETWEEN 3 AND 5),
                   count(*) FILTER (WHERE v.minutos BETWEEN 6 AND 15),
                   count(*) FILTER (WHERE v.minutos >= 16),
                   h.histograma,
                   count(*) FILTER (WHERE v.final),
                   CASE WHEN CAST(:conHorario AS boolean) THEN count(*) FILTER (WHERE v.destino)
                        ELSE coalesce(max(a.destinos), 0) END,
                   CASE WHEN CAST(:conHorario AS boolean) THEN coalesce(sum(v.minutos) FILTER (WHERE v.destino), 0)
                        ELSE coalesce(max(a.suma_destinos), 0) END,
                   CASE WHEN CAST(:conHorario AS boolean) THEN count(*) FILTER (WHERE v.destino AND v.minutos < 3)
                        ELSE coalesce(max(a.destinos_menos_de_3), 0) END
            FROM vistas v
            JOIN histogramas h ON h.nucleo = v.nucleo AND h.linea = v.linea AND h.hora = v.hora
            LEFT JOIN estad_anterior_hora a ON a.nucleo = v.nucleo AND a.linea = v.linea AND a.hora = v.hora
            GROUP BY v.nucleo, v.linea, v.hora, h.histograma""".formatted(BASE, histogramas("nucleo, linea, hora"));

    /**
     * Por línea: viajes distintos, lo que se quita y la cobertura. Las líneas del horario sin ningún viaje visto también
     * tienen fila (cuentan en la cobertura). Los programados salen del horario y los cancelados de {@code posiciones},
     * o de {@code estad_anterior} si su fuente ya no tiene el día.
     */
    static final String SQL_LINEA_DIA = """
            WITH %s,
            r AS (
                SELECT nucleo, linea,
                       count(DISTINCT trip_id) FILTER (WHERE valida) AS viajes,
                       count(DISTINCT trip_id) AS viajes_con_datos,
                       count(*) AS paradas_con_datos,
                       count(*) FILTER (WHERE inferido) AS inferidas,
                       count(*) FILTER (WHERE NOT inferido AND llegada_programada IS NULL) AS sin_hora
                FROM base GROUP BY nucleo, linea),
            p AS (
                SELECT ro.nucleo, ro.route_short_name AS linea, count(*) AS programados,
                       sum((SELECT count(*) FROM horario.stop_times s WHERE s.trip_id = t.trip_id)) AS paradas_programadas
                FROM horario.trips t
                JOIN horario.routes ro ON ro.route_id = t.route_id
                JOIN horario.calendar c ON c.service_id = t.service_id
                WHERE NOT t.es_autobus AND ro.nucleo IS NOT NULL
                  AND c.start_date <= :f AND c.end_date >= :f AND c.%%s
                GROUP BY ro.nucleo, ro.route_short_name),
            c AS (
                SELECT nucleo, linea, count(DISTINCT trip_id) AS cancelados
                FROM posiciones
                WHERE instante >= :inicio AND instante < :fin AND fecha_servicio = :f AND estado = 'CANCELADO'
                  AND nucleo IS NOT NULL AND linea IS NOT NULL
                GROUP BY nucleo, linea),
            juntas AS (
                SELECT nucleo, linea, coalesce(r.viajes, 0) AS viajes, coalesce(r.viajes_con_datos, 0) AS viajes_con_datos,
                       coalesce(r.paradas_con_datos, 0) AS paradas_con_datos,
                       coalesce(r.inferidas, 0) AS inferidas, coalesce(r.sin_hora, 0) AS sin_hora,
                       CASE WHEN CAST(:conHorario AS boolean) THEN coalesce(p.programados, 0)
                            ELSE a.viajes_programados END AS programados,
                       CASE WHEN CAST(:conHorario AS boolean) THEN coalesce(p.paradas_programadas, 0)
                            ELSE a.paradas_programadas END AS paradas_programadas,
                       CASE WHEN CAST(:conPosiciones AS boolean) THEN coalesce(c.cancelados, 0)
                            ELSE a.cancelados END AS cancelados
                FROM r FULL JOIN p USING (nucleo, linea) FULL JOIN c USING (nucleo, linea)
                FULL JOIN estad_anterior a USING (nucleo, linea))
            INSERT INTO estad_linea_dia (fecha_servicio, nucleo, linea, viajes, viajes_con_datos, paradas_con_datos,
                paradas_inferidas, paradas_sin_hora, viajes_programados, paradas_programadas, cancelados)
            SELECT CAST(:f AS date), nucleo, linea, viajes, viajes_con_datos, paradas_con_datos, inferidas, sin_hora,
                   programados, paradas_programadas, cancelados
            FROM juntas
            WHERE viajes_con_datos > 0 OR coalesce(programados, 0) > 0 OR coalesce(cancelados, 0) > 0""".formatted(BASE);

    static final String SQL_ESTACION_DIA = """
            WITH %s,
            %s
            INSERT INTO estad_estacion_dia (fecha_servicio, stop_id, nucleo, linea, viajes, paradas, suma_minutos,
                menos_de_3, de_3_a_5, de_6_a_15, de_16_o_mas, histograma, paradas_inferidas, paradas_sin_hora)
            SELECT CAST(:f AS date), b.stop_id, b.nucleo, b.linea,
                   count(DISTINCT b.trip_id) FILTER (WHERE b.valida),
                   count(*) FILTER (WHERE b.valida),
                   coalesce(sum(b.minutos) FILTER (WHERE b.valida), 0),
                   count(*) FILTER (WHERE b.valida AND b.minutos < 3),
                   count(*) FILTER (WHERE b.valida AND b.minutos BETWEEN 3 AND 5),
                   count(*) FILTER (WHERE b.valida AND b.minutos BETWEEN 6 AND 15),
                   count(*) FILTER (WHERE b.valida AND b.minutos >= 16),
                   coalesce(h.histograma, array_fill(0, ARRAY[61])),
                   count(*) FILTER (WHERE b.inferido),
                   count(*) FILTER (WHERE NOT b.inferido AND b.llegada_programada IS NULL)
            FROM base b
            LEFT JOIN histogramas h ON h.nucleo = b.nucleo AND h.linea = b.linea AND h.stop_id = b.stop_id
            GROUP BY b.stop_id, b.nucleo, b.linea, h.histograma""".formatted(BASE, histogramas("nucleo, linea, stop_id"));

    /**
     * Tramos: con {@code LAG} sobre todas las filas del viaje en orden (las inferidas cortan el tramo). Cuenta si las
     * dos paradas son válidas y, si el horario conoce el viaje, la anterior es justo la que el horario pone antes
     * ({@code stop_sequence} de Renfe tiene huecos). Al recalcular un día que el horario ya no cubre, se conservan los
     * tramos que hubiera; si no hay ninguno, vale la fila anterior de {@code retrasos}.
     */
    static final String SQL_TRAMO_DIA = """
            WITH %s,
            filas AS (
                SELECT b.nucleo, b.linea, b.stop_id, b.stop_sequence, b.minutos, b.valida,
                       lag(b.stop_sequence) OVER w AS seq_anterior,
                       lag(b.stop_id) OVER w AS stop_anterior,
                       lag(b.minutos) OVER w AS minutos_anterior,
                       lag(b.valida) OVER w AS valida_anterior,
                       (SELECT max(s.stop_sequence) FROM horario.stop_times s
                        WHERE s.trip_id = b.trip_id AND s.stop_sequence < b.stop_sequence) AS seq_anterior_horario,
                       EXISTS (SELECT 1 FROM horario.stop_times s WHERE s.trip_id = b.trip_id) AS en_horario
                FROM base b
                WINDOW w AS (PARTITION BY b.trip_id ORDER BY b.stop_sequence))
            INSERT INTO estad_tramo_dia (fecha_servicio, nucleo, linea, desde_stop_id, hasta_stop_id, pasos,
                suma_diferencia, pierden_2_o_mas, ganan_2_o_mas)
            SELECT CAST(:f AS date), nucleo, linea, stop_anterior, stop_id, count(*),
                   sum(minutos - minutos_anterior),
                   count(*) FILTER (WHERE minutos - minutos_anterior >= 2),
                   count(*) FILTER (WHERE minutos - minutos_anterior <= -2)
            FROM filas
            WHERE valida AND valida_anterior
              AND (NOT en_horario OR seq_anterior = seq_anterior_horario)
            GROUP BY nucleo, linea, stop_anterior, stop_id""".formatted(BASE);
}
