package com.ivanchesa.trainwatch.horario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.ivanchesa.trainwatch.TestcontainersConfiguration;
import com.ivanchesa.trainwatch.horario.ImportadorHorario.Informe;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Resultado;

/**
 * Aceptación de la fase 2 con PostGIS en Testcontainers y un GTFS pequeño ({@code src/test/resources/gtfs/ejemplo}):
 * importación completa, idempotencia, descarga condicional, fallos que no tocan el horario anterior, proyección de
 * las paradas sobre el recorrido y los endpoints.
 *
 * <p>El ejemplo: 5 paradas (A Lleida Pirineus, B y C sobre el recorrido {@code 51_RL4}, D y una a 40 km), 3 líneas
 * (la tercera, un autobús), 2 recorridos ({@code 51_RL4}, A-B-C, y {@code 51_CIRC}, circular A-B-C-D-A), 7 viajes
 * (T1 A→B→C; T2 C→B→A, que acaba a las 24:10; T3 en autobús; T4 A→C→B, desordenado; T5 A→parada lejana; T6 C→D→A→B
 * por el circular, pasando por el final y el principio del recorrido; T7, de una sola parada) y 18 horas de paso.
 * Todos los campos van rellenos de espacios, como en Renfe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class HorarioIntegracionTests {

    static final String TOKEN = "token-de-prueba";
    static final ServidorGtfs RENFE = new ServidorGtfs();
    static final AtomicInteger VERSION = new AtomicInteger();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("trainwatch.horario.url", () -> RENFE.url().toString());
        registro.add("trainwatch.horario.token-admin", () -> TOKEN);
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    ImportadorHorario importador;

    @Autowired
    JdbcClient jdbc;

    @Value("${local.server.port}")
    int puerto;

    RestTestClient cliente;

    @BeforeEach
    void horarioDeEjemplo() {
        cliente = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        // Cada prueba parte del ejemplo importado con un ETag nuevo.
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), etagNuevo());
        assertThat(importador.importar(Motivo.MANUAL, false).resultado()).isEqualTo(Resultado.CORRECTA);
    }

    static String etagNuevo() {
        return "\"v" + VERSION.incrementAndGet() + "\"";
    }

    @Test
    void importaTodosLosFicherosConSusFilas() {
        assertThat(filas()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "stops", 5L, "routes", 3L, "calendar", 2L, "trips", 7L, "stop_times", 18L, "shapes", 2L,
                "parada_forma", 8L, "transfers", 1L));
    }

    @Test
    void recortaLosEspaciosDeRelleno() {
        assertThat(jdbc.sql("SELECT route_id || '|' || route_short_name || '|' || route_long_name FROM horario.routes"
                + " WHERE route_id = '51T0001RL4'").query(String.class).single())
                .isEqualTo("51T0001RL4|RL4|Lleida Pirineus");
        assertThat(jdbc.sql("SELECT count(*) FROM horario.stop_times WHERE stop_id = '78805' AND trip_id = 'T1RL4'"
                + " AND stop_sequence = 1").query(Integer.class).single()).isEqualTo(1);
        // Un campo solo con espacios es NULL, no una cadena vacía.
        assertThat(jdbc.sql("SELECT count(*) FROM horario.trips WHERE trip_headsign IS NULL").query(Integer.class)
                .single()).isEqualTo(7);
    }

    @Test
    void convierteHorasFechasNucleoYUbicacion() {
        // 24:10:00 = 87.000 s: el viaje acaba pasada la medianoche del día de servicio.
        assertThat(jdbc.sql("SELECT llegada_s FROM horario.stop_times WHERE trip_id = 'T2RL4' AND stop_sequence = 3")
                .query(Integer.class).single()).isEqualTo(87_000);
        assertThat(jdbc.sql("SELECT salida_s - llegada_s FROM horario.stop_times WHERE trip_id = 'T1RL4'"
                + " AND stop_sequence = 2").query(Integer.class).single()).isEqualTo(60);
        assertThat(jdbc.sql("SELECT DISTINCT nucleo FROM horario.routes").query(Integer.class).list())
                .containsExactly(51);
        assertThat(jdbc.sql("SELECT end_date::text FROM horario.calendar WHERE service_id = 'S1'")
                .query(String.class).single()).isEqualTo("2026-12-19");
        assertThat(jdbc.sql("SELECT ST_SRID(ubicacion) || ' ' || ST_AsText(ubicacion) FROM horario.stops"
                + " WHERE stop_id = '78805'").query(String.class).single()).isEqualTo("4326 POINT(0.6335 41.6206)");
    }

    @Test
    void marcaLosAutobusesDeSustitucion() {
        assertThat(jdbc.sql("SELECT trip_id FROM horario.trips WHERE es_autobus").query(String.class).list())
                .containsExactly("T3BUS");
        assertThat(jdbc.sql("SELECT route_id FROM horario.routes WHERE es_autobus").query(String.class).list())
                .containsExactly("51T0003RL4");
    }

    @Test
    void construyeElRecorridoComoLineString() {
        var r = jdbc.sql("SELECT GeometryType(recorrido) AS tipo, ST_SRID(recorrido) AS srid, puntos, longitud_m, cerrado"
                + " FROM horario.shapes WHERE shape_id = '51_RL4'").query().singleRow();
        assertThat(r).containsEntry("tipo", "LINESTRING").containsEntry("srid", 4326).containsEntry("puntos", 3)
                .containsEntry("cerrado", false);
        // Lleida-Parada C, en línea recta por B: unos 53 km.
        assertThat((Double) r.get("longitud_m")).isBetween(50_000.0, 56_000.0);
        assertThat(jdbc.sql("SELECT cerrado FROM horario.shapes WHERE shape_id = '51_CIRC'").query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void proyectaLasParadasSobreElRecorrido() {
        Map<String, Double> posicion = jdbc.sql("SELECT stop_id, posicion FROM horario.parada_forma"
                + " WHERE shape_id = '51_RL4'").query().listOfRows().stream()
                .collect(java.util.stream.Collectors.toMap(f -> (String) f.get("stop_id"), f -> (Double) f.get("posicion")));
        assertThat(posicion.get("78805")).isCloseTo(0.0, within(1e-6));
        assertThat(posicion.get("78950")).isCloseTo(1.0, within(1e-6));
        assertThat(posicion.get("78900")).isBetween(0.3, 0.6);

        record Encaje(String trip, Integer sentido, boolean encaja) {
        }
        List<Encaje> encajes = jdbc.sql("SELECT trip_id, forma_sentido, forma_encaja FROM horario.trips ORDER BY trip_id")
                .query((rs, n) -> new Encaje(rs.getString(1), (Integer) rs.getObject(2), rs.getBoolean(3))).list();
        assertThat(encajes).containsExactly(
                new Encaje("T1RL4", 1, true),     // A→B→C: avanza en el sentido del recorrido
                new Encaje("T2RL4", -1, true),    // C→B→A: al revés, pero en orden
                new Encaje("T3BUS", null, false), // autobús sin recorrido
                new Encaje("T4RL4", null, false), // A→C→B: no avanza siempre en el mismo sentido
                new Encaje("T5RL4", 1, false),    // en orden, pero la parada lejana está a 40 km del recorrido
                new Encaje("T6CIR", 1, true),     // circular: de D (0,7) a A (0,0) es avanzar, dando la vuelta
                new Encaje("T7UNA", null, false)); // una sola parada: no hay orden que comprobar
        assertThat(jdbc.sql("SELECT forma_distancia_max_m FROM horario.trips WHERE trip_id = 'T5RL4'")
                .query(Double.class).single()).isGreaterThan(30_000.0);
    }

    @Test
    void repetirLaImportacionNoDuplicaNada() {
        var antes = filas();
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), etagNuevo());
        assertThat(importador.importar(Motivo.MANUAL, false).resultado()).isEqualTo(Resultado.CORRECTA);
        assertThat(importador.importar(Motivo.MANUAL, true).resultado()).isEqualTo(Resultado.CORRECTA);

        assertThat(filas()).isEqualTo(antes);
        assertThat(esquemasDelHorario()).containsExactly("horario");
        // Los índices de la migración siguen ahí después de dos cambios de esquema.
        assertThat(jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'horario' ORDER BY indexname")
                .query(String.class).list()).containsExactly("calendar_pkey", "parada_forma_pkey", "routes_nucleo_idx",
                "routes_pkey", "shapes_pkey", "shapes_recorrido_idx", "stop_times_pkey", "stop_times_salidas_idx",
                "stops_pkey", "stops_ubicacion_geog_idx", "trips_pkey", "trips_route_idx", "trips_service_idx");
    }

    @Test
    void siElFicheroNoHaCambiadoNoSeImporta() {
        int peticiones = RENFE.ifNoneMatchRecibidos.size();
        long importacionesCorrectas = correctas();

        Informe informe = importador.importar(Motivo.NOCTURNA, false);

        assertThat(informe.resultado()).isEqualTo(Resultado.SIN_CAMBIOS);
        assertThat(RENFE.ifNoneMatchRecibidos).hasSize(peticiones + 1);
        // Mandó el ETag de la última importación correcta, y el servidor contestó 304.
        assertThat(RENFE.ifNoneMatchRecibidos.getLast()).isEqualTo("\"v" + VERSION.get() + "\"");
        assertThat(correctas()).isEqualTo(importacionesCorrectas);
    }

    @Test
    void unFalloAMediaImportacionDejaElHorarioAnterior() {
        var antes = filas();
        // Una hora imposible en la última línea de stop_times: falla en el paso de carga de stop_times, con
        // stops, routes, calendar y trips ya cargados en el esquema de carga.
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(Map.of("stop_times.txt",
                s -> s + "T5RL4,12:7X:00,12:70:00,78950,003\n")), etagNuevo());

        Informe informe = importador.importar(Motivo.NOCTURNA, false);

        assertThat(informe.resultado()).isEqualTo(Resultado.FALLIDA);
        assertThat(informe.error()).contains("stop_times.txt").contains("no es una hora");
        assertThat(filas()).isEqualTo(antes);
        assertThat(esquemasDelHorario()).containsExactly("horario");

        cliente.get().uri("/api/horario/estado").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ultimoIntento.resultado").isEqualTo("FALLIDA")
                .jsonPath("$.ultimoIntento.error").value(String.class, e -> assertThat(e).contains("no es una hora"))
                .jsonPath("$.horario.filas.stop_times").isEqualTo(18);

        // El siguiente intento con el mismo fichero vuelve a probar: el fallido no cuenta como importado.
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), "\"v" + VERSION.get() + "\"");
        assertThat(importador.importar(Motivo.NOCTURNA, false).resultado()).isEqualTo(Resultado.CORRECTA);
    }

    @Test
    void rechazaUnHorarioMuchoMasPequenoQueElActual() {
        var antes = filas();
        // Solo un viaje de siete: menos de la mitad, como si Renfe publicara un fichero cortado.
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(Map.of(
                "trips.txt", s -> s.lines().limit(2).reduce((a, b) -> a + "\n" + b).orElseThrow() + "\n")), etagNuevo());

        Informe informe = importador.importar(Motivo.NOCTURNA, false);

        assertThat(informe.resultado()).isEqualTo(Resultado.FALLIDA);
        assertThat(informe.error()).contains("menos del 50 %");
        assertThat(filas()).isEqualTo(antes);
    }

    @Test
    void unZipSinUnFicheroObligatorioFalla() {
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(Map.of("stops.txt", s -> null)), etagNuevo());

        Informe informe = importador.importar(Motivo.NOCTURNA, false);

        assertThat(informe.resultado()).isEqualTo(Resultado.FALLIDA);
        assertThat(informe.error()).contains("El ZIP no trae stops.txt");
        assertThat(filas().get("stops")).isEqualTo(5L);
    }

    @Test
    void elEstadoCuentaElHorarioEnUso() {
        cliente.get().uri("/api/horario/estado").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.horario.publicadoPorRenfe").isEqualTo(ServidorGtfs.LAST_MODIFIED)
                .jsonPath("$.horario.vigenciaDesde").isEqualTo("2026-11-20")
                .jsonPath("$.horario.vigenciaHasta").isEqualTo("2026-12-19")
                .jsonPath("$.horario.filas.trips").isEqualTo(7)
                .jsonPath("$.horario.filas.viajes_tren_encajan").isEqualTo(3)
                .jsonPath("$.horario.filas.viajes_tren_menos_de_2_paradas").isEqualTo(1)
                .jsonPath("$.horario.filas.viajes_autobus").isEqualTo(1)
                .jsonPath("$.horario.filas.viajes_tren_con_recorrido").isEqualTo(6)
                .jsonPath("$.horario.filas.viajes_tren_sin_orden").isEqualTo(1)
                .jsonPath("$.horario.filas.viajes_tren_lejos_del_recorrido").isEqualTo(1)
                .jsonPath("$.ultimoIntento.resultado").isEqualTo("CORRECTA")
                .jsonPath("$.ultimoIntento.duracionMs").isNotEmpty()
                .jsonPath("$.importacionEnCurso").isEqualTo(false);
    }

    @Test
    void elDisparoManualPideElToken() throws InterruptedException {
        long intentos = jdbc.sql("SELECT count(*) FROM importacion_horario").query(Long.class).single();
        cliente.post().uri("/api/horario/importaciones").exchange().expectStatus().isUnauthorized();
        cliente.post().uri("/api/horario/importaciones").header(HorarioController.CABECERA_TOKEN, "otro")
                .exchange().expectStatus().isUnauthorized();
        cliente.post().uri("/api/horario/importaciones").header(HorarioController.CABECERA_TOKEN, TOKEN)
                .exchange().expectStatus().isAccepted();
        // Espera a que termine la importación lanzada en segundo plano, para no pisar a la siguiente prueba.
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite && (importador.enCurso()
                || jdbc.sql("SELECT count(*) FROM importacion_horario").query(Long.class).single() == intentos)) {
            Thread.sleep(50);
        }
        assertThat(ultimoResultado()).isEqualTo("SIN_CAMBIOS");
    }

    private Map<String, Long> filas() {
        Map<String, Long> filas = new java.util.LinkedHashMap<>();
        for (String tabla : EsquemaCarga.TABLAS) {
            filas.put(tabla, jdbc.sql("SELECT count(*) FROM horario." + tabla).query(Long.class).single());
        }
        return filas;
    }

    private List<String> esquemasDelHorario() {
        return jdbc.sql("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'horario%' ORDER BY nspname")
                .query(String.class).list();
    }

    private long correctas() {
        return jdbc.sql("SELECT count(*) FROM importacion_horario WHERE resultado = 'CORRECTA'").query(Long.class)
                .single();
    }

    private String ultimoResultado() {
        return jdbc.sql("SELECT resultado FROM importacion_horario ORDER BY id DESC LIMIT 1").query(String.class)
                .single();
    }
}
