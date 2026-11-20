package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.ivanchesa.trainwatch.TestcontainersConfiguration;
import com.ivanchesa.trainwatch.directo.IngestaDirecto;
import com.ivanchesa.trainwatch.directo.ServidorFeeds;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aceptación de la fase 4 con PostGIS en Testcontainers y la ingesta alimentada con los {@code .pb} grabados de la
 * fase 3 (un servidor HTTP local hace de Renfe): cada endpoint (contrato, errores y casos sin datos), CORS, caché,
 * OpenAPI y el directo por SSE (instantánea y después cambios, latido, reconexión y máximo de conexiones).
 *
 * <p>El horario es el de la prueba de la fase 3 (el C1 real {@code 4023V24219C1} de Valencia y un viaje de Bilbao
 * sin directo), con nombres con tildes para comprobar el UTF-8 de punta a punta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, ApiIntegracionTests.Reloj.class })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegracionTests {

    static final ServidorFeeds RENFE = new ServidorFeeds();
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final String C1 = "4023V24219C1";
    static final int MAXIMO_CONEXIONES = 3;

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
        registro.add("trainwatch.api.directo.maximo-conexiones", () -> MAXIMO_CONEXIONES);
        registro.add("trainwatch.api.directo.latido", () -> "1s");
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    IngestaDirecto ingesta;
    @Autowired
    DifusorDirecto difusor;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RelojMovil reloj;
    @Autowired
    JsonMapper json;
    @LocalServerPort
    int puerto;

    RestTestClient http() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    JsonNode get(String ruta) {
        String cuerpo = http().get().uri(ruta).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        return json.readTree(cuerpo);
    }

    /** Un error: el estado y el ProblemDetail (RFC 9457) con su título en español. */
    JsonNode problema(String ruta, int estado) {
        String cuerpo = http().get().uri(ruta).exchange().expectStatus().isEqualTo(estado)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode p = json.readTree(cuerpo);
        assertThat(p.get("status").asInt()).isEqualTo(estado);
        assertThat(p.get("title").asString()).isNotBlank();
        assertThat(p.get("detail").asString()).isNotBlank();
        return p;
    }

    static byte[] leer(String nombre) throws IOException {
        try (InputStream in = ApiIntegracionTests.class.getResourceAsStream("/gtfsrt/" + nombre)) {
            return in.readAllBytes();
        }
    }

    static long cabecera(String hora) throws IOException {
        return FeedMessage.parseFrom(leer("20261120T" + hora + "Z_vehicle_positions.pb")).getHeader().getTimestamp();
    }

    /** Sirve las muestras de esa hora con la cabecera desplazada {@code mas} segundos y pone el reloj 5 s después. */
    void servir(String hora, long mas) throws IOException {
        for (String feed : List.of("vehicle_positions", "trip_updates")) {
            FeedMessage m = FeedMessage.parseFrom(leer("20261120T" + hora + "Z_" + feed + ".pb"));
            FeedMessage nueva = FeedMessage.newBuilder(m).setHeader(FeedHeader.newBuilder(m.getHeader())
                    .setTimestamp(m.getHeader().getTimestamp() + mas)).build();
            RENFE.servir("/" + feed + ".pb", nueva.toByteArray());
        }
        reloj.ahora = Instant.ofEpochSecond(cabecera(hora) + mas + 5);
    }

    URI directo(int nucleo) {
        return URI.create("http://localhost:" + puerto + "/api/directo?nucleo=" + nucleo);
    }

    static JsonNode tren(JsonNode trenes, String id) {
        for (JsonNode t : trenes) {
            if (id.equals(t.get("id").asString())) {
                return t;
            }
        }
        return null;
    }

    void horarioDePrueba() {
        jdbc.sql("""
                INSERT INTO horario.stops (stop_id, stop_name, stop_lat, stop_lon) VALUES
                  ('69102', 'Uno', 39.40, -0.40), ('69103', 'Dos', 39.41, -0.40),
                  ('69104', 'València Nord', 39.42, -0.40), ('69105', 'Castelló', 39.43, -0.40),
                  ('69200', 'Lejos', 39.60, -0.40);
                INSERT INTO horario.routes (route_id, route_short_name, route_long_name, route_type, route_color,
                                            route_text_color, nucleo) VALUES
                  ('40T0001C1', 'C1', 'València Nord - Castelló', 2, 'E2001A', 'FFFFFF', 40),
                  ('60T0001C1', 'C1', 'Bilbao', 2, NULL, NULL, 60);
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

    // ---------------------------------------------------------------- sin datos

    @Test
    @Order(1)
    void sinDatosEnDirectoLaApiRespondeVacioYLosErroresSonProblemDetail() throws Exception {
        horarioDePrueba();

        // Núcleos del horario, sin ningún ciclo de la ingesta: sin datos en directo, pero con su mapa.
        JsonNode nucleos = get("/api/nucleos");
        assertThat(nucleos).extracting(n -> n.get("id").asInt()).containsExactly(40, 60);
        JsonNode valencia = nucleos.get(0);
        assertThat(valencia.get("nombre").asString()).isEqualTo("Valencia");
        assertThat(valencia.get("estado").asString()).isEqualTo("SIN_DATOS_EN_DIRECTO");
        assertThat(valencia.get("estadoTexto").asString()).isEqualTo("sin datos en directo");
        assertThat(valencia.get("centro").get("lat").asDouble()).isCloseTo(39.415, within(1e-4));
        assertThat(valencia.get("centro").get("lon").asDouble()).isCloseTo(-0.40, within(1e-4));
        assertThat(valencia.get("zoom").asDouble()).isBetween(10.0, 14.0);
        assertThat(valencia.get("cobertura").isNull()).isTrue();

        assertThat(get("/api/nucleos/40/trenes")).isEmpty();
        assertThat(get("/api/avisos")).isEmpty();

        // Errores: 404 y 400, siempre como application/problem+json.
        assertThat(problema("/api/nucleos/99/trenes", 404).get("title").asString()).isEqualTo("No encontrado");
        assertThat(problema("/api/trenes/" + C1, 404).get("detail").asString()).contains("no está en directo");
        problema("/api/estaciones/NOEXISTE", 404);
        problema("/api/lineas/40/X9", 404);
        assertThat(problema("/api/nucleos/abc/trenes", 400).get("parametro").asString()).isEqualTo("id");
        assertThat(problema("/api/estaciones", 400).get("detail").asString()).isEqualTo("Falta el parámetro «cerca».");
        assertThat(problema("/api/estaciones?cerca=hola", 400).get("parametro").asString()).isEqualTo("cerca");
        assertThat(problema("/api/estaciones?cerca=39.41,-0.40&radio=0", 400).get("parametro").asString())
                .isEqualTo("radio");
        assertThat(problema("/api/estaciones?cerca=95,0", 400).get("title").asString()).isEqualTo("Parámetro no válido");
        assertThat(problema("/api/ruta-que-no-existe", 404).get("title").asString()).isEqualTo("No encontrado");

        // El directo de un núcleo sin datos: una instantánea vacía. El de uno que no existe: 404.
        try (ClienteSse sse = new ClienteSse(directo(40), null)) {
            assertThat(sse.estado()).isEqualTo(200);
            assertThat(sse.respuesta.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            // Fase 7: que ningún proxy (Caddy, Cloudflare) lo guarde, lo comprima ni lo acumule en un búfer.
            assertThat(sse.respuesta.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-cache, no-transform");
            assertThat(sse.respuesta.headers().firstValue("X-Accel-Buffering").orElseThrow()).isEqualTo("no");
            ClienteSse.Evento primero = sse.siguiente(Duration.ofSeconds(5));
            assertThat(primero.nombre()).isEqualTo("instantanea");
            assertThat(primero.reintento()).isBetween(3000L, 6000L);
            assertThat(json.readTree(primero.datos()).get("trenes")).isEmpty();
        }
        try (ClienteSse sse = new ClienteSse(directo(99), null)) {
            assertThat(sse.estado()).isEqualTo(404);
            assertThat(sse.respuesta.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
        }
    }

    // ---------------------------------------------------------------- con datos

    @Test
    @Order(2)
    void conLaIngestaAlimentadaCadaEndpointDevuelveSuContrato() throws Exception {
        RENFE.servir("/alerts.pb", leer("20261120T092915Z_alerts.pb"));
        for (String hora : List.of("092815", "092835", "092855")) {
            servir(hora, 0);
            ingesta.ciclo();
        }

        // Núcleos: Valencia con directo y el C1 visto; Bilbao, en marcha según el horario y sin directo.
        JsonNode nucleos = get("/api/nucleos");
        JsonNode valencia = nucleos.valueStream().filter(n -> n.get("id").asInt() == 40).findFirst().orElseThrow();
        assertThat(valencia.get("estado").asString()).isEqualTo("EN_DIRECTO");
        assertThat(valencia.get("estadoTexto").asString()).isEqualTo("con directo");
        assertThat(valencia.get("trenesEnDirecto").asInt()).isPositive();
        assertThat(valencia.get("cobertura").get("porcentaje").asDouble()).isEqualTo(100.0);
        JsonNode bilbao = nucleos.valueStream().filter(n -> n.get("id").asInt() == 60).findFirst().orElseThrow();
        assertThat(bilbao.get("estado").asString()).isEqualTo("SIN_DATOS_EN_DIRECTO");
        assertThat(bilbao.get("cobertura").get("porcentaje").asDouble()).isEqualTo(0.0);
        // Núcleos sin horario pero con trenes en el feed (Rodalies, Madrid…) también salen, sin mapa.
        assertThat(nucleos.valueStream().map(n -> n.get("id").asInt())).contains(10, 51);

        // Trenes del núcleo: el C1 con horario, posición sobre el recorrido, rumbo y próxima parada con retraso.
        JsonNode trenes = get("/api/nucleos/40/trenes");
        assertThat(trenes.size()).isGreaterThan(5);
        JsonNode c1 = tren(trenes, C1);
        assertThat(c1.get("linea").asString()).isEqualTo("C1");
        assertThat(c1.get("nucleo").asInt()).isEqualTo(40);
        assertThat(c1.get("estado").asString()).isEqualTo("EN_MARCHA");
        assertThat(c1.get("sinHorario").asBoolean()).isFalse();
        assertThat(c1.get("destino").asString()).isEqualTo("Castelló");
        assertThat(c1.get("fuentePosicion").asString()).isEqualTo("RECORRIDO");
        assertThat(c1.get("lon").asDouble()).isCloseTo(-0.40, within(1e-5));
        assertThat(c1.get("rumbo").asInt()).isIn(0, 359);
        int retraso = c1.get("retrasoMin").asInt();
        JsonNode proxima = c1.get("proximaParada");
        assertThat(proxima.get("id").asString()).isIn("69104", "69105");
        OffsetDateTime programada = OffsetDateTime.parse(proxima.get("llegadaProgramada").asString());
        assertThat(programada.getOffset().getTotalSeconds()).isEqualTo(3600); // hora de Madrid en noviembre
        assertThat(OffsetDateTime.parse(proxima.get("llegadaEstimada").asString()))
                .isEqualTo(programada.plusMinutes(retraso));
        assertThat(c1.get("salida").isNull()).isTrue();
        // Un tren sin horario: con lo que trae el feed.
        JsonNode sinHorario = trenes.valueStream().filter(t -> t.get("sinHorario").asBoolean()).findFirst().orElseThrow();
        assertThat(sinHorario.get("destino").isNull()).isTrue();
        assertThat(sinHorario.get("proximaParada").isNull()).isTrue();

        // Ficha del tren: paradas con horas programada y estimada, y la evolución del retraso (la 69103, 3 min).
        JsonNode ficha = get("/api/trenes/" + C1);
        assertThat(ficha.get("origen").asString()).isEqualTo("Uno");
        assertThat(ficha.get("destino").asString()).isEqualTo("Castelló");
        assertThat(ficha.get("nombreNucleo").asString()).isEqualTo("Valencia");
        assertThat(ficha.get("fechaServicio").asString()).isEqualTo("2026-11-20");
        JsonNode paradas = ficha.get("paradas");
        assertThat(paradas).hasSize(4);
        assertThat(paradas.get(0).get("situacion").asString()).isEqualTo("PASADA");
        assertThat(paradas.get(0).get("llegadaEstimada").isNull()).isTrue();
        assertThat(paradas.get(1).get("llegadaProgramada").asString()).isEqualTo("2026-11-20T10:24:00+01:00");
        assertThat(paradas.get(1).get("retrasoFinalMin").asInt()).isEqualTo(3);
        assertThat(paradas.get(3).get("llegadaEstimada").asString()).isNotBlank();
        assertThat(paradas.valueStream().map(p -> p.get("situacion").asString())).contains("PROXIMA");
        JsonNode evolucion = ficha.get("evolucion");
        assertThat(evolucion).hasSize(1);
        assertThat(evolucion.get(0).get("parada").asString()).isEqualTo("69103");
        assertThat(evolucion.get(0).get("nombre").asString()).isEqualTo("Dos");
        assertThat(evolucion.get(0).get("retrasoMin").asInt()).isEqualTo(3);
        assertThat(evolucion.get(0).get("inferido").asBoolean()).isFalse();
        assertThat(evolucion.get(0).get("llegadaProgramada").asString()).isEqualTo("2026-11-20T10:24:00+01:00");

        // Estación: líneas, próximos trenes (el C1 en directo con retraso y el de Bilbao sin directo) y avisos.
        JsonNode castello = get("/api/estaciones/69105");
        assertThat(castello.get("nombre").asString()).isEqualTo("Castelló");
        assertThat(castello.get("lineas")).extracting(l -> l.get("nucleo").asInt() + "/" + l.get("linea").asString())
                .containsExactly("40/C1", "60/C1");
        assertThat(castello.get("lineas").get(0).get("color").asString()).isEqualTo("#E2001A");
        JsonNode proximos = castello.get("proximos");
        assertThat(proximos).extracting(p -> p.get("linea").asString() + "/" + p.get("nucleo").asInt() + "/"
                + p.get("enDirecto").asBoolean()).containsExactly("C1/40/true", "C1/60/false");
        JsonNode proximoC1 = proximos.get(0);
        assertThat(proximoC1.get("id").asString()).isEqualTo(C1);
        assertThat(proximoC1.get("salidaProgramada").asString()).isEqualTo("2026-11-20T10:32:00+01:00");
        assertThat(OffsetDateTime.parse(proximoC1.get("salidaEstimada").asString()))
                .isEqualTo(OffsetDateTime.parse("2026-11-20T10:32:00+01:00").plusMinutes(proximoC1.get("retrasoMin").asInt()));
        assertThat(proximos.get(1).get("retrasoMin").isNull()).isTrue();
        assertThat(proximos.get(1).get("destino").asString()).isEqualTo("Castelló");
        // La primera estación del C1 ya la ha dejado atrás: no sale como próximo.
        assertThat(get("/api/estaciones/69102").get("proximos")).isEmpty();

        // Cercanas, con PostGIS: de la más cercana a la más lejana, dentro del radio.
        JsonNode cercanas = get("/api/estaciones?cerca=39.41,-0.40&radio=1500");
        assertThat(cercanas).extracting(e -> e.get("id").asString()).containsExactly("69103", "69102", "69104")
                .doesNotContain("69200");
        assertThat(cercanas.get(0).get("distanciaM").asInt()).isZero();
        assertThat(cercanas.get(1).get("distanciaM").asInt()).isBetween(1100, 1120);
        assertThat(get("/api/estaciones?cerca=39.41,-0.40&radio=1500&limite=1")).hasSize(1);
        assertThat(get("/api/estaciones?cerca=40.0,3.0")).isEmpty();

        // Línea: recorrido en GeoJSON, paradas en orden y avisos; sin distinguir mayúsculas.
        JsonNode linea = get("/api/lineas/40/c1");
        assertThat(linea.get("linea").asString()).isEqualTo("C1");
        assertThat(linea.get("nombreLargo").asString()).isEqualTo("València Nord - Castelló");
        assertThat(linea.get("rutas")).extracting(JsonNode::asString).containsExactly("40T0001C1");
        assertThat(linea.get("recorrido").get("type").asString()).isEqualTo("FeatureCollection");
        JsonNode geometria = linea.get("recorrido").get("features").get(0).get("geometry");
        assertThat(geometria.get("type").asString()).isEqualTo("LineString");
        assertThat(geometria.get("coordinates")).hasSize(2);
        assertThat(linea.get("paradas")).extracting(p -> p.get("id").asString())
                .containsExactly("69102", "69103", "69104", "69105");

        // Avisos de la muestra real: todos, y filtrados por núcleo (cada aviso, entero).
        JsonNode avisos = get("/api/avisos");
        assertThat(avisos.size()).isGreaterThan(10);
        assertThat(avisos.get(0).get("texto").asString()).isNotBlank();
        JsonNode deRodalies = get("/api/avisos?nucleo=51");
        assertThat(deRodalies.size()).isBetween(1, avisos.size() - 1);
        assertThat(deRodalies).allSatisfy(a -> assertThat(a.get("nucleos").valueStream().map(JsonNode::asInt))
                .contains(51));
        String estacion = jdbc.sql("SELECT stop_id FROM avisos WHERE stop_id IS NOT NULL LIMIT 1")
                .query(String.class).optional().orElse(null);
        if (estacion != null) {
            assertThat(get("/api/avisos?estacion=" + estacion)).allSatisfy(a -> assertThat(
                    a.get("estaciones").valueStream().map(JsonNode::asString)).contains(estacion));
        }
    }

    @Test
    @Order(3)
    void cabecerasDeCacheEtagYCors() {
        // Lo que no cambia: caché pública y ETag (un 304 sin cuerpo si no ha cambiado).
        var linea = http().get().uri("/api/lineas/40/C1").exchange().expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, "max-age=60, public")
                .returnResult(String.class);
        String etag = linea.getResponseHeaders().getETag();
        assertThat(etag).isNotBlank();
        http().get().uri("/api/lineas/40/C1").header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isNotModified();
        http().get().uri("/api/estaciones?cerca=39.41,-0.40").exchange().expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                .expectHeader().exists(HttpHeaders.ETAG);
        // Lo que está en directo: unos segundos.
        http().get().uri("/api/nucleos/40/trenes").exchange().expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, "max-age=5, public");

        // CORS: la web y el Vite de local sí; otro origen, no.
        for (String origen : List.of("https://trainwatch.ivanchesa.com", "http://localhost:5173")) {
            http().options().uri("/api/nucleos").header(HttpHeaders.ORIGIN, origen)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET").exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origen);
            http().get().uri("/api/nucleos").header(HttpHeaders.ORIGIN, origen).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origen);
        }
        http().options().uri("/api/directo?nucleo=40").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "last-event-id").exchange().expectStatus().isOk();
        http().options().uri("/api/nucleos").header(HttpHeaders.ORIGIN, "https://otra-web.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET").exchange().expectStatus().isForbidden();
        http().get().uri("/api/nucleos").header(HttpHeaders.ORIGIN, "https://otra-web.example").exchange()
                .expectStatus().isForbidden();
        // Solo lectura: nada de POST en la API pública.
        http().options().uri("/api/nucleos").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST").exchange().expectStatus().isForbidden();
    }

    @Test
    @Order(4)
    void openApiEnApiDocsConLosEndpointsYSusErrores() {
        JsonNode docs = get("/api/docs");
        assertThat(docs.get("openapi").asString()).startsWith("3.");
        assertThat(docs.get("info").get("title").asString()).isEqualTo("TrainWatch API");
        assertThat(docs.get("info").get("description").asString()).contains("no oficial", "Renfe (CC BY 4.0)");
        assertThat(docs.get("paths").propertyNames()).contains("/api/nucleos", "/api/nucleos/{id}/trenes",
                "/api/trenes/{id}", "/api/estaciones", "/api/estaciones/{id}", "/api/lineas/{nucleo}/{linea}",
                "/api/avisos", "/api/directo", "/api/directo/estado");
        JsonNode ficha = docs.get("paths").get("/api/trenes/{id}").get("get");
        assertThat(ficha.get("operationId").asString()).isEqualTo("fichaTren");
        assertThat(ficha.get("responses").get("404").get("content").propertyNames()).contains("application/problem+json");
        assertThat(docs.get("paths").get("/api/directo").get("get").get("responses").propertyNames()).contains("503");
        // Los ejemplos de docs/api/ cuelgan del 200 de cada operación.
        JsonNode ok = docs.get("paths").get("/api/nucleos").get("get").get("responses").get("200").get("content");
        assertThat(ok.valueStream().map(tipo -> tipo.get("examples").get("ejemplo").get("value").get(0).get("nombre")
                .asString())).contains("Madrid");
        // La interfaz de Swagger.
        http().get().uri("/api/docs/ui").exchange().expectStatus().is3xxRedirection();
    }

    @Test
    @Order(4)
    void unErrorInternoTambienEsProblemDetailSinDetallesInternos() {
        jdbc.sql("ALTER TABLE avisos RENAME TO avisos_caida").update();
        try {
            JsonNode p = problema("/api/avisos", 500);
            assertThat(p.get("title").asString()).isEqualTo("Error interno");
            assertThat(p.toString()).doesNotContain("avisos_caida", "SQL", "Exception");
        } finally {
            jdbc.sql("ALTER TABLE avisos_caida RENAME TO avisos").update();
        }
    }

    // ---------------------------------------------------------------- directo (SSE)

    @Test
    @Order(5)
    void elDirectoMandaLaInstantaneaYDespuesSoloLosCambios() throws Exception {
        try (ClienteSse sse = new ClienteSse(directo(40), null)) {
            ClienteSse.Evento primero = sse.siguiente(Duration.ofSeconds(5));
            assertThat(primero.nombre()).isEqualTo("instantanea");
            JsonNode instantanea = json.readTree(primero.datos());
            long secuencia = instantanea.get("secuencia").asLong();
            assertThat(primero.id()).isEqualTo(String.valueOf(secuencia));
            assertThat(instantanea.get("nucleo").asInt()).isEqualTo(40);
            // La misma foto que la API REST, con las tildes bien (UTF-8 de punta a punta).
            assertThat(instantanea.get("trenes").size()).isEqualTo(get("/api/nucleos/40/trenes").size());
            assertThat(tren(instantanea.get("trenes"), C1).get("destino").asString()).isEqualTo("Castelló");

            // Una muestra nueva: solo los cambios, con la secuencia siguiente.
            servir("092835", 100);
            ingesta.ciclo();
            ClienteSse.Evento evento = sse.siguiente("cambios", Duration.ofSeconds(5));
            assertThat(evento).isNotNull();
            JsonNode cambios = json.readTree(evento.datos());
            assertThat(cambios.get("secuencia").asLong()).isEqualTo(secuencia + 1);
            assertThat(evento.id()).isEqualTo(String.valueOf(secuencia + 1));
            int tocados = cambios.get("altas").size() + cambios.get("cambios").size() + cambios.get("bajas").size();
            assertThat(tocados).isPositive().isLessThan(instantanea.get("trenes").size() + 5);
            assertThat(cambios.get("ultimoDato").asString()).isNotBlank();

            // Sin muestra nueva y con el mismo reloj, nada cambia: ningún evento de cambios.
            ingesta.ciclo();
            assertThat(sse.siguiente("cambios", Duration.ofMillis(800))).isNull();

            // El latido (cada 1 s en esta prueba): con la antigüedad del último dato.
            ClienteSse.Evento latido = sse.siguiente("latido", Duration.ofSeconds(4));
            assertThat(latido).isNotNull();
            assertThat(latido.id()).isNull();
            assertThat(json.readTree(latido.datos()).get("antiguedadS").asLong()).isEqualTo(5);

            // Al reconectarse con Last-Event-ID se recibe una instantánea nueva, al día.
            try (ClienteSse otra = new ClienteSse(directo(40), String.valueOf(secuencia))) {
                ClienteSse.Evento nueva = otra.siguiente(Duration.ofSeconds(5));
                assertThat(nueva.nombre()).isEqualTo("instantanea");
                assertThat(json.readTree(nueva.datos()).get("secuencia").asLong()).isEqualTo(secuencia + 1);
            }
        }
    }

    @Test
    @Order(6)
    void pasadoElMaximoDeConexionesSeRechazaConUn503YRetryAfter() throws Exception {
        esperarConexiones(0);
        List<ClienteSse> abiertas = new ArrayList<>();
        try {
            for (int i = 0; i < MAXIMO_CONEXIONES; i++) {
                ClienteSse c = new ClienteSse(directo(40), null);
                assertThat(c.estado()).isEqualTo(200);
                abiertas.add(c);
            }
            esperarConexiones(MAXIMO_CONEXIONES);
            try (ClienteSse sobra = new ClienteSse(directo(40), null)) {
                assertThat(sobra.estado()).isEqualTo(503);
                assertThat(sobra.respuesta.headers().firstValue("Retry-After")).contains("30");
                assertThat(sobra.respuesta.headers().firstValue("Content-Type").orElseThrow())
                        .startsWith("application/problem+json");
                JsonNode p = json.readTree(sobra.respuesta.body().readAllBytes());
                assertThat(p.get("title").asString()).isEqualTo("Directo completo");
            }
        } finally {
            abiertas.forEach(ClienteSse::close);
        }
        // Al cerrarse, las conexiones se liberan (el latido descubre las que se han ido) y se puede volver a entrar.
        esperarConexiones(0);
        try (ClienteSse otra = new ClienteSse(directo(40), null)) {
            assertThat(otra.estado()).isEqualTo(200);
        }
    }

    void esperarConexiones(int n) throws InterruptedException {
        long fin = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (difusor.conexiones() != n && System.nanoTime() < fin) {
            Thread.sleep(50);
        }
        assertThat(difusor.conexiones()).isEqualTo(n);
    }
}
