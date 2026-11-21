package com.ivanchesa.trainwatch.estadisticas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.ivanchesa.trainwatch.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aceptación de la fase 6 con PostGIS en Testcontainers: un puñado de filas de {@code retrasos} escritas a mano, cuyos
 * resultados se calculan a mano en los comentarios, y la API que los lee.
 *
 * <p>Núcleo 51, líneas RL4 y R3; estaciones A, B, C y D ({@code 90001}…{@code 90004}). En el horario, las cuatro con
 * {@code stop_sequence} 1, 2, <b>4</b> y 5 (Renfe tiene huecos así). Dos días con datos:
 *
 * <pre>
 * 23-10-2026 (viernes, CEST = UTC+2)   servicio S23
 *   RL4 T1  A 08:00 0 · B 08:20 2 · C 08:40 5 · D 09:05 6
 *   RL4 T2  A 08:30 4 · B 08:50 4 INFERIDA · C 09:10 10 · D 09:30 -1 (adelanto)
 *   RL4 T3  A 23:40 20 · B 00:10 (del 24) 18 · C 00:30 3 SIN HORA
 *   RL4 T4  programado, no se ve · T6 programado y CANCELADO (solo en posiciones)
 *   RL4 T12 CANCELADO a las 01:30 del 24 (en la partición del 24), sin horario
 *   R3  T7  A 10:00 1 · C 10:20 1 (B no se cerró: A→C no es un tramo)
 *   R3  T8  A 10:00 0 · B 10:10 0
 *   BUS T5  autobús (route_type 3) A 08:10 30: no entra en nada
 * 25-10-2026 (domingo, cambio de hora: 03:00 CEST → 02:00 CET)   servicio S25
 *   RL4 T9  A 00:30Z (02:30 CEST) 1 · B 01:30Z (02:30 CET) 3 · C 02:30Z (03:30 CET) 4
 *   RL4 T10 A 08:00Z (09:00 CET) 0
 * </pre>
 *
 * <p>El destino de todos los viajes es D (secuencia 5): solo T1 y T2 se ven llegar a él.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "trainwatch.estadisticas.muestra-minima=2",
        "trainwatch.estadisticas.publicar-desde=2026-10-26",
        "trainwatch.estadisticas.espera-cerrojo=1s" })
@Import({ TestcontainersConfiguration.class, EstadisticasIntegracionTests.Reloj.class })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EstadisticasIntegracionTests {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate D1 = LocalDate.of(2026, 10, 23);
    static final LocalDate D2 = LocalDate.of(2026, 10, 25);
    static final String PERIODO = "?desde=2026-10-23&hasta=2026-10-25";

    /** Un reloj que la prueba mueve a mano: empieza el lunes 26-10-2026 a las 10:00 de Madrid. */
    static class RelojMovil extends Clock {
        volatile Instant ahora = Instant.parse("2026-10-26T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return MADRID;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Reloj {
        @Bean
        @Primary
        RelojMovil relojMovil() {
            return new RelojMovil();
        }
    }

    @Autowired
    CalculoEstadisticas calculo;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    RelojMovil reloj;
    @Autowired
    JsonMapper json;
    @LocalServerPort
    int puerto;

    @BeforeAll
    void datos() {
        particionDiaria(D1);
        particionDiaria(D1.plusDays(1));

        particionDiaria(D2);
        particionDiaria(D2.plusDays(1));
        jdbc.sql("CREATE TABLE IF NOT EXISTS retrasos_p202610 PARTITION OF retrasos"
                + " FOR VALUES FROM ('2026-10-01') TO ('2026-11-01')").update();

        jdbc.sql("""
                INSERT INTO horario.stops (stop_id, stop_name, stop_lat, stop_lon) VALUES
                    ('90001', 'Lleida-Pirineus', 41.62, 0.63), ('90002', 'Bell-lloc d''Urgell', 41.63, 0.78),
                    ('90003', 'Mollerussa', 41.63, 0.89), ('90004', 'Tàrrega', 41.65, 1.14)""").update();
        jdbc.sql("""
                INSERT INTO horario.routes (route_id, route_short_name, route_type, nucleo) VALUES
                    ('51T0001RL4', 'RL4', 2, 51), ('51T0001R3', 'R3', 2, 51), ('51BUS', 'BUS1', 3, 51)""").update();
        jdbc.sql("""
                INSERT INTO horario.calendar VALUES
                    ('S23', false, false, false, false, true, false, false, '2026-10-23', '2026-10-23'),
                    ('S25', false, false, false, false, false, false, true, '2026-10-25', '2026-10-25')""").update();
        jdbc.sql("""
                INSERT INTO horario.trips (trip_id, route_id, service_id, es_autobus) VALUES
                    ('T1', '51T0001RL4', 'S23', false), ('T2', '51T0001RL4', 'S23', false),
                    ('T3', '51T0001RL4', 'S23', false), ('T4', '51T0001RL4', 'S23', false),
                    ('T6', '51T0001RL4', 'S23', false), ('T7', '51T0001R3', 'S23', false),
                    ('T8', '51T0001R3', 'S23', false), ('T5', '51BUS', 'S23', true),
                    ('T9', '51T0001RL4', 'S25', false), ('T10', '51T0001RL4', 'S25', false)""").update();
        for (String trip : List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10")) {
            jdbc.sql("""
                    INSERT INTO horario.stop_times VALUES
                        (:t, 1, '90001', 0, 0), (:t, 2, '90002', 600, 600), (:t, 4, '90003', 1200, 1200),
                        (:t, 5, '90004', 1800, 1800)""").param("t", trip).update();
        }

        // 23-10 (CEST, UTC+2): las horas locales menos 2.
        fila(D1, "T1", 1, "90001", "RL4", 0, false, "2026-10-23T06:00:00Z");
        fila(D1, "T1", 2, "90002", "RL4", 2, false, "2026-10-23T06:20:00Z");
        fila(D1, "T1", 4, "90003", "RL4", 5, false, "2026-10-23T06:40:00Z");
        fila(D1, "T1", 5, "90004", "RL4", 6, false, "2026-10-23T07:05:00Z");
        fila(D1, "T2", 1, "90001", "RL4", 4, false, "2026-10-23T06:30:00Z");
        fila(D1, "T2", 2, "90002", "RL4", 4, true, "2026-10-23T06:50:00Z");
        fila(D1, "T2", 4, "90003", "RL4", 10, false, "2026-10-23T07:10:00Z");
        fila(D1, "T2", 5, "90004", "RL4", -1, false, "2026-10-23T07:30:00Z");
        fila(D1, "T3", 1, "90001", "RL4", 20, false, "2026-10-23T21:40:00Z");
        fila(D1, "T3", 2, "90002", "RL4", 18, false, "2026-10-23T22:10:00Z");
        fila(D1, "T3", 4, "90003", "RL4", 3, false, null);
        fila(D1, "T7", 1, "90001", "R3", 1, false, "2026-10-23T08:00:00Z");
        fila(D1, "T7", 4, "90003", "R3", 1, false, "2026-10-23T08:20:00Z");
        fila(D1, "T8", 1, "90001", "R3", 0, false, "2026-10-23T08:00:00Z");
        fila(D1, "T8", 2, "90002", "R3", 0, false, "2026-10-23T08:10:00Z");
        jdbc.sql("""
                INSERT INTO retrasos (fecha_servicio, trip_id, stop_sequence, stop_id, route_id, linea, nucleo, minutos,
                    registrado, inferido, llegada_programada)
                VALUES ('2026-10-23', 'T5', 1, '90001', '51BUS', 'BUS1', 51, 30, now(), false,
                    '2026-10-23T06:10:00Z')""").update();
        // 25-10: cambio de hora. Las 02:30 locales pasan dos veces.
        fila(D2, "T9", 1, "90001", "RL4", 1, false, "2026-10-25T00:30:00Z");
        fila(D2, "T9", 2, "90002", "RL4", 3, false, "2026-10-25T01:30:00Z");
        fila(D2, "T9", 4, "90003", "RL4", 4, false, "2026-10-25T02:30:00Z");
        fila(D2, "T10", 1, "90001", "RL4", 0, false, "2026-10-25T08:00:00Z");

        // T6, cancelado: solo está en posiciones. Y un cancelado de otro día en la misma partición, que no cuenta.
        jdbc.sql("""
                INSERT INTO posiciones (instante, trip_id, fecha_servicio, nucleo, linea, estado) VALUES
                    ('2026-10-23T05:00:00Z', 'T6', '2026-10-23', 51, 'RL4', 'CANCELADO'),
                    ('2026-10-23T05:10:00Z', 'T6', '2026-10-23', 51, 'RL4', 'CANCELADO'),
                    ('2026-10-23T05:00:00Z', 'T1', '2026-10-23', 51, 'RL4', 'EN_ORIGEN'),
                    ('2026-10-23T06:00:00Z', 'OTRO', '2026-10-22', 51, 'RL4', 'CANCELADO'),
                    ('2026-10-23T23:30:00Z', 'T12', '2026-10-23', 51, 'RL4', 'CANCELADO')""").update();
    }

    void fila(LocalDate fecha, String trip, int seq, String stop, String linea, int minutos, boolean inferido,
            String llegada) {
        jdbc.sql("""
                INSERT INTO retrasos (fecha_servicio, trip_id, stop_sequence, stop_id, route_id, linea, nucleo, minutos,
                    registrado, inferido, llegada_programada)
                VALUES (:f, :t, :s, :stop, :r, :l, 51, :m, now(), :i, CAST(:llegada AS timestamptz))""")
                .param("f", fecha).param("t", trip).param("s", seq).param("stop", stop)
                .param("r", "51T0001" + linea).param("l", linea).param("m", minutos).param("i", inferido)
                .param("llegada", llegada).update();
    }

    void particionDiaria(LocalDate dia) {
        jdbc.sql("CREATE TABLE IF NOT EXISTS posiciones_p%s PARTITION OF posiciones FOR VALUES FROM ('%s') TO ('%s')"
                .formatted(dia.toString().replace("-", ""), dia.atStartOfDay(MADRID).toOffsetDateTime(),
                        dia.plusDays(1).atStartOfDay(MADRID).toOffsetDateTime()))
                .update();
    }

    Map<String, Object> hora(LocalDate fecha, String linea, int hora) {
        return jdbc.sql("""
                SELECT viajes, paradas, suma_minutos, menos_de_3, de_3_a_5, de_6_a_15, de_16_o_mas, finales, destinos,
                       suma_destinos, destinos_menos_de_3, histograma::text AS histograma
                FROM estad_linea_hora WHERE fecha_servicio = :f AND linea = :l AND hora = :h""")
                .param("f", fecha).param("l", linea).param("h", hora).query().singleRow();
    }

    /** Las 61 posiciones del histograma, con las que se dan a 1 o más. */
    static String histograma(Map<Integer, Integer> posiciones) {
        int[] h = new int[61];
        posiciones.forEach((i, n) -> h[i] = n);
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i < h.length; i++) {
            s.append(i == 0 ? "" : ",").append(h[i]);
        }
        return s.append('}').toString();
    }

    /** Todo lo que hay en las tablas de agregados de un día, como texto, para comparar dos cálculos. */
    String volcado(LocalDate fecha) {
        StringBuilder s = new StringBuilder();
        for (String tabla : List.of("estad_linea_hora", "estad_linea_dia", "estad_estacion_dia", "estad_tramo_dia")) {
            s.append(tabla).append(jdbc.sql("SELECT t::text FROM " + tabla + " t WHERE fecha_servicio = :f ORDER BY 1")
                    .param("f", fecha).query(String.class).list()).append('\n');
        }
        s.append(jdbc.sql("SELECT filas_retrasos, con_horario, con_posiciones FROM estad_dias WHERE fecha_servicio = :f")
                .param("f", fecha).query().listOfRows());
        return s.toString();
    }

    // ---------------------------------------------------------------- cálculo

    @Test
    @Order(1)
    void porHoraEnHoraDeMadridConMediasPorcentajesEHistograma() {
        calculo.recalcular(D1, D2);

        // 8:00 → T1 A 0, B 2, C 5 y T2 A 4 (la B de T2 es inferida). Viajes 2, suma 11.
        Map<String, Object> h8 = hora(D1, "RL4", 8);
        assertThat(h8).containsEntry("viajes", 2).containsEntry("paradas", 4).containsEntry("suma_minutos", 11)
                .containsEntry("menos_de_3", 2).containsEntry("de_3_a_5", 2).containsEntry("de_6_a_15", 0)
                .containsEntry("de_16_o_mas", 0).containsEntry("finales", 0);
        assertThat(h8.get("histograma")).isEqualTo(histograma(Map.of(0, 1, 2, 1, 4, 1, 5, 1)));
        // 9:00 → T1 D 6, T2 C 10 y D -1 (el adelanto va a la posición 0 y a «menos de 3», y la suma lo lleva tal cual).
        // Las últimas paradas vistas de T1 (6) y T2 (-1) son de esta hora y son su destino (D): suma 5, una con menos
        // de 3.
        Map<String, Object> h9 = hora(D1, "RL4", 9);
        assertThat(h9).containsEntry("viajes", 2).containsEntry("paradas", 3).containsEntry("suma_minutos", 15)
                .containsEntry("menos_de_3", 1).containsEntry("de_6_a_15", 2).containsEntry("finales", 2)
                .containsEntry("destinos", 2).containsEntry("suma_destinos", 5).containsEntry("destinos_menos_de_3", 1);
        assertThat(h9.get("histograma")).isEqualTo(histograma(Map.of(0, 1, 6, 1, 10, 1)));
        // 08:00 en hora de Madrid son las 06:00 UTC: no hay nada en las franjas 6 y 7.
        assertThat(jdbc.sql("SELECT count(*) FROM estad_linea_hora WHERE fecha_servicio = :f AND hora IN (6, 7)")
                .param("f", D1).query(Integer.class).single()).isZero();
        // La UTC de 08:00 del 25-10 (horario de invierno) es la franja de las 9.
        assertThat(hora(D2, "RL4", 9)).containsEntry("paradas", 1).containsEntry("suma_minutos", 0);
    }

    @Test
    @Order(2)
    void elDiaDeServicioPasadaLaMedianocheYElCambioDeHora() {
        // T3: la B es a las 00:10 del 24, pero su día de servicio es el 23: franja 0 del 23, y es su última parada vista
        // (la C no tiene hora), pero no su destino.
        assertThat(hora(D1, "RL4", 0)).containsEntry("paradas", 1).containsEntry("suma_minutos", 18)
                .containsEntry("de_16_o_mas", 1).containsEntry("finales", 1).containsEntry("destinos", 0);
        assertThat(hora(D1, "RL4", 23)).containsEntry("paradas", 1).containsEntry("suma_minutos", 20);
        assertThat(jdbc.sql("SELECT count(*) FROM estad_linea_hora WHERE fecha_servicio = '2026-10-24'")
                .query(Integer.class).single()).isZero();
        // 25-10: las 02:30 de verano y las 02:30 de invierno son la misma franja (la de las 2), y las 03:30, la de las 3.
        assertThat(hora(D2, "RL4", 2)).containsEntry("viajes", 1).containsEntry("paradas", 2)
                .containsEntry("suma_minutos", 4);
        assertThat(hora(D2, "RL4", 3)).containsEntry("paradas", 1).containsEntry("suma_minutos", 4)
                .containsEntry("finales", 1).containsEntry("destinos", 0);
    }

    @Test
    @Order(3)
    void porLineaYDiaInferidasSinHoraCanceladasYCobertura() {
        Map<String, Object> rl4 = jdbc.sql("SELECT * FROM estad_linea_dia WHERE fecha_servicio = :f AND linea = 'RL4'")
                .param("f", D1).query().singleRow();
        // Viajes con alguna parada válida: T1, T2 y T3. Programados: T1, T2, T3, T4 y T6 (no el autobús), con 4
        // paradas cada uno. Cancelados: T6 y T12 (este, pasada la medianoche, en la partición del 24).
        assertThat(rl4).containsEntry("viajes", 3).containsEntry("viajes_con_datos", 3)
                .containsEntry("paradas_con_datos", 11).containsEntry("paradas_inferidas", 1)
                .containsEntry("paradas_sin_hora", 1).containsEntry("viajes_programados", 5)
                .containsEntry("paradas_programadas", 20).containsEntry("cancelados", 2);
        Map<String, Object> r3 = jdbc.sql("SELECT * FROM estad_linea_dia WHERE fecha_servicio = :f AND linea = 'R3'")
                .param("f", D1).query().singleRow();
        assertThat(r3).containsEntry("viajes", 2).containsEntry("viajes_programados", 2).containsEntry("cancelados", 0);
        // El autobús no está en ninguna tabla.
        for (String tabla : List.of("estad_linea_hora", "estad_linea_dia", "estad_estacion_dia", "estad_tramo_dia")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + tabla + " WHERE linea = 'BUS1'").query(Integer.class).single())
                    .as(tabla).isZero();
        }
        // La suma de las horas es el día: 9 paradas válidas el 23 (11 filas de RL4 menos la inferida y la sin hora).
        assertThat(jdbc.sql("SELECT sum(paradas) FROM estad_linea_hora WHERE fecha_servicio = :f AND linea = 'RL4'")
                .param("f", D1).query(Integer.class).single()).isEqualTo(9);
        Map<String, Object> dia = jdbc.sql("SELECT * FROM estad_dias WHERE fecha_servicio = :f").param("f", D1)
                .query().singleRow();
        assertThat(dia).containsEntry("filas_retrasos", 16).containsEntry("con_horario", true)
                .containsEntry("con_posiciones", true);
        // El 24 no tiene horario: se calcula vacío y lo dice.
        assertThat(jdbc.sql("SELECT filas_retrasos, con_horario FROM estad_dias WHERE fecha_servicio = '2026-10-24'")
                .query().singleRow()).containsEntry("filas_retrasos", 0).containsEntry("con_horario", false);
    }

    @Test
    @Order(4)
    void tramosConLagYElOrdenDelHorario() {
        List<Map<String, Object>> tramos = jdbc.sql("""
                SELECT linea, desde_stop_id, hasta_stop_id, pasos, suma_diferencia, pierden_2_o_mas, ganan_2_o_mas
                FROM estad_tramo_dia WHERE fecha_servicio = :f ORDER BY linea, desde_stop_id, hasta_stop_id""")
                .param("f", D1).query().listOfRows();
        assertThat(tramos).containsExactly(
                // R3: T8 A→B (0). T7 A→C no: en el horario, la anterior de C es B.
                Map.of("linea", "R3", "desde_stop_id", "90001", "hasta_stop_id", "90002", "pasos", 1,
                        "suma_diferencia", 0, "pierden_2_o_mas", 0, "ganan_2_o_mas", 0),
                // RL4 A→B: T1 +2 y T3 -2 (la B de T2 es inferida: no hay tramo).
                Map.of("linea", "RL4", "desde_stop_id", "90001", "hasta_stop_id", "90002", "pasos", 2,
                        "suma_diferencia", 0, "pierden_2_o_mas", 1, "ganan_2_o_mas", 1),
                // RL4 B→C (secuencia 2 → 4, seguidas en el horario): T1 +3.
                Map.of("linea", "RL4", "desde_stop_id", "90002", "hasta_stop_id", "90003", "pasos", 1,
                        "suma_diferencia", 3, "pierden_2_o_mas", 1, "ganan_2_o_mas", 0),
                // RL4 C→D: T1 +1 y T2 -11.
                Map.of("linea", "RL4", "desde_stop_id", "90003", "hasta_stop_id", "90004", "pasos", 2,
                        "suma_diferencia", -10, "pierden_2_o_mas", 0, "ganan_2_o_mas", 1));
    }

    @Test
    @Order(5)
    void porEstacionConSusInferidas() {
        Map<String, Object> a = jdbc.sql("""
                SELECT viajes, paradas, suma_minutos, menos_de_3, de_16_o_mas, paradas_inferidas FROM estad_estacion_dia
                WHERE fecha_servicio = :f AND stop_id = '90001' AND linea = 'RL4'""").param("f", D1).query().singleRow();
        assertThat(a).containsEntry("viajes", 3).containsEntry("paradas", 3).containsEntry("suma_minutos", 24)
                .containsEntry("menos_de_3", 1).containsEntry("de_16_o_mas", 1).containsEntry("paradas_inferidas", 0);
        // En B, la de T2 es inferida: no entra, pero se cuenta.
        Map<String, Object> b = jdbc.sql("""
                SELECT viajes, paradas, suma_minutos, paradas_inferidas FROM estad_estacion_dia
                WHERE fecha_servicio = :f AND stop_id = '90002' AND linea = 'RL4'""").param("f", D1).query().singleRow();
        assertThat(b).containsEntry("viajes", 2).containsEntry("paradas", 2).containsEntry("suma_minutos", 20)
                .containsEntry("paradas_inferidas", 1);
    }

    @Test
    @Order(6)
    void recalcularUnDiaDaLoMismo() {
        String antes = volcado(D1);
        calculo.calcular(D1);
        calculo.calcular(D1);
        assertThat(volcado(D1)).isEqualTo(antes);
        assertThat(jdbc.sql("SELECT count(*) FROM estad_dias WHERE fecha_servicio = :f").param("f", D1)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void unaFilaNuevaSustituyeElDiaYLoQueYaNoEstaSeConserva() throws Exception {
        // Sin el horario ni la partición de posiciones del 23, al recalcular se conservan programados, cancelados,
        // llegadas a destino y tramos.
        List<String> tramos = jdbc.sql("SELECT t::text FROM estad_tramo_dia t WHERE fecha_servicio = :f ORDER BY 1")
                .param("f", D1).query(String.class).list();
        jdbc.sql("UPDATE horario.calendar SET start_date = '2026-01-01', end_date = '2026-01-01' WHERE service_id = 'S23'")
                .update();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute("ALTER TABLE posiciones DETACH PARTITION posiciones_p20261023");
            st.execute("DROP TABLE posiciones_p20261023");
        }
        fila(D1, "T4", 1, "90001", "RL4", 7, false, "2026-10-23T09:00:00Z");
        CalculoEstadisticas.Resultado r = calculo.calcular(D1);
        assertThat(r.conHorario()).isFalse();
        assertThat(r.conPosiciones()).isFalse();
        Map<String, Object> rl4 = jdbc.sql("SELECT * FROM estad_linea_dia WHERE fecha_servicio = :f AND linea = 'RL4'")
                .param("f", D1).query().singleRow();
        assertThat(rl4).containsEntry("viajes", 4).containsEntry("viajes_programados", 5)
                .containsEntry("paradas_programadas", 20).containsEntry("cancelados", 2);
        assertThat(hora(D1, "RL4", 11)).containsEntry("paradas", 1).containsEntry("suma_minutos", 7);
        assertThat(hora(D1, "RL4", 9)).containsEntry("destinos", 2).containsEntry("suma_destinos", 5);
        assertThat(jdbc.sql("SELECT t::text FROM estad_tramo_dia t WHERE fecha_servicio = :f ORDER BY 1")
                .param("f", D1).query(String.class).list()).isEqualTo(tramos);
        // Se deja todo como estaba para las pruebas de la API.
        jdbc.sql("DELETE FROM retrasos WHERE trip_id = 'T4'").update();
        jdbc.sql("UPDATE horario.calendar SET start_date = '2026-10-23', end_date = '2026-10-23' WHERE service_id = 'S23'")
                .update();
        calculo.calcular(D1);
        assertThat(jdbc.sql("SELECT viajes FROM estad_linea_dia WHERE fecha_servicio = :f AND linea = 'RL4'")
                .param("f", D1).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    @Order(8)
    void laNocturnaCalculaAyerYRecuperaLosDiasQueFaltan() {
        jdbc.sql("DELETE FROM estad_dias WHERE fecha_servicio IN ('2026-10-24', '2026-10-25')").update();
        calculo.nocturna();
        assertThat(jdbc.sql("SELECT fecha_servicio FROM estad_dias ORDER BY 1").query(LocalDate.class).list())
                .containsExactly(D1, D1.plusDays(1), D2);
    }

    @Test
    @Order(9)
    void losCerrojosHacenFallarElCalculoEnSegundosSinDejarElDiaAMedias() throws Exception {
        String antes = volcado(D1);
        // Otro cálculo del mismo día (su cerrojo consultivo) y una importación del horario en marcha (el suyo): el
        // cálculo espera como mucho lock_timeout (1 s aquí) y falla, sin tocar nada.
        for (String cerrojo : List.of("SELECT pg_advisory_xact_lock(6, " + D1.toEpochDay() + ")",
                "SELECT pg_advisory_xact_lock(" + com.ivanchesa.trainwatch.horario.ImportadorHorario.CLAVE_CERROJO
                        + ")")) {
            try (Connection otro = dataSource.getConnection(); Statement st = otro.createStatement()) {
                otro.setAutoCommit(false);
                st.execute(cerrojo);
                long t0 = System.nanoTime();
                assertThatThrownBy(() -> calculo.calcular(D1)).as(cerrojo).hasStackTraceContaining("lock timeout");
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(10_000);
                otro.rollback();
            }
        }
        // Otro día sí se puede calcular mientras tanto: el cerrojo es por día.
        try (Connection otro = dataSource.getConnection(); Statement st = otro.createStatement()) {
            otro.setAutoCommit(false);
            st.execute("SELECT pg_advisory_xact_lock(6, " + D2.toEpochDay() + ")");
            calculo.calcular(D1);
            otro.rollback();
        }
        assertThat(volcado(D1)).isEqualTo(antes);
        // Algo que retiene una tabla de agregados: lo mismo.
        CountDownLatch cogido = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        CompletableFuture<Void> bloqueo = CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                c.setAutoCommit(false);
                st.execute("LOCK TABLE estad_tramo_dia IN ACCESS EXCLUSIVE MODE");
                cogido.countDown();
                soltar.await(30, TimeUnit.SECONDS);
                c.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(cogido.await(10, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> calculo.calcular(D1)).hasStackTraceContaining("lock timeout");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(10_000);
        soltar.countDown();
        bloqueo.get(10, TimeUnit.SECONDS);
        // El fallo no ha dejado el día a medias.
        assertThat(volcado(D1)).isEqualTo(antes);
    }

    // ---------------------------------------------------------------- API

    RestTestClient http() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    JsonNode get(String ruta) {
        String cuerpo = http().get().uri(ruta).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueMatches("Cache-Control", ".*max-age=3600.*")
                .expectHeader().exists("ETag")
                .expectBody(String.class).returnResult().getResponseBody();
        return json.readTree(cuerpo);
    }

    JsonNode problema(String ruta, int estado) {
        String cuerpo = http().get().uri(ruta).exchange().expectStatus().isEqualTo(estado)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode p = json.readTree(cuerpo);
        assertThat(p.get("title").asString()).isNotBlank();
        assertThat(p.get("detail").asString()).isNotBlank();
        return p;
    }

    @Test
    @Order(20)
    void rankingDelNucleo() {
        JsonNode n = get("/api/estadisticas/nucleos/51" + PERIODO);
        assertThat(n.get("nombre").asString()).isEqualTo("Rodalies de Catalunya");
        assertThat(n.at("/publicacion/publicado").asBoolean()).isTrue();
        assertThat(n.at("/publicacion/recogiendoDesde").asString()).isEqualTo("2026-10-23");
        // El 24 está calculado, pero sin ninguna fila: no cuenta como día con datos.
        assertThat(n.at("/periodo/diasConDatos").asInt()).isEqualTo(2);
        // Viajes: RL4 3 + 2 y R3 2. Paradas: RL4 9 + 4 y R3 4.
        assertThat(n.at("/muestra/viajes").asInt()).isEqualTo(7);
        assertThat(n.at("/muestra/paradas").asInt()).isEqualTo(17);
        assertThat(n.at("/muestra/paradasInferidasQuitadas").asInt()).isEqualTo(1);
        assertThat(n.at("/muestra/paradasSinHoraQuitadas").asInt()).isEqualTo(1);
        assertThat(n.at("/muestra/cancelados").asInt()).isEqualTo(2);
        // Cobertura: el 23, 7 programados (RL4 5, R3 2) y 5 vistos; el 25, 2 y 2. 7 de 9 = 77,8 %.
        assertThat(n.at("/cobertura/viajesProgramados").asInt()).isEqualTo(9);
        assertThat(n.at("/cobertura/viajesVistos").asInt()).isEqualTo(7);
        assertThat(n.at("/cobertura/porcentaje").asDouble()).isEqualTo(77.8);
        // Por paradas: programadas 20 + 8 (el 23) y 8 (el 25) = 36; con fila, 11 + 4 y 4 = 19 → 52,8 %.
        assertThat(n.at("/cobertura/paradasProgramadas").asInt()).isEqualTo(36);
        assertThat(n.at("/cobertura/paradasVistas").asInt()).isEqualTo(19);
        assertThat(n.at("/cobertura/porcentajeParadas").asDouble()).isEqualTo(52.8);
        // R3: 4 paradas con 1, 1, 0 y 0 → 100 % con menos de 3, media 0,5. RL4: 5 de 13 = 38,5 %.
        JsonNode lineas = n.get("lineas");
        assertThat(lineas).hasSize(2);
        assertThat(lineas.get(0).get("linea").asString()).isEqualTo("R3");
        assertThat(lineas.get(0).get("puesto").asInt()).isEqualTo(1);
        assertThat(lineas.get(0).at("/cifras/mediaMinutos").asDouble()).isEqualTo(0.5);
        assertThat(lineas.get(0).at("/cifras/porcentajes/menosDe3").asDouble()).isEqualTo(100.0);
        assertThat(lineas.get(1).get("linea").asString()).isEqualTo("RL4");
        assertThat(lineas.get(1).get("puesto").asInt()).isEqualTo(2);
        // La RL4 es una línea más (Iván, 21-11): no hay «destacada».
        assertThat(lineas.get(1).has("destacada")).isFalse();
        assertThat(lineas.get(1).at("/cifras/porcentajes/menosDe3").asDouble()).isEqualTo(38.5);
        assertThat(lineas.get(1).get("cancelados").asInt()).isEqualTo(2);
        assertThat(lineas.get(1).at("/cobertura/porcentaje").asDouble()).isEqualTo(71.4);
        assertThat(n.get("fuente").asString()).contains("Datos: Renfe (CC BY 4.0)").contains("vistos en directo");
    }

    @Test
    @Order(21)
    void unaLineaPorHoraDiaSemanaYTramos() {
        JsonNode l = get("/api/estadisticas/lineas/51/rl4" + PERIODO);
        assertThat(l.get("linea").asString()).isEqualTo("RL4");
        // 13 paradas: 0, 0(-1), 2, 4, 5, 6, 10, 18, 20 del 23 y 1, 3, 4, 0 del 25. Suma 72 → 5,5.
        JsonNode r = l.get("resumen");
        assertThat(r.get("viajes").asInt()).isEqualTo(5);
        assertThat(r.get("paradas").asInt()).isEqualTo(13);
        assertThat(r.get("suficiente").asBoolean()).isTrue();
        assertThat(r.get("mediaMinutos").asDouble()).isEqualTo(5.5);
        // Ordenadas (el adelanto como 0): 0 0 0 1 2 3 [4] 4 5 6 10 [18] 20. Mediana (7.ª de 13) 4; p90 (12.ª) 18.
        assertThat(r.get("medianaMinutos").asInt()).isEqualTo(4);
        assertThat(r.get("p90Minutos").asInt()).isEqualTo(18);
        assertThat(r.at("/porcentajes/menosDe3").asDouble()).isEqualTo(38.5);
        assertThat(r.at("/porcentajes/de3a5").asDouble()).isEqualTo(30.8);
        assertThat(r.at("/porcentajes/de6a15").asDouble()).isEqualTo(15.4);
        assertThat(r.at("/porcentajes/de16OMas").asDouble()).isEqualTo(15.4);
        // Por viaje, solo los que se vieron en su destino (D): T1 con 6 y T2 con -1 → 2,5; menos de 3: 1 de 2. T3, T9 y
        // T10 dejaron de verse antes: cuentan en viajes, no en la cifra.
        assertThat(l.at("/llegadaDestino/viajes").asInt()).isEqualTo(5);
        assertThat(l.at("/llegadaDestino/viajesEnDestino").asInt()).isEqualTo(2);
        assertThat(l.at("/llegadaDestino/mediaMinutos").asDouble()).isEqualTo(2.5);
        assertThat(l.at("/llegadaDestino/menosDe3").asDouble()).isEqualTo(50.0);
        assertThat(l.at("/coberturaLinea/porcentaje").asDouble()).isEqualTo(71.4);

        // Por hora: 0, 2, 3, 8, 9 y 23. La de las 9: 23-10 (6, 10, -1) y 25-10 (0) → 15 / 4 = 3,75 → 3,8; 3 viajes.
        JsonNode horas = l.get("porHora");
        assertThat(horas.valueStream().map(h -> h.get("hora").asInt()).toList()).containsExactly(0, 2, 3, 8, 9, 23);
        JsonNode h9 = horas.get(4).get("cifras");
        assertThat(h9.get("viajes").asInt()).isEqualTo(3);
        assertThat(h9.get("mediaMinutos").asDouble()).isEqualTo(3.8);
        assertThat(h9.at("/porcentajes/menosDe3").asDouble()).isEqualTo(50.0);
        // Muestra mínima (2 en esta prueba): la franja de las 23 tiene un solo viaje.
        JsonNode h23 = horas.get(5).get("cifras");
        assertThat(h23.get("viajes").asInt()).isEqualTo(1);
        assertThat(h23.get("suficiente").asBoolean()).isFalse();
        assertThat(h23.get("mediaMinutos").isNull()).isTrue();
        assertThat(h23.get("porcentajes").isNull()).isTrue();

        // Días de la semana con datos: viernes (3 viajes, 64 / 9 = 7,1) y domingo (2 viajes, 8 / 4 = 2,0). El sábado
        // está calculado, pero vacío.
        JsonNode dias = l.get("porDiaSemana");
        assertThat(dias.valueStream().map(d -> d.get("nombre").asString()).toList())
                .containsExactly("viernes", "domingo");
        assertThat(dias.get(0).get("dias").asInt()).isEqualTo(1);
        assertThat(dias.get(0).at("/cifras/viajes").asInt()).isEqualTo(3);
        assertThat(dias.get(0).at("/cifras/mediaMinutos").asDouble()).isEqualTo(7.1);
        assertThat(dias.get(1).at("/cifras/mediaMinutos").asDouble()).isEqualTo(2.0);
        // Una semana (la del lunes 19-10) con 2 días con datos.
        assertThat(l.get("porSemana")).hasSize(1);
        assertThat(l.at("/porSemana/0/lunes").asString()).isEqualTo("2026-10-19");
        assertThat(l.at("/porSemana/0/dias").asInt()).isEqualTo(2);

        // Tramos con 2 pasos o más, de más a menos minutos perdidos: B→C (+3 y +1 = 2,0), A→B (+2, -2, +2 = 0,7) y
        // C→D (+1, -11 = -5,0).
        JsonNode tramos = l.get("tramos");
        assertThat(tramos.valueStream().map(t -> t.at("/desde/stopId").asString() + "→" + t.at("/hasta/stopId").asString())
                .toList()).containsExactly("90002→90003", "90001→90002", "90003→90004");
        assertThat(tramos.get(0).get("mediaMinutos").asDouble()).isEqualTo(2.0);
        assertThat(tramos.get(0).get("pierden2OMas").asDouble()).isEqualTo(50.0);
        assertThat(tramos.get(0).at("/desde/nombre").asString()).isEqualTo("Bell-lloc d'Urgell");
        assertThat(tramos.get(1).get("pasos").asInt()).isEqualTo(3);
        assertThat(tramos.get(1).get("mediaMinutos").asDouble()).isEqualTo(0.7);
        assertThat(tramos.get(2).get("mediaMinutos").asDouble()).isEqualTo(-5.0);
        assertThat(tramos.get(2).at("/hasta/nombre").asString()).isEqualTo("Tàrrega");
    }

    @Test
    @Order(22)
    void todaEspanaEsElRankingDeLosNucleos() {
        JsonNode t = get("/api/estadisticas/nucleos/todos" + PERIODO);
        assertThat(t.at("/periodo/diasConDatos").asInt()).isEqualTo(2);
        assertThat(t.at("/muestra/viajes").asInt()).isEqualTo(7);
        assertThat(t.at("/cobertura/porcentaje").asDouble()).isEqualTo(77.8);
        JsonNode nucleos = t.get("nucleos");
        assertThat(nucleos).hasSize(1);
        JsonNode n = nucleos.get(0);
        assertThat(n.get("puesto").asInt()).isEqualTo(1);
        assertThat(n.get("nombre").asString()).isEqualTo("Rodalies de Catalunya");
        // Las 17 paradas del núcleo (las 13 de la RL4 y las 4 de la R3), sumadas en SQL con su histograma:
        // suma 74 → 4,4; menos de 3: 9 → 52,9 %; ordenadas, 0 0 0 0 0 1 1 1 [2] 3 4 4 5 6 10 [18] 20.
        JsonNode c = n.get("cifras");
        assertThat(c.get("paradas").asInt()).isEqualTo(17);
        assertThat(c.get("mediaMinutos").asDouble()).isEqualTo(4.4);
        assertThat(c.at("/porcentajes/menosDe3").asDouble()).isEqualTo(52.9);
        assertThat(c.get("medianaMinutos").asInt()).isEqualTo(2);
        assertThat(c.get("p90Minutos").asInt()).isEqualTo(18);
        assertThat(n.get("cancelados").asInt()).isEqualTo(2);
    }

    @Test
    @Order(23)
    void unaEstacionEnTotalYPorLinea() {
        JsonNode e = get("/api/estadisticas/estaciones/90001" + PERIODO);
        assertThat(e.get("nombre").asString()).isEqualTo("Lleida-Pirineus");
        // RL4: 0, 4, 20, 1, 0 (5 viajes, suma 25); R3: 1, 0 (2 viajes, suma 1). Total 26 / 7 = 3,7.
        assertThat(e.at("/resumen/viajes").asInt()).isEqualTo(7);
        assertThat(e.at("/resumen/mediaMinutos").asDouble()).isEqualTo(3.7);
        assertThat(e.at("/coberturaNucleo/porcentaje").asDouble()).isEqualTo(77.8);
        JsonNode lineas = e.get("porLinea");
        assertThat(lineas.valueStream().map(x -> x.get("linea").asString()).toList()).containsExactly("R3", "RL4");
        assertThat(lineas.get(1).at("/cifras/mediaMinutos").asDouble()).isEqualTo(5.0);
        assertThat(lineas.get(1).at("/cifras/porcentajes/menosDe3").asDouble()).isEqualTo(60.0);
        assertThat(get("/api/estadisticas/estaciones/90002" + PERIODO).at("/muestra/paradasInferidasQuitadas").asInt())
                .isEqualTo(1);
        // En C, la de T3 no tiene hora: tampoco entra, y se cuenta aparte.
        assertThat(get("/api/estadisticas/estaciones/90003" + PERIODO).at("/muestra/paradasSinHoraQuitadas").asInt())
                .isEqualTo(1);
    }

    @Test
    @Order(24)
    void sinDatosEnElPeriodoYPeriodoPorDefecto() {
        JsonNode l = get("/api/estadisticas/lineas/51/R3?desde=2026-09-01&hasta=2026-09-07");
        assertThat(l.at("/periodo/diasConDatos").asInt()).isZero();
        assertThat(l.at("/resumen/viajes").asInt()).isZero();
        assertThat(l.at("/resumen/suficiente").asBoolean()).isFalse();
        assertThat(l.get("porHora")).isEmpty();
        assertThat(l.get("porDiaSemana")).isEmpty();
        assertThat(l.get("tramos")).isEmpty();
        assertThat(l.at("/coberturaLinea/porcentaje").isNull()).isTrue();
        // Por defecto: las 4 semanas que acaban ayer (el reloj está en el 26-10).
        JsonNode n = get("/api/estadisticas/nucleos/51");
        assertThat(n.at("/periodo/desde").asString()).isEqualTo("2026-09-28");
        assertThat(n.at("/periodo/hasta").asString()).isEqualTo("2026-10-25");
        // Una línea del horario sin ningún dato también existe.
        jdbc.sql("INSERT INTO horario.routes (route_id, route_short_name, route_type, nucleo) VALUES ('51T0001R7', 'R7', 2, 51)")
                .update();
        assertThat(get("/api/estadisticas/lineas/51/R7").at("/resumen/viajes").asInt()).isZero();
    }

    @Test
    @Order(25)
    void errores() {
        problema("/api/estadisticas/nucleos/98", 404);
        problema("/api/estadisticas/lineas/51/R99", 404);
        problema("/api/estadisticas/estaciones/00000", 404);
        assertThat(problema("/api/estadisticas/nucleos/51?desde=2026-10-25&hasta=2026-10-23", 400).get("parametro")
                .asString()).isEqualTo("desde");
        assertThat(problema("/api/estadisticas/nucleos/51?desde=ayer", 400).get("parametro").asString())
                .isEqualTo("desde");
        assertThat(problema("/api/estadisticas/lineas/51/RL4?desde=2020-01-01&hasta=2026-10-25", 400).get("parametro")
                .asString()).isEqualTo("hasta");
    }

    @Test
    @Order(26)
    void antesDeLaFechaDePublicacionNoHayNingunaCifra() {
        reloj.ahora = Instant.parse("2026-10-25T12:00:00Z");
        try {
            JsonNode n = get("/api/estadisticas/nucleos/51" + PERIODO);
            assertThat(n.at("/publicacion/publicado").asBoolean()).isFalse();
            assertThat(n.at("/publicacion/texto").asString())
                    .isEqualTo("Recogiendo datos desde el 23-10-2026; cifras a partir del 26-10-2026.");
            assertThat(n.get("muestra").isNull()).isTrue();
            assertThat(n.get("cobertura").isNull()).isTrue();
            assertThat(n.get("lineas")).isEmpty();
            JsonNode l = get("/api/estadisticas/lineas/51/RL4" + PERIODO);
            assertThat(l.get("resumen").isNull()).isTrue();
            assertThat(l.get("tramos")).isEmpty();
            assertThat(get("/api/estadisticas/estaciones/90001" + PERIODO).get("porLinea")).isEmpty();
            JsonNode t = get("/api/estadisticas/nucleos/todos" + PERIODO);
            assertThat(t.get("muestra").isNull()).isTrue();
            assertThat(t.get("nucleos")).isEmpty();
        } finally {
            reloj.ahora = Instant.parse("2026-10-26T09:00:00Z");
        }
    }

    @Test
    @Order(27)
    void recalcularPorActuatorYOpenApi() {
        String cuerpo = http().post().uri("/actuator/estadisticas").contentType(MediaType.APPLICATION_JSON)
                .body("{\"desde\": \"2026-10-23\", \"hasta\": \"2026-10-25\"}").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode r = json.readTree(cuerpo);
        assertThat(r).hasSize(3);
        assertThat(r.get(0).get("fecha").asString()).isEqualTo("2026-10-23");
        assertThat(r.get(0).at("/resultado/filasRetrasos").asInt()).isEqualTo(16);
        assertThat(r.get(0).get("error").isNull()).isTrue();
        http().post().uri("/actuator/estadisticas").contentType(MediaType.APPLICATION_JSON)
                .body("{\"desde\": \"2026-10-25\", \"hasta\": \"2026-10-23\"}").exchange().expectStatus().isBadRequest();
        JsonNode calculados = json.readTree(http().get().uri("/actuator/estadisticas").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(calculados.get(0).get("fecha").asString()).isEqualTo("2026-10-25");

        JsonNode docs = json.readTree(http().get().uri("/api/docs").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        for (String ruta : List.of("/api/estadisticas/nucleos/todos", "/api/estadisticas/nucleos/{nucleo}", "/api/estadisticas/lineas/{nucleo}/{linea}",
                "/api/estadisticas/estaciones/{stopId}")) {
            JsonNode op = docs.get("paths").get(ruta).get("get");
            assertThat(op).as(ruta).isNotNull();
            assertThat(op.at("/responses/200/content").valueStream().anyMatch(t -> t.at("/examples/ejemplo/value")
                    .isObject())).as(ruta + " con el ejemplo de docs/api").isTrue();
            assertThat(op.at("/responses/404/content/application~1problem+json").isMissingNode()).isFalse();
        }
        // El endpoint de Actuator no es parte de la API pública documentada.
        assertThat(docs.get("paths").has("/actuator/estadisticas")).isFalse();
    }
}
