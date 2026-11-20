package com.ivanchesa.trainwatch.directo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.ivanchesa.trainwatch.TestcontainersConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Aceptación de la fase 3 con PostGIS en Testcontainers: de los {@code .pb} grabados (servidos por un servidor HTTP
 * local que hace de Renfe) a filas en {@code posiciones}, {@code retrasos} y {@code avisos}, con las particiones
 * creadas y borradas, el 304 que no procesa nada, la muestra incompleta y {@code GET /api/directo/estado}.
 *
 * <p>El horario se escribe a mano con dos viajes: el {@code 4023V24219C1} real (C1 de Valencia), que en las muestras
 * pasa de la 69103 a la 69104 en {@code trip_updates} con 3 min de retraso, y uno de Bilbao en marcha según el
 * horario pero sin directo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, DirectoIntegracionTests.Reloj.class })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DirectoIntegracionTests {

    static final ServidorFeeds RENFE = new ServidorFeeds();
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** Un reloj que la prueba mueve a mano. */
    static class RelojMovil extends Clock {
        volatile Instant ahora = Instant.parse("2026-11-20T09:28:20Z");

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

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("trainwatch.directo.url-posiciones", () -> RENFE.url("/vehicle_positions.pb").toString());
        registro.add("trainwatch.directo.url-retrasos", () -> RENFE.url("/trip_updates.pb").toString());
        registro.add("trainwatch.directo.url-avisos", () -> RENFE.url("/alerts.pb").toString());
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    IngestaDirecto ingesta;
    @Autowired
    EstadoDirecto estado;
    @Autowired
    GestorParticiones particiones;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RelojMovil reloj;
    @Autowired
    MeterRegistry metricas;
    @LocalServerPort
    int puerto;

    static byte[] leer(String nombre) throws IOException {
        try (InputStream in = DirectoIntegracionTests.class.getResourceAsStream("/gtfsrt/" + nombre)) {
            return in.readAllBytes();
        }
    }

    void servir(String hora) throws IOException {
        RENFE.servir("/vehicle_positions.pb", leer("20261120T" + hora + "Z_vehicle_positions.pb"));
        RENFE.servir("/trip_updates.pb", leer("20261120T" + hora + "Z_trip_updates.pb"));
        reloj.ahora = Instant.parse("2026-11-20T" + hora.substring(0, 2) + ":" + hora.substring(2, 4) + ":"
                + hora.substring(4) + "Z").plusSeconds(5);
    }

    long filas(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla).query(Long.class).single();
    }

    double contador(String nombre, String... etiquetas) {
        var c = metricas.find(nombre).tags(etiquetas).counter();
        return c == null ? 0 : c.count();
    }

    void horarioDePrueba() {
        jdbc.sql("""
                INSERT INTO horario.stops (stop_id, stop_name, stop_lat, stop_lon) VALUES
                  ('69102', 'Uno', 39.40, -0.40), ('69103', 'Dos', 39.41, -0.40),
                  ('69104', 'Tres', 39.42, -0.40), ('69105', 'Cuatro', 39.43, -0.40);
                INSERT INTO horario.routes (route_id, route_short_name, route_type, nucleo) VALUES
                  ('40T0001C1', 'C1', 2, 40), ('60T0001C1', 'C1', 2, 60);
                INSERT INTO horario.calendar VALUES
                  ('4020261120', false, false, false, false, true, false, false, '2026-11-20', '2026-11-20'),
                  ('6020261120', false, false, false, false, true, false, false, '2026-11-20', '2026-11-20');
                INSERT INTO horario.shapes VALUES ('40_C1',
                  ST_GeomFromText('LINESTRING(-0.40 39.40, -0.40 39.43)', 4326), 2, 3336, false);
                INSERT INTO horario.parada_forma VALUES ('40_C1', '69102', 0, 0), ('40_C1', '69103', 0.3333333333, 0),
                  ('40_C1', '69104', 0.6666666667, 0), ('40_C1', '69105', 1, 0);
                INSERT INTO horario.trips (trip_id, route_id, service_id, shape_id, forma_sentido, forma_encaja) VALUES
                  ('4023V24219C1', '40T0001C1', '4020261120', '40_C1', 1, true),
                  ('6020T1', '60T0001C1', '6020261120', NULL, NULL, false);
                INSERT INTO horario.stop_times VALUES
                  ('4023V24219C1', 1, '69102', 37200, 37200), ('4023V24219C1', 2, '69103', 37440, 37470),
                  ('4023V24219C1', 3, '69104', 37680, 37710), ('4023V24219C1', 4, '69105', 37920, 37920),
                  ('6020T1', 1, '69102', 36000, 36000), ('6020T1', 2, '69105', 39600, 39600);
                """).update();
    }

    @Test
    @Order(1)
    void deLosPbGrabadosAFilasEnPosicionesRetrasosYAvisos() throws IOException {
        horarioDePrueba();
        RENFE.servir("/alerts.pb", leer("20261120T092915Z_alerts.pb"));

        servir("092815");
        ingesta.ciclo();
        long posiciones1 = filas("posiciones");
        assertThat(posiciones1).isGreaterThan(300);
        assertThat(filas("avisos")).isGreaterThanOrEqualTo(71);
        assertThat(contador("trainwatch.directo.filas", "tabla", "posiciones")).isEqualTo(posiciones1);

        // Nada nuevo en Renfe: tres 304 y nada procesado.
        double muestras = contador("trainwatch.directo.muestras", "feed", "posiciones", "completa", "true");
        reloj.ahora = reloj.ahora.plusSeconds(7);
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.descargas", "feed", "posiciones", "resultado", "304")).isEqualTo(1);
        assertThat(contador("trainwatch.directo.descargas", "feed", "retrasos", "resultado", "304")).isEqualTo(1);
        assertThat(contador("trainwatch.directo.descargas", "feed", "avisos", "resultado", "304")).isEqualTo(1);
        assertThat(contador("trainwatch.directo.muestras", "feed", "posiciones", "completa", "true")).isEqualTo(muestras);
        assertThat(filas("posiciones")).isEqualTo(posiciones1);

        servir("092835");
        ingesta.ciclo();
        long posiciones2 = filas("posiciones");
        // Solo los trenes que cambian: muchas menos filas que trenes.
        assertThat(posiciones2 - posiciones1).isBetween(10L, 150L);
        servir("092855");
        ingesta.ciclo();

        // La C1 de Valencia pasa de la 69103 a la 69104: el retraso final de la 69103 es el último conocido, 3 min.
        Map<String, Object> retraso = jdbc.sql("""
                SELECT fecha_servicio, stop_id, stop_sequence, minutos, linea, nucleo, route_id
                FROM retrasos WHERE trip_id = '4023V24219C1'""").query().singleRow();
        assertThat(retraso).containsEntry("stop_id", "69103").containsEntry("stop_sequence", 2)
                .containsEntry("minutos", 3).containsEntry("linea", "C1").containsEntry("route_id", "40T0001C1");
        assertThat(((java.sql.Date) retraso.get("fecha_servicio")).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(((Number) retraso.get("nucleo")).intValue()).isEqualTo(40);
        assertThat(jdbc.sql("SELECT tableoid::regclass::text FROM retrasos LIMIT 1").query(String.class).single())
                .isEqualTo("retrasos_p202611");
        assertThat(jdbc.sql("SELECT DISTINCT tableoid::regclass::text FROM posiciones").query(String.class).list())
                .containsExactly("posiciones_p20261120");

        // El tren con horario tiene posición estimada sobre su recorrido y rumbo (va hacia el norte).
        Tren c1 = estado.tren("4023V24219C1");
        assertThat(c1.viaje()).isNotNull();
        assertThat(c1.estimada().fuente()).isEqualTo(PosicionEstimada.Fuente.RECORRIDO);
        assertThat(c1.estimada().rumbo()).isCloseTo(0.0, org.assertj.core.api.Assertions.within(0.5));
        assertThat(jdbc.sql("SELECT count(*) FROM posiciones WHERE trip_id = '4023V24219C1' AND rumbo IS NOT NULL")
                .query(Long.class).single()).isPositive();

        // La muestra incompleta (un tercio menos) no hace desaparecer a nadie.
        int trenesAntes = estado.trenes().size();
        servir("092915");
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.muestras", "feed", "posiciones", "completa", "false")).isEqualTo(1);
        assertThat(estado.trenes()).hasSize(trenesAntes);

        // El estado por núcleo: Valencia con el C1 visto; Bilbao, en marcha según el horario y sin directo.
        RestTestClient http = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        ResumenDirecto resumen = http.get().uri("/api/directo/estado").exchange().expectStatus().isOk()
                .expectBody(ResumenDirecto.class).returnResult().getResponseBody();
        assertThat(resumen.trenes()).isEqualTo(trenesAntes);
        assertThat(resumen.ultimoDato()).isEqualTo(Instant.ofEpochSecond(1795166944));
        assertThat(resumen.sinHorario()).isEqualTo(trenesAntes - 1);
        assertThat(resumen.circuitos()).containsEntry("posiciones", "CLOSED");
        ResumenDirecto.Nucleo valencia = nucleo(resumen.nucleos(), 40);
        assertThat(valencia.estado()).isEqualTo("EN_DIRECTO");
        assertThat(valencia.enMarchaHorario()).isEqualTo(1);
        assertThat(valencia.coberturaPct()).isEqualTo(100.0);
        ResumenDirecto.Nucleo bilbao = nucleo(resumen.nucleos(), 60);
        assertThat(bilbao.estado()).isEqualTo("SIN_DATOS_EN_DIRECTO");
        assertThat(bilbao.nombre()).isEqualTo("Bilbao");
        assertThat(bilbao.coberturaPct()).isEqualTo(0.0);
        assertThat(nucleo(resumen.nucleos(), 51).estado()).isEqualTo("EN_DIRECTO");

        assertThat(metricas.find("trainwatch.directo.trenes.sin.horario").gauge().value()).isEqualTo(trenesAntes - 1);
        assertThat(metricas.find("trainwatch.directo.antiguedad").tag("feed", "posiciones").gauge().value())
                .isEqualTo(Instant.parse("2026-11-20T09:29:20Z").getEpochSecond() - 1795166944);
    }

    @Test
    @Order(2)
    void lasParticionesSeCreanYLasDePosicionesSeBorranPasadas48Horas() {
        Instant hoy = Instant.parse("2026-11-20T09:30:00Z");
        particiones.mantener(hoy);
        assertThat(particiones.particiones("posiciones"))
                .contains("posiciones_p20261119", "posiciones_p20261120", "posiciones_p20261121", "posiciones_p20261122");
        assertThat(particiones.particiones("retrasos"))
                .containsExactly("retrasos_p202610", "retrasos_p202611", "retrasos_p202612");
        long deHoy = filas("posiciones");
        assertThat(deHoy).isPositive();

        // Tres días después, el día 20 entero queda a más de 48 h: su partición se borra (DROP, no DELETE).
        GestorParticiones.Resultado r = particiones.mantener(Instant.parse("2026-11-23T09:30:00Z"));
        assertThat(r.borradas()).containsExactly("posiciones_p20261119", "posiciones_p20261120");
        assertThat(particiones.particiones("posiciones")).doesNotContain("posiciones_p20261120")
                .contains("posiciones_p20261121", "posiciones_p20261125");
        assertThat(filas("posiciones")).isZero();
        // Los retrasos no se borran nunca.
        assertThat(particiones.particiones("retrasos")).contains("retrasos_p202611");
        assertThat(filas("retrasos")).isPositive();
    }

    static ResumenDirecto.Nucleo nucleo(List<ResumenDirecto.Nucleo> nucleos, int codigo) {
        return nucleos.stream().filter(n -> n.nucleo() == codigo).findFirst().orElseThrow();
    }
}
