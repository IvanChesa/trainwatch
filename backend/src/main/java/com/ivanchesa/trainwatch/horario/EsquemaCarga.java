package com.ivanchesa.trainwatch.horario;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * El SQL de la importación. El horario en uso está en el esquema {@value #VIVO}; la importación llena
 * {@value #CARGA}, una copia vacía con la misma estructura, y al final cambia los nombres de los dos esquemas en
 * una transacción ({@link #cambiar}). Mientras tanto, la aplicación sigue leyendo {@value #VIVO} sin enterarse.
 */
@Component
public class EsquemaCarga {

    public static final String VIVO = "horario";
    public static final String CARGA = "horario_carga";
    public static final String ANTERIOR = "horario_anterior";
    /** Puntos de shapes.txt tal cual; solo existe en el esquema de carga y se borra antes del cambio. */
    public static final String TABLA_PUNTOS = "shape_puntos";

    /** Tablas cuyo número de filas se registra (y se valida) en cada importación. */
    public static final List<String> TABLAS = List.of("stops", "routes", "calendar", "trips", "stop_times", "shapes",
            "parada_forma", "transfers");

    private static final Logger log = LoggerFactory.getLogger(EsquemaCarga.class);

    private final JdbcClient jdbc;

    public EsquemaCarga(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Borra lo que haya quedado de una importación anterior y crea {@value #CARGA} con las tablas de {@value #VIVO}
     * (columnas, tipos, valores por defecto, columnas generadas y CHECK), pero sin índices: se crean después de
     * cargar, que es mucho más rápido que mantenerlos fila a fila.
     */
    public void preparar() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + CARGA + " CASCADE").update();
        jdbc.sql("DROP SCHEMA IF EXISTS " + ANTERIOR + " CASCADE").update();
        jdbc.sql("CREATE SCHEMA " + CARGA).update();
        for (String tabla : tablasDe(VIVO)) {
            jdbc.sql("CREATE TABLE %s.%s (LIKE %s.%s INCLUDING ALL EXCLUDING INDEXES)"
                    .formatted(CARGA, tabla, VIVO, tabla)).update();
        }
        jdbc.sql("CREATE UNLOGGED TABLE " + CARGA + "." + TABLA_PUNTOS
                + " (shape_id TEXT NOT NULL, lat DOUBLE PRECISION NOT NULL, lon DOUBLE PRECISION NOT NULL,"
                + " secuencia INTEGER NOT NULL)").update();
    }

    /**
     * Crea en {@value #CARGA} las claves primarias, las restricciones únicas y los índices que tiene {@value #VIVO},
     * leyéndolos del catálogo de PostgreSQL. Así la migración de Flyway es la única definición del esquema.
     */
    public void crearIndices() {
        List<String> sentencias = jdbc.sql("""
                SELECT format('ALTER TABLE %s.%I ADD CONSTRAINT %I %s', :carga, cl.relname, con.conname,
                              pg_get_constraintdef(con.oid))
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid
                WHERE con.connamespace = to_regnamespace(:vivo) AND con.contype IN ('p', 'u', 'x')
                UNION ALL
                SELECT regexp_replace(pg_get_indexdef(i.indexrelid), ' ON (ONLY )?' || :vivo || '\\.',
                                      ' ON \\1' || :carga || '.')
                FROM pg_index i
                JOIN pg_class ic ON ic.oid = i.indexrelid
                WHERE ic.relnamespace = to_regnamespace(:vivo)
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
                """).param("vivo", VIVO).param("carga", CARGA).query(String.class).list();
        for (String sql : sentencias) {
            if (!sql.contains(" " + CARGA + ".")) {
                throw new IllegalStateException("Índice sin el esquema de carga: " + sql);
            }
            jdbc.sql(sql).update();
        }
        log.info("Horario: {} índices y claves creados en {}", sentencias.size(), CARGA);
    }

    /** Estadísticas para el planificador: sin ellas, las consultas de {@link #calcularDerivados} van a ciegas. */
    public void analizar() {
        for (String tabla : tablasDe(CARGA)) {
            jdbc.sql("ANALYZE " + CARGA + "." + tabla).update();
        }
    }

    /**
     * Lo que no viene tal cual en el GTFS:
     * <ol>
     * <li>los recorridos como LineString, uniendo los puntos de shapes.txt en orden;</li>
     * <li>la marca de autobús de cada viaje, copiada de su línea;</li>
     * <li>cada parada proyectada sobre cada recorrido que la usa ({@code ST_LineLocatePoint});</li>
     * <li>si las paradas de cada viaje avanzan en orden sobre su recorrido, y en qué sentido.</li>
     * </ol>
     */
    public void calcularDerivados(double distanciaMaximaM, double toleranciaRetrocesoM) {
        jdbc.sql("""
                INSERT INTO %1$s.shapes (shape_id, recorrido, puntos, longitud_m, cerrado)
                SELECT shape_id, recorrido, ST_NPoints(recorrido), ST_Length(recorrido::geography),
                       ST_Distance(ST_StartPoint(recorrido)::geography, ST_EndPoint(recorrido)::geography) < 100
                FROM (SELECT shape_id,
                             ST_SetSRID(ST_MakeLine(ST_MakePoint(lon, lat) ORDER BY secuencia), 4326) AS recorrido
                      FROM %1$s.%2$s GROUP BY shape_id) l
                WHERE ST_NPoints(recorrido) >= 2
                """.formatted(CARGA, TABLA_PUNTOS)).update();
        jdbc.sql("DROP TABLE " + CARGA + "." + TABLA_PUNTOS).update();

        jdbc.sql("""
                UPDATE %1$s.trips t SET es_autobus = true
                FROM %1$s.routes r WHERE r.route_id = t.route_id AND r.es_autobus
                """.formatted(CARGA)).update();

        // Pocas parejas distintas (recorrido, parada): unos miles, frente a 1,8 M horas de paso.
        jdbc.sql("""
                INSERT INTO %1$s.parada_forma (shape_id, stop_id, posicion, distancia_m)
                SELECT p.shape_id, p.stop_id,
                       ST_LineLocatePoint(f.recorrido, s.ubicacion),
                       ST_Distance(ST_ClosestPoint(f.recorrido, s.ubicacion)::geography, s.ubicacion::geography)
                FROM (SELECT DISTINCT t.shape_id, st.stop_id
                      FROM %1$s.trips t JOIN %1$s.stop_times st ON st.trip_id = t.trip_id
                      WHERE t.shape_id IS NOT NULL) p
                JOIN %1$s.shapes f ON f.shape_id = p.shape_id
                JOIN %1$s.stops s ON s.stop_id = p.stop_id
                """.formatted(CARGA)).update();

        // Para cada viaje: cuánto avanza (en metros sobre el recorrido) de cada parada a la siguiente.
        // Sube = nunca retrocede más que la tolerancia; baja = nunca avanza más que la tolerancia. En un recorrido
        // cerrado el avance se toma módulo 1, en (-0,5, 0,5]: pasar de 0,9 a 0,1 es avanzar 0,2, no retroceder 0,8.
        jdbc.sql("""
                WITH pasos AS (
                    SELECT st.trip_id, pf.distancia_m, pf.posicion IS NULL AS sin_proyectar, f.cerrado, f.longitud_m,
                           pf.posicion - lag(pf.posicion) OVER (PARTITION BY st.trip_id ORDER BY st.stop_sequence)
                               AS avance
                    FROM %1$s.stop_times st
                    JOIN %1$s.trips t ON t.trip_id = st.trip_id
                    JOIN %1$s.shapes f ON f.shape_id = t.shape_id
                    LEFT JOIN %1$s.parada_forma pf ON pf.shape_id = t.shape_id AND pf.stop_id = st.stop_id
                ), pasos_m AS (
                    SELECT trip_id, distancia_m, sin_proyectar,
                           CASE WHEN cerrado THEN avance - ceil(avance - 0.5) ELSE avance END * longitud_m AS avance_m
                    FROM pasos
                ), viajes AS (
                    SELECT trip_id, count(*) AS paradas,
                           bool_and(avance_m IS NULL OR avance_m >= -:tolerancia) AS sube,
                           bool_and(avance_m IS NULL OR avance_m <= :tolerancia) AS baja,
                           max(distancia_m) AS distancia_max,
                           bool_or(sin_proyectar) AS alguna_sin_proyectar
                    FROM pasos_m GROUP BY trip_id
                )
                UPDATE %1$s.trips t SET
                    forma_sentido = CASE WHEN v.paradas < 2 THEN NULL
                                         WHEN v.sube AND NOT v.baja THEN 1
                                         WHEN v.baja AND NOT v.sube THEN -1 END,
                    forma_distancia_max_m = v.distancia_max,
                    forma_encaja = v.paradas >= 2 AND (v.sube <> v.baja) AND NOT v.alguna_sin_proyectar
                                   AND v.distancia_max <= :distancia
                FROM viajes v WHERE v.trip_id = t.trip_id
                """.formatted(CARGA))
                .param("tolerancia", toleranciaRetrocesoM)
                .param("distancia", distanciaMaximaM)
                .update();
    }

    /** Filas de cada tabla en un esquema. */
    public Map<String, Long> contar(String esquema) {
        Map<String, Long> filas = new LinkedHashMap<>();
        for (String tabla : TABLAS) {
            filas.put(tabla, jdbc.sql("SELECT count(*) FROM " + esquema + "." + tabla).query(Long.class).single());
        }
        return filas;
    }

    /**
     * Cifras de calidad del horario cargado: viajes de tren y de autobús; de los de tren, cuántos tienen su recorrido
     * en shapes.txt, cuántos encajan en él, cuántos no avanzan en orden y cuántos tienen paradas lejos; y horas de
     * paso cuyo viaje o parada no existe (no hay claves foráneas: se cuentan).
     */
    public Map<String, Long> calidad(String esquema) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE NOT es_autobus) AS viajes_tren,
                       count(*) FILTER (WHERE es_autobus) AS viajes_autobus,
                       count(*) FILTER (WHERE NOT es_autobus AND f.shape_id IS NOT NULL) AS viajes_tren_con_recorrido,
                       count(*) FILTER (WHERE NOT es_autobus AND f.shape_id IS NULL) AS viajes_tren_sin_recorrido,
                       count(*) FILTER (WHERE NOT es_autobus AND forma_encaja) AS viajes_tren_encajan,
                       count(*) FILTER (WHERE NOT es_autobus AND coalesce(p.n, 0) < 2) AS viajes_tren_menos_de_2_paradas,
                       count(*) FILTER (WHERE NOT es_autobus AND forma_sentido IS NULL AND f.shape_id IS NOT NULL
                                        AND p.n >= 2) AS viajes_tren_sin_orden,
                       count(*) FILTER (WHERE NOT es_autobus AND forma_sentido IS NOT NULL AND NOT forma_encaja)
                           AS viajes_tren_lejos_del_recorrido,
                       (SELECT count(*) FROM %1$s.stop_times st
                        WHERE NOT EXISTS (SELECT 1 FROM %1$s.trips x WHERE x.trip_id = st.trip_id))
                           AS horas_de_paso_sin_viaje,
                       (SELECT count(*) FROM %1$s.stop_times st
                        WHERE NOT EXISTS (SELECT 1 FROM %1$s.stops x WHERE x.stop_id = st.stop_id))
                           AS horas_de_paso_sin_parada
                FROM %1$s.trips t
                LEFT JOIN %1$s.shapes f ON f.shape_id = t.shape_id
                LEFT JOIN (SELECT trip_id, count(*) AS n FROM %1$s.stop_times GROUP BY trip_id) p ON p.trip_id = t.trip_id
                """.formatted(esquema)).query((rs, n) -> {
                    Map<String, Long> m = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        m.put(rs.getMetaData().getColumnLabel(i), rs.getLong(i));
                    }
                    return m;
                }).single();
    }

    /** Primer y último día que cubre el calendario cargado. */
    public LocalDate[] vigencia(String esquema) {
        return jdbc.sql("SELECT min(start_date), max(end_date) FROM " + esquema + ".calendar")
                .query((rs, n) -> new LocalDate[] { rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class) })
                .single();
    }

    /**
     * El cambio de golpe: el horario cargado pasa a ser el vivo y el vivo pasa a {@value #ANTERIOR}. Son dos
     * cambios de nombre en el catálogo, sin copiar datos, dentro de la transacción del paso que lo llama: o se ven
     * los dos o ninguno. Las consultas que estén en marcha terminan con las tablas viejas (PostgreSQL las identifica
     * por OID, no por nombre); las nuevas ya resuelven {@code horario.x} al horario nuevo.
     */
    public void cambiar() {
        jdbc.sql("SET LOCAL lock_timeout = '30s'").update();
        jdbc.sql("DROP SCHEMA IF EXISTS " + ANTERIOR + " CASCADE").update();
        jdbc.sql("ALTER SCHEMA " + VIVO + " RENAME TO " + ANTERIOR).update();
        jdbc.sql("ALTER SCHEMA " + CARGA + " RENAME TO " + VIVO).update();
    }

    /** Borra un esquema auxiliar (el anterior tras un cambio, o el de carga tras un fallo). Nunca el vivo. */
    public void borrar(String esquema) {
        if (VIVO.equals(esquema)) {
            throw new IllegalArgumentException("No se borra el horario vivo");
        }
        jdbc.sql("DROP SCHEMA IF EXISTS " + esquema + " CASCADE").update();
    }

    public boolean existe(String esquema) {
        return jdbc.sql("SELECT to_regnamespace(:e) IS NOT NULL").param("e", esquema).query(Boolean.class).single();
    }

    /** Si el horario vivo no tiene ningún viaje (base de datos recién creada). */
    public boolean vivoVacio() {
        return !jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + VIVO + ".trips)").query(Boolean.class).single();
    }

    private List<String> tablasDe(String esquema) {
        return jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = :e ORDER BY tablename")
                .param("e", esquema).query(String.class).list();
    }
}
