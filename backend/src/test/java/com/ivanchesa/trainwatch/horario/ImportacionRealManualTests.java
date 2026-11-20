package com.ivanchesa.trainwatch.horario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ivanchesa.trainwatch.TestcontainersConfiguration;
import com.ivanchesa.trainwatch.horario.ImportadorHorario.Informe;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Resultado;

/**
 * Prueba manual con el ZIP real de Renfe (no corre en CI). Se descarga aparte y se pasa su ruta:
 *
 * <pre>
 * curl -o ../datos/gtfs/fomento_transit.zip https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip
 * ./mvnw test -Dtest=ImportacionRealManualTests -Dhorario.zip=../datos/gtfs/fomento_transit.zip
 * </pre>
 *
 * Importa dos veces (la segunda, sobre un horario ya cargado, mide el cambio con datos dentro) y escribe las cifras
 * en {@code target/importacion-real.txt}: duración, filas por tabla, encaje de los viajes en su recorrido por núcleo
 * y los planes de las tres consultas para las que están pensados los índices.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "horario.zip", matches = ".+")
class ImportacionRealManualTests {

    static final ServidorGtfs RENFE = new ServidorGtfs();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("trainwatch.horario.url", () -> RENFE.url().toString());
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    ImportadorHorario importador;

    @Autowired
    JdbcClient jdbc;

    @Test
    void importaElZipReal() throws IOException {
        byte[] zip = Files.readAllBytes(Path.of(System.getProperty("horario.zip")));
        List<String> salida = new ArrayList<>();
        salida.add("ZIP: " + zip.length + " bytes");

        RENFE.sirve(zip, "\"real-1\"");
        Informe primera = importador.importar(Motivo.MANUAL, false);
        salida.add("Primera importación (base vacía): " + primera.resultado() + " en " + primera.duracion().toMillis()
                + " ms" + (primera.error() == null ? "" : " · " + primera.error()));
        assertThat(primera.resultado()).isEqualTo(Resultado.CORRECTA);

        RENFE.sirve(zip, "\"real-2\"");
        Informe segunda = importador.importar(Motivo.MANUAL, false);
        salida.add("Segunda importación (con horario cargado): " + segunda.resultado() + " en "
                + segunda.duracion().toMillis() + " ms");
        Informe tercera = importador.importar(Motivo.NOCTURNA, false);
        salida.add("Tercera (mismo ETag): " + tercera.resultado() + " en " + tercera.duracion().toMillis() + " ms");

        salida.add("Filas y calidad: " + segunda.filas());
        salida.add("Tamaño del esquema horario (tablas e índices): " + jdbc.sql("""
                SELECT pg_size_pretty(sum(pg_total_relation_size(c.oid)))
                FROM pg_class c WHERE c.relnamespace = 'horario'::regnamespace AND c.relkind = 'r'
                """).query(String.class).single());

        salida.add("Encaje de los viajes de tren en su recorrido, por núcleo:");
        jdbc.sql("""
                SELECT r.nucleo, count(*) AS viajes,
                       count(*) FILTER (WHERE t.shape_id IS NOT NULL) AS con_recorrido,
                       count(*) FILTER (WHERE t.forma_encaja) AS encajan,
                       count(*) FILTER (WHERE t.forma_sentido IS NULL AND t.shape_id IS NOT NULL) AS sin_orden,
                       count(*) FILTER (WHERE t.forma_sentido IS NOT NULL AND NOT t.forma_encaja) AS lejos,
                       round(100.0 * count(*) FILTER (WHERE t.forma_encaja) / count(*), 1) AS pct
                FROM horario.trips t JOIN horario.routes r ON r.route_id = t.route_id
                WHERE NOT t.es_autobus GROUP BY r.nucleo ORDER BY r.nucleo
                """).query().listOfRows().forEach(f -> salida.add("  " + f));

        salida.add("Recorridos con viajes que no encajan (los 15 con más):");
        jdbc.sql("""
                SELECT t.shape_id, count(*) AS no_encajan,
                       count(*) FILTER (WHERE t.forma_sentido IS NULL) AS sin_orden,
                       round(max(t.forma_distancia_max_m)) AS dist_max_m
                FROM horario.trips t WHERE NOT t.es_autobus AND NOT t.forma_encaja AND t.shape_id IS NOT NULL
                GROUP BY t.shape_id ORDER BY 2 DESC LIMIT 15
                """).query().listOfRows().forEach(f -> salida.add("  " + f));

        salida.add("Un viaje de ejemplo de cada recorrido que no encaja (parada: posición · metros al recorrido):");
        jdbc.sql("""
                SELECT DISTINCT ON (t.shape_id) t.shape_id, t.trip_id,
                       (SELECT string_agg(st.stop_id || ': ' || coalesce(round(pf.posicion::numeric, 3)::text, '-')
                                          || ' · ' || coalesce(round(pf.distancia_m)::text, '-'), ' | '
                                          ORDER BY st.stop_sequence)
                        FROM horario.stop_times st
                        LEFT JOIN horario.parada_forma pf ON pf.shape_id = t.shape_id AND pf.stop_id = st.stop_id
                        WHERE st.trip_id = t.trip_id) AS paradas
                FROM horario.trips t
                WHERE NOT t.es_autobus AND NOT t.forma_encaja AND t.shape_id IN
                      ('40_C6', '30_C4', '20_C2', '51_R16_INV', '51_R11', '41_C3', '51_R2', '10_C2', '51_R4')
                ORDER BY t.shape_id, t.trip_id
                """).query().listOfRows().forEach(f -> salida.add("  " + f));

        salida.add("Distancia de las paradas a su recorrido (parada_forma): " + jdbc.sql("""
                SELECT jsonb_build_object('p50', percentile_cont(0.5) WITHIN GROUP (ORDER BY distancia_m),
                                          'p90', percentile_cont(0.9) WITHIN GROUP (ORDER BY distancia_m),
                                          'p99', percentile_cont(0.99) WITHIN GROUP (ORDER BY distancia_m),
                                          'mas_de_200', count(*) FILTER (WHERE distancia_m > 200))::text
                FROM horario.parada_forma
                """).query(String.class).single());

        Map<String, String> consultas = Map.of(
                "Próximas salidas de Lleida Pirineus (78805) entre las 08:00 y las 10:00", """
                        SELECT st.trip_id, st.salida_s FROM horario.stop_times st
                        WHERE st.stop_id = '78805' AND st.salida_s BETWEEN 28800 AND 36000 ORDER BY st.salida_s""",
                "Paradas de un viaje en orden", """
                        SELECT * FROM horario.stop_times
                        WHERE trip_id = (SELECT trip_id FROM horario.trips LIMIT 1) ORDER BY stop_sequence""",
                "Las 5 estaciones más cercanas a la plaza de Catalunya", """
                        SELECT stop_id, stop_name FROM horario.stops
                        ORDER BY ubicacion::geography <-> ST_SetSRID(ST_MakePoint(2.1700, 41.3870), 4326)::geography
                        LIMIT 5""");
        consultas.forEach((titulo, sql) -> {
            salida.add(titulo + ":");
            jdbc.sql("EXPLAIN (ANALYZE, COSTS OFF) " + sql).query(String.class).list()
                    .forEach(l -> salida.add("    " + l));
        });

        Path informe = Path.of("target/importacion-real.txt");
        Files.write(informe, salida);
        salida.forEach(System.out::println);
    }
}
