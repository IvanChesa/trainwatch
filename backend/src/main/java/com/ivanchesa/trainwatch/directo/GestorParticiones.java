package com.ivanchesa.trainwatch.directo;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ivanchesa.trainwatch.RelojConfig;

/**
 * Crea y borra las particiones de {@code posiciones} (diarias) y {@code retrasos} (mensuales). ADR 0009.
 *
 * <ul>
 * <li><b>Posiciones:</b> una partición por día de Madrid ({@code posiciones_p20261120}, de 00:00 a 00:00 hora
 * local), creada con dos días de adelanto. Se borra con {@code DROP TABLE} cuando su día entero queda más atrás que
 * la retención (48 h): borrar una partición es instantáneo y no deja filas muertas, al contrario que un
 * {@code DELETE} de 0,26 M filas al día seguido de {@code VACUUM}. Por eso en la base hay entre 48 y 72 h.</li>
 * <li><b>Retrasos:</b> una por mes ({@code retrasos_p202611}), el anterior, el actual y el siguiente. No se borran
 * nunca: son la base de las estadísticas.</li>
 * </ul>
 * No hay partición por defecto: una fila fuera de rango falla y se ve, en vez de acabar en un cajón de sastre que
 * luego impide crear la partición buena. La ingesta llama a {@link #mantenerSiToca} en cada ciclo, y como mucho
 * trabaja una vez por hora.
 */
@Component
public class GestorParticiones {

    /** Lo que ha hecho una pasada. */
    public record Resultado(List<String> creadas, List<String> borradas) {
    }

    private static final Logger log = LoggerFactory.getLogger(GestorParticiones.class);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter LIMITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final Pattern DIARIA = Pattern.compile("posiciones_p(\\d{8})");
    private static final Duration CADA = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final ZoneId zona = RelojConfig.ZONA;
    private final Duration retencion;
    private final Clock reloj;
    private Instant ultimaPasada;

    public GestorParticiones(JdbcTemplate jdbc, DirectoProperties propiedades, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.retencion = propiedades.retencionPosiciones();
    }

    /** Al arrancar, antes del primer ciclo de la ingesta (que espera 10 s), y aunque la ingesta este desactivada. */
    @EventListener(ApplicationReadyEvent.class)
    void alArrancar() {
        try {
            mantenerSiToca(reloj.instant());
        } catch (RuntimeException e) {
            log.warn("No se pudieron preparar las particiones al arrancar: {}", e.toString());
        }
    }

    /** Hace una pasada si nunca se ha hecho, si hace más de una hora de la última o si ha cambiado el día. */
    public synchronized void mantenerSiToca(Instant ahora) {
        if (ultimaPasada == null || !ahora.isBefore(ultimaPasada.plus(CADA))
                || !LocalDate.ofInstant(ahora, zona).equals(LocalDate.ofInstant(ultimaPasada, zona))) {
            mantener(ahora);
        }
    }

    public synchronized Resultado mantener(Instant ahora) {
        List<String> creadas = new ArrayList<>();
        LocalDate hoy = LocalDate.ofInstant(ahora, zona);
        for (int d = -1; d <= 2; d++) {
            LocalDate dia = hoy.plusDays(d);
            if (dia.plusDays(1).atStartOfDay(zona).toInstant().isAfter(ahora.minus(retencion))) {
                crearDiaria(dia, creadas);
            }
        }
        YearMonth mes = YearMonth.from(hoy);
        for (int m = -1; m <= 1; m++) {
            crearMensual(mes.plusMonths(m), creadas);
        }
        List<String> borradas = new ArrayList<>();
        for (String nombre : particiones("posiciones")) {
            Matcher m = DIARIA.matcher(nombre);
            if (!m.matches()) {
                continue;
            }
            try {
                LocalDate dia = LocalDate.parse(m.group(1), DIA);
                Instant fin = dia.plusDays(1).atStartOfDay(zona).toInstant();
                if (!fin.isAfter(ahora.minus(retencion))) {
                    jdbc.execute("DROP TABLE " + nombre); // el nombre viene del catalogo y cumple el patron
                    borradas.add(nombre);
                }
            } catch (RuntimeException e) {
                // Una particion rara (fecha imposible, bloqueada) no impide revisar las demas.
                log.warn("No se pudo revisar o borrar la particion {}: {}", nombre, e.toString());
            }
        }
        ultimaPasada = ahora;
        if (!creadas.isEmpty() || !borradas.isEmpty()) {
            log.info("Particiones creadas: {}; borradas: {}", creadas, borradas);
        }
        return new Resultado(creadas, borradas);
    }

    /** Nombres de las particiones de una tabla, en orden. */
    public List<String> particiones(String tabla) {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ? ORDER BY c.relname""", String.class, tabla);
    }

    private void crearDiaria(LocalDate dia, List<String> creadas) {
        String nombre = "posiciones_p" + dia.format(DIA);
        crear(nombre, "posiciones", "'" + dia.atStartOfDay(zona).format(LIMITE) + "'",
                "'" + dia.plusDays(1).atStartOfDay(zona).format(LIMITE) + "'", creadas);
    }

    private void crearMensual(YearMonth mes, List<String> creadas) {
        crear("retrasos_p" + mes.format(MES), "retrasos", "'" + mes.atDay(1) + "'", "'" + mes.plusMonths(1).atDay(1) + "'",
                creadas);
    }

    private void crear(String nombre, String tabla, String desde, String hasta, List<String> creadas) {
        Boolean existe = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, nombre);
        if (Boolean.TRUE.equals(existe)) {
            return;
        }
        // Todo lo que va en el SQL sale de fechas formateadas aquí: no hay texto de fuera.
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + nombre + " PARTITION OF " + tabla + " FOR VALUES FROM (" + desde
                + ") TO (" + hasta + ")");
        creadas.add(nombre);
    }
}
