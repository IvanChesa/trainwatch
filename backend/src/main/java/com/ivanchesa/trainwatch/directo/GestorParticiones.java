package com.ivanchesa.trainwatch.directo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ivanchesa.trainwatch.RelojConfig;

/**
 * Crea y borra las particiones de {@code posiciones} (diarias) y {@code retrasos} (mensuales). ADR 0009.
 *
 * <ul>
 * <li><b>Posiciones:</b> una partición por día de Madrid ({@code posiciones_p20261120}, de 00:00 a 00:00 hora
 * local), creada con dos días de adelanto. Se borra cuando su día entero queda más atrás que la retención (48 h):
 * borrar una partición es instantáneo y no deja filas muertas, al contrario que un {@code DELETE} de 0,26 M filas al
 * día seguido de {@code VACUUM}. Por eso en la base hay entre 48 y 72 h.</li>
 * <li><b>Retrasos:</b> una por mes ({@code retrasos_p202611}), el anterior, el actual y el siguiente. No se borran
 * nunca: son la base de las estadísticas.</li>
 * </ul>
 * No hay partición por defecto: una fila fuera de rango falla y se ve, en vez de acabar en un cajón de sastre que
 * luego impide crear la partición buena.
 *
 * <p><b>Sin parar la ingesta (fase 3b).</b> Antes, la ingesta hacía el mantenimiento en su propio hilo con un
 * {@code DROP TABLE} de la partición, que pide un cerrojo exclusivo sobre {@code posiciones} entera: con un lector
 * largo (la API de la fase 4), el {@code DROP} esperaría y, detrás de él, todos los {@code INSERT} de la ingesta.
 * Ahora:
 * <ul>
 * <li>es una tarea programada aparte ({@code trainwatch.directo.mantenimiento-particiones}, cada hora), además de al
 * arrancar;</li>
 * <li>cada sentencia lleva {@code lock_timeout} ({@code trainwatch.directo.espera-cerrojo-particiones}): si no
 * consigue el cerrojo, falla en vez de quedarse en la cola;</li>
 * <li>la partición vieja se separa con {@code DETACH PARTITION … CONCURRENTLY}, que no bloquea las escrituras en
 * {@code posiciones}, y luego se borra con {@code DROP TABLE}, que solo bloquea la tabla ya separada;</li>
 * <li>si algo falla, la pasada siguiente lo retoma: termina un {@code DETACH} a medias con {@code FINALIZE} y borra
 * las tablas que quedaron separadas.</li>
 * </ul>
 */
@Component
public class GestorParticiones {

    /** Lo que ha hecho una pasada. {@code fallidas}: lo que no se pudo hacer y se reintenta en la siguiente. */
    public record Resultado(List<String> creadas, List<String> borradas, List<String> fallidas) {
    }

    /** Una partición diaria tal como está en el catálogo. */
    private record Diaria(String nombre, boolean unida, boolean separandose) {
    }

    private static final Logger log = LoggerFactory.getLogger(GestorParticiones.class);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter LIMITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final Pattern DIARIA = Pattern.compile("posiciones_p(\\d{8})");

    private final JdbcTemplate jdbc;
    private final ZoneId zona = RelojConfig.ZONA;
    private final Duration retencion;
    private final Duration esperaCerrojo;
    private final Clock reloj;

    public GestorParticiones(JdbcTemplate jdbc, DirectoProperties propiedades, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.retencion = propiedades.retencionPosiciones();
        this.esperaCerrojo = propiedades.esperaCerrojoParticiones();
    }

    /** Al arrancar, antes del primer ciclo de la ingesta (que espera 10 s), y aunque la ingesta este desactivada. */
    @EventListener(ApplicationReadyEvent.class)
    void alArrancar() {
        pasada("al arrancar");
    }

    /** Cada hora, en su propio hilo del programador: la ingesta no lo espera. */
    @Scheduled(fixedDelayString = "${trainwatch.directo.mantenimiento-particiones:PT1H}",
            initialDelayString = "${trainwatch.directo.mantenimiento-particiones:PT1H}")
    void programado() {
        pasada("programada");
    }

    private void pasada(String cuando) {
        try {
            Resultado r = mantener(reloj.instant());
            if (!r.fallidas().isEmpty()) {
                log.warn("Particiones ({}): no se pudo con {}; se reintenta en la pasada siguiente", cuando,
                        r.fallidas());
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron mantener las particiones ({})", cuando, e);
        }
    }

    public synchronized Resultado mantener(Instant ahora) {
        List<String> creadas = new ArrayList<>();
        List<String> fallidas = new ArrayList<>();
        LocalDate hoy = LocalDate.ofInstant(ahora, zona);
        for (int d = -1; d <= 2; d++) {
            LocalDate dia = hoy.plusDays(d);
            if (dia.plusDays(1).atStartOfDay(zona).toInstant().isAfter(ahora.minus(retencion))) {
                crearDiaria(dia, creadas, fallidas);
            }
        }
        YearMonth mes = YearMonth.from(hoy);
        for (int m = -1; m <= 1; m++) {
            crearMensual(mes.plusMonths(m), creadas, fallidas);
        }
        List<String> borradas = new ArrayList<>();
        for (Diaria p : diarias()) {
            Matcher m = DIARIA.matcher(p.nombre());
            if (!m.matches()) {
                continue;
            }
            try {
                LocalDate dia = LocalDate.parse(m.group(1), DIA);
                Instant fin = dia.plusDays(1).atStartOfDay(zona).toInstant();
                if (!fin.isAfter(ahora.minus(retencion))) {
                    borrar(p);
                    borradas.add(p.nombre());
                }
            } catch (RuntimeException e) {
                // Una particion rara (fecha imposible, bloqueada) no impide revisar las demas.
                log.warn("No se pudo revisar o borrar la particion {}", p.nombre(), e);
                fallidas.add(p.nombre());
            }
        }
        if (!creadas.isEmpty() || !borradas.isEmpty()) {
            log.info("Particiones creadas: {}; borradas: {}", creadas, borradas);
        }
        return new Resultado(creadas, borradas, fallidas);
    }

    /** Nombres de las particiones (unidas) de una tabla, en orden. */
    public List<String> particiones(String tabla) {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ? ORDER BY c.relname""", String.class, tabla);
    }

    /**
     * Las particiones diarias: las unidas a {@code posiciones} (y si se están separando) y las tablas con su nombre
     * que quedaron separadas sin borrar en una pasada anterior.
     */
    private List<Diaria> diarias() {
        return jdbc.query("""
                SELECT c.relname, i.inhrelid IS NOT NULL, coalesce(i.inhdetachpending, false)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_inherits i ON i.inhrelid = c.oid
                    AND i.inhparent = to_regclass('posiciones')
                WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname ~ '^posiciones_p[0-9]{8}$'
                ORDER BY c.relname""",
                (rs, n) -> new Diaria(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)));
    }

    /** Separa (sin bloquear las escrituras) y borra. El nombre viene del catálogo y cumple el patrón. */
    private void borrar(Diaria p) {
        conEsperaCorta(st -> {
            if (p.separandose()) {
                st.execute("ALTER TABLE posiciones DETACH PARTITION " + p.nombre() + " FINALIZE");
            } else if (p.unida()) {
                st.execute("ALTER TABLE posiciones DETACH PARTITION " + p.nombre() + " CONCURRENTLY");
            }
            st.execute("DROP TABLE " + p.nombre());
        });
    }

    private void crearDiaria(LocalDate dia, List<String> creadas, List<String> fallidas) {
        String nombre = "posiciones_p" + dia.format(DIA);
        crear(nombre, "posiciones", "'" + dia.atStartOfDay(zona).format(LIMITE) + "'",
                "'" + dia.plusDays(1).atStartOfDay(zona).format(LIMITE) + "'", creadas, fallidas);
    }

    private void crearMensual(YearMonth mes, List<String> creadas, List<String> fallidas) {
        crear("retrasos_p" + mes.format(MES), "retrasos", "'" + mes.atDay(1) + "'", "'" + mes.plusMonths(1).atDay(1) + "'",
                creadas, fallidas);
    }

    private void crear(String nombre, String tabla, String desde, String hasta, List<String> creadas,
            List<String> fallidas) {
        Boolean existe = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, nombre);
        if (Boolean.TRUE.equals(existe)) {
            return;
        }
        try {
            // Todo lo que va en el SQL sale de fechas formateadas aquí: no hay texto de fuera.
            conEsperaCorta(st -> st.execute("CREATE TABLE IF NOT EXISTS " + nombre + " PARTITION OF " + tabla
                    + " FOR VALUES FROM (" + desde + ") TO (" + hasta + ")"));
            creadas.add(nombre);
        } catch (RuntimeException e) {
            // Se crean con días o un mes de adelanto: la pasada siguiente lo vuelve a intentar.
            log.warn("No se pudo crear la particion {}", nombre, e);
            fallidas.add(nombre);
        }
    }

    private interface Sentencias {
        void ejecutar(Statement st) throws SQLException;
    }

    /**
     * Ejecuta en una conexión con {@code lock_timeout}, fuera de transacción ({@code DETACH … CONCURRENTLY} no se
     * puede ejecutar dentro de una), y deja la conexión como estaba antes de devolverla al pool.
     */
    private void conEsperaCorta(Sentencias sentencias) {
        jdbc.execute((ConnectionCallback<Void>) conexion -> {
            boolean autoCommit = conexion.getAutoCommit();
            conexion.setAutoCommit(true);
            try (Statement st = conexion.createStatement()) {
                st.execute("SET lock_timeout = '" + esperaCerrojo.toMillis() + "ms'");
                try {
                    sentencias.ejecutar(st);
                } finally {
                    st.execute("RESET lock_timeout");
                }
            } finally {
                restaurar(conexion, autoCommit);
            }
            return null;
        });
    }

    private static void restaurar(Connection conexion, boolean autoCommit) throws SQLException {
        if (conexion.getAutoCommit() != autoCommit) {
            conexion.setAutoCommit(autoCommit);
        }
    }
}
