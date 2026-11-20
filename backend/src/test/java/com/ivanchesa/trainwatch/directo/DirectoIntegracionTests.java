package com.ivanchesa.trainwatch.directo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.ivanchesa.trainwatch.TestcontainersConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Aceptación de la fase 3 con PostGIS en Testcontainers: de los {@code .pb} grabados (servidos por un servidor HTTP
 * local que hace de Renfe) a filas en {@code posiciones}, {@code retrasos} y {@code avisos}, con las particiones
 * creadas y borradas, el 304 que no procesa nada, la muestra incompleta y {@code GET /api/directo/estado}. Y lo de
 * la fase 3b: tres incompletas seguidas, la base de datos caída (al escribir y al leer el horario), el {@code ETag}
 * que no se confirma si el procesado falla, los cierres inferidos y el borrado de particiones con un lector abierto.
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
        registro.add("trainwatch.directo.espera-cerrojo-particiones", () -> "1s");
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    IngestaDirecto ingesta;
    @MockitoSpyBean
    EstadoDirecto estado;
    @Autowired
    HorarioDirectoJdbc horarioJdbc;
    @Autowired
    RepositorioDirecto repositorio;
    @Autowired
    javax.sql.DataSource dataSource;
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
        // Fase 4: la hora programada de llegada a la 69103 (37.440 s = 10:24, hora de Madrid).
        assertThat(jdbc.sql("SELECT llegada_programada FROM retrasos WHERE trip_id = '4023V24219C1'")
                .query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(Instant.parse("2026-11-20T09:24:00Z"));
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
        assertThat(jdbc.sql("SELECT inferido FROM retrasos WHERE trip_id = '4023V24219C1'").query(Boolean.class)
                .single()).isFalse();

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

        // Fase 3b: la antigüedad se calcula al responder. Si pasa un minuto sin ciclos (ingesta parada), se nota.
        reloj.ahora = reloj.ahora.plusSeconds(60);
        ResumenDirecto parado = http.get().uri("/api/directo/estado").exchange().expectStatus().isOk()
                .expectBody(ResumenDirecto.class).returnResult().getResponseBody();
        assertThat(parado.antiguedadS()).isEqualTo(resumen.antiguedadS() + 60);
        assertThat(parado.calculado()).isEqualTo(resumen.calculado());
    }

    /**
     * Fase 3b: una muestra incompleta, sola, tampoco cerraría nada con una completa (hacen falta 3 ausencias). Aquí
     * son tres seguidas: los trenes que solo faltan en ellas no desaparecen y sus viajes no se cierran.
     */
    @Test
    @Order(2)
    void tresMuestrasIncompletasSeguidasNoBorranLosTrenesQueFaltan() throws IOException {
        // Los que estaban en las dos muestras completas anteriores (sin ausencias) y faltan en la incompleta.
        java.util.Set<String> antes = idsPosiciones("092835");
        antes.retainAll(idsPosiciones("092855"));
        antes.removeAll(idsPosiciones("092915"));
        assertThat(antes).hasSizeGreaterThan(50);
        double desaparecidos = contador("trainwatch.directo.trenes.desaparecidos");

        // La incompleta ya se procesó en la prueba anterior; dos veces más, con cabeceras nuevas.
        servirConCabecera("092915", 20);
        ingesta.ciclo();
        // Dos incompletas seguidas: nadie desaparece.
        assertThat(contador("trainwatch.directo.trenes.desaparecidos")).isEqualTo(desaparecidos);
        // La tercera se acepta como nivel nuevo: es la primera ausencia que cuenta (solo desaparecen los que ya
        // faltaban en las dos completas anteriores).
        servirConCabecera("092915", 40);
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.muestras", "feed", "posiciones", "completa", "false")).isEqualTo(2);
        assertThat(contador("trainwatch.directo.muestras", "feed", "posiciones", "completa", "true")).isEqualTo(4);
        for (String id : antes) {
            assertThat(estado.tren(id)).as(id).isNotNull();
            assertThat(estado.tren(id).ausencias()).as(id).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void conLaBaseDeDatosCaidaElDirectoSigueYLasFilasEsperan() throws IOException {
        particiones.mantener(Instant.parse("2026-11-20T09:30:00Z"));
        long posicionesAntes = filas("posiciones");
        double erroresBd = contador("trainwatch.directo.errores", "feed", "-", "tipo", "base_de_datos");

        // 1. No se puede escribir: el mapa se pone al día igual y las filas esperan en la cola.
        jdbc.sql("ALTER TABLE posiciones RENAME TO posiciones_caida").update();
        int pendientes;
        Instant guardadoAntes = ingesta.resumen().guardadoAlDia();
        ResumenDirecto conLaBaseCaida;
        try {
            servirConCabecera("092815", 1_000);
            reloj.ahora = reloj.ahora.plusSeconds(7);
            ingesta.ciclo();
            pendientes = ingesta.pendientes("posiciones");
            conLaBaseCaida = ingesta.resumen();
        } finally {
            jdbc.sql("ALTER TABLE posiciones_caida RENAME TO posiciones").update();
        }
        assertThat(pendientes).isGreaterThan(10);
        assertThat(contador("trainwatch.directo.errores", "feed", "-", "tipo", "base_de_datos")).isGreaterThan(erroresBd);
        long cabecera = cabecera("092815") + 1_000;
        assertThat(estado.ultimaMuestraPosiciones()).isEqualTo(Instant.ofEpochSecond(cabecera));
        // Fase 7: el estado lo dice (para la vigilancia): el dato de Renfe es nuevo, pero «guardado al día» no avanza
        // y hay filas pendientes.
        assertThat(conLaBaseCaida.calculado()).isAfter(guardadoAntes);
        assertThat(conLaBaseCaida.guardadoAlDia()).isEqualTo(guardadoAntes);
        assertThat(conLaBaseCaida.pendientes()).containsEntry("posiciones", pendientes);

        // Vuelve: el ciclo siguiente (sin nada nuevo en Renfe) escribe lo pendiente, sin perder nada.
        reloj.ahora = reloj.ahora.plusSeconds(7);
        ingesta.ciclo();
        assertThat(ingesta.pendientes("posiciones")).isZero();
        assertThat(filas("posiciones")).isEqualTo(posicionesAntes + pendientes);
        assertThat(ingesta.resumen().guardadoAlDia()).isEqualTo(reloj.ahora);
        assertThat(ingesta.resumen().pendientes()).containsEntry("posiciones", 0).containsEntry("retrasos", 0);

        // 2. No se puede leer el horario (y con las cachés vacías): los viajes se saltan y se cuentan, sin error de
        // procesado y sin mover ni borrar trenes.
        Tren c1 = estado.tren("4023V24219C1");
        double procesado = contador("trainwatch.directo.errores", "feed", "posiciones", "tipo", "procesado");
        horarioJdbc.refrescarSiCambia(Instant.parse("2026-11-21T10:00:00Z")); // otro día: vacía las cachés
        jdbc.sql("ALTER SCHEMA horario RENAME TO horario_caido").update();
        try {
            servirConCabecera("092855", 1_100);
            ingesta.ciclo();
        } finally {
            jdbc.sql("ALTER SCHEMA horario_caido RENAME TO horario").update();
        }
        assertThat(contador("trainwatch.directo.viajes.sin.resolver", "feed", "posiciones")).isGreaterThan(300);
        assertThat(contador("trainwatch.directo.errores", "feed", "posiciones", "tipo", "procesado")).isEqualTo(procesado);
        // El mismo tren, sin tocar (salvo la posición estimada, que avanza con la hora en cada ciclo).
        assertThat(estado.tren("4023V24219C1")).usingRecursiveComparison().ignoringFields("estimada").isEqualTo(c1);

        // Vuelve: la muestra siguiente se procesa con normalidad.
        double sinResolver = contador("trainwatch.directo.viajes.sin.resolver", "feed", "posiciones");
        servirConCabecera("092835", 1_200);
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.viajes.sin.resolver", "feed", "posiciones")).isEqualTo(sinResolver);
        assertThat(estado.tren("4023V24219C1").viaje()).isNotNull();
    }

    @Test
    @Order(4)
    void siElProcesadoFallaElEtagNoSeConfirmaYLaMuestraSeRepite() throws IOException {
        double descargas200 = contador("trainwatch.directo.descargas", "feed", "retrasos", "resultado", "200");
        double muestras = contador("trainwatch.directo.muestras", "feed", "retrasos", "completa", "true")
                + contador("trainwatch.directo.muestras", "feed", "retrasos", "completa", "false");
        doThrow(new IllegalStateException("Fallo de prueba a mitad de la muestra")).doCallRealMethod()
                .when(estado).procesarRetrasos(any(), any());
        servirConCabecera("092855", 1_300);

        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.errores", "feed", "retrasos", "tipo", "procesado")).isEqualTo(1);
        // Sin confirmar: el ciclo siguiente vuelve a pedirla sin If-None-Match válido, recibe un 200 y la procesa.
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.descargas", "feed", "retrasos", "resultado", "200"))
                .isEqualTo(descargas200 + 2);
        assertThat(contador("trainwatch.directo.muestras", "feed", "retrasos", "completa", "true")
                + contador("trainwatch.directo.muestras", "feed", "retrasos", "completa", "false"))
                .isEqualTo(muestras + 1);
        // Ya confirmada: el siguiente es un 304.
        double sinCambios = contador("trainwatch.directo.descargas", "feed", "retrasos", "resultado", "304");
        ingesta.ciclo();
        assertThat(contador("trainwatch.directo.descargas", "feed", "retrasos", "resultado", "304"))
                .isEqualTo(sinCambios + 1);
    }

    @Test
    @Order(5)
    void unaFilaSinParticionNoBloqueaLaColaNiDuplicaLasDemas() throws IOException {
        // 501 posiciones buenas y una de 1999, que no cabe en ninguna partición, en la misma muestra.
        particiones.mantener(Instant.parse("2026-11-20T09:30:00Z"));
        Tren base = estado.tren("4023V24219C1");
        List<Tren> cambios = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            cambios.add(new Tren("COLA" + i, "COLA" + i, null, "C1", 40, null, base.fechaServicio(), EstadoTren.EN_MARCHA,
                    null, -1, null, 39.4, -0.4, Instant.parse("2026-11-20T09:30:00Z"), null, null, 0));
        }
        cambios.add(250, new Tren("COLA_MALA", "COLA_MALA", null, "C1", 40, null, base.fechaServicio(),
                EstadoTren.EN_MARCHA, null, -1, null, 39.4, -0.4, Instant.parse("1999-01-01T00:00:00Z"), null, null, 0));
        org.mockito.Mockito.doReturn(new EstadoDirecto.ResultadoPosiciones(true, true, cambios, 0, 0))
                .doCallRealMethod().when(estado).procesarPosiciones(any(), any());
        double descartadas = contador("trainwatch.directo.filas.descartadas", "tabla", "posiciones");
        servirConCabecera("092855", 1_400);

        ingesta.ciclo();

        assertThat(ingesta.pendientes("posiciones")).isZero();
        assertThat(contador("trainwatch.directo.filas.descartadas", "tabla", "posiciones")).isEqualTo(descartadas + 1);
        // Cada buena, una sola vez: el intento con la cola entera iba en una transacción y no dejó nada a medias.
        assertThat(jdbc.sql("SELECT count(*) FROM posiciones WHERE trip_id LIKE 'COLA%'").query(Long.class).single())
                .isEqualTo(501);
        assertThat(jdbc.sql("SELECT count(DISTINCT trip_id) FROM posiciones WHERE trip_id LIKE 'COLA%'")
                .query(Long.class).single()).isEqualTo(501);
    }

    @Test
    @Order(5)
    void lasConexionesLlevanLosPlazosCortos() {
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("15s");
    }

    @Test
    @Order(5)
    void unCierreInferidoNoPisaUnoVisto() {
        LocalDate dia = LocalDate.of(2026, 11, 20);
        Instant t = Instant.parse("2026-11-20T10:00:00Z");
        repositorio.guardarRetrasos(List.of(new RetrasoFinal(dia, "INF1", 1, "A", "R", "C1", 40, 3, t, false, null)));
        repositorio.guardarRetrasos(List.of(new RetrasoFinal(dia, "INF1", 1, "A", "R", "C1", 40, 9, t, true, null)));
        assertThat(jdbc.sql("SELECT minutos, inferido FROM retrasos WHERE trip_id = 'INF1'").query().singleRow())
                .containsEntry("minutos", 3).containsEntry("inferido", false);
        // Sin hora programada (como las filas anteriores a la V5): NULL, no un valor inventado.
        assertThat(jdbc.sql("SELECT count(*) FROM retrasos WHERE trip_id = 'INF1' AND llegada_programada IS NULL")
                .query(Long.class).single()).isEqualTo(1);

        // Al revés, sí: uno visto sustituye a uno inferido.
        repositorio.guardarRetrasos(List.of(new RetrasoFinal(dia, "INF2", 1, "A", "R", "C1", 40, 9, t, true, null)));
        repositorio.guardarRetrasos(List.of(new RetrasoFinal(dia, "INF2", 1, "A", "R", "C1", 40, 4, t, false, null)));
        assertThat(jdbc.sql("SELECT minutos, inferido FROM retrasos WHERE trip_id = 'INF2'").query().singleRow())
                .containsEntry("minutos", 4).containsEntry("inferido", false);
    }

    @Test
    @Order(6)
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

    /**
     * Fase 3b: con un lector largo abierto sobre {@code posiciones} (la API de la fase 4), borrar una partición vieja
     * no bloquea las escrituras de la ingesta: se separa con {@code DETACH … CONCURRENTLY}, que no choca con los
     * {@code INSERT}, y la espera del cerrojo está acotada por {@code lock_timeout}. Lo que no se pudo hacer se termina
     * en la pasada siguiente. (Antes, el {@code DROP TABLE} pedía un cerrojo exclusivo sobre {@code posiciones} y los
     * {@code INSERT} se quedaban en la cola detrás de él.)
     */
    @Test
    @Order(7)
    void borrarUnaParticionConUnLectorAbiertoNoBloqueaLaIngestaYSeTerminaDespues() throws Exception {
        particiones.mantener(Instant.parse("2026-11-20T09:30:00Z")); // vuelve a crear la del 19 y la del 20
        assertThat(particiones.particiones("posiciones")).contains("posiciones_p20261119", "posiciones_p20261120");
        Instant despues = Instant.parse("2026-11-23T09:30:00Z");

        try (java.sql.Connection lector = dataSource.getConnection()) {
            lector.setAutoCommit(false);
            try (var st = lector.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM posiciones")) {
                rs.next();
            }
            // El lector sigue abierto (con sus cerrojos) mientras el mantenimiento intenta borrar.
            var pasada = java.util.concurrent.CompletableFuture.supplyAsync(() -> particiones.mantener(despues));
            Thread.sleep(300);
            long t0 = System.nanoTime();
            repositorio.guardarPosiciones(List.of(estado.tren("4023V24219C1")));
            java.time.Duration insercion = java.time.Duration.ofNanos(System.nanoTime() - t0);
            assertThat(insercion).isLessThan(java.time.Duration.ofMillis(500));

            GestorParticiones.Resultado r = pasada.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r.fallidas()).isNotEmpty();
            lector.commit();
        }
        GestorParticiones.Resultado r = particiones.mantener(despues);
        assertThat(r.fallidas()).isEmpty();
        assertThat(particiones.particiones("posiciones")).doesNotContain("posiciones_p20261119", "posiciones_p20261120");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_class WHERE relname IN ('posiciones_p20261119', 'posiciones_p20261120')")
                .query(Long.class).single()).isZero();
    }

    Set<String> idsPosiciones(String hora) throws IOException {
        Set<String> ids = new HashSet<>();
        for (FeedEntity e : FeedMessage.parseFrom(leer("20261120T" + hora + "Z_vehicle_positions.pb")).getEntityList()) {
            if (e.hasVehicle() && !e.getVehicle().getTrip().getTripId().isBlank()) {
                ids.add(e.getVehicle().getTrip().getTripId().trim());
            }
        }
        return ids;
    }

    static long cabecera(String hora) throws IOException {
        return FeedMessage.parseFrom(leer("20261120T" + hora + "Z_vehicle_positions.pb")).getHeader().getTimestamp();
    }

    /** Sirve las muestras de esa hora con la cabecera desplazada {@code mas} segundos: para el estado, son nuevas. */
    void servirConCabecera(String hora, long mas) throws IOException {
        for (String feed : List.of("vehicle_positions", "trip_updates")) {
            FeedMessage m = FeedMessage.parseFrom(leer("20261120T" + hora + "Z_" + feed + ".pb"));
            FeedMessage nueva = FeedMessage.newBuilder(m).setHeader(FeedHeader.newBuilder(m.getHeader())
                    .setTimestamp(m.getHeader().getTimestamp() + mas)).build();
            RENFE.servir("/" + feed + ".pb", nueva.toByteArray());
        }
        reloj.ahora = Instant.ofEpochSecond(cabecera(hora) + mas + 5);
    }

    static ResumenDirecto.Nucleo nucleo(List<ResumenDirecto.Nucleo> nucleos, int codigo) {
        return nucleos.stream().filter(n -> n.nucleo() == codigo).findFirst().orElseThrow();
    }
}
