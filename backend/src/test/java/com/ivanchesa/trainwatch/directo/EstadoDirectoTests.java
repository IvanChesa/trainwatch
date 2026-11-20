package com.ivanchesa.trainwatch.directo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.ivanchesa.trainwatch.directo.EstadoDirecto.ResultadoPosiciones;
import com.ivanchesa.trainwatch.directo.EstadoDirecto.ResultadoRetrasos;

/**
 * El estado en memoria sin base de datos: estados, tolerancia a muestras incompletas, cierre del retraso final por
 * parada, posición estimada y rumbo, y las muestras reales de {@code src/test/resources/gtfsrt}.
 *
 * <p>El viaje de prueba {@code 51T1} (R4, núcleo 51, el 20-11-2026): A (salida 10:00), B (llegada 10:10, salida
 * 10:11) y C (llegada 10:20), sobre un recorrido recto de oeste a este por la latitud 41,6, de la longitud 0,60 a la
 * 0,62. A está en la fracción 0, B en 0,5 y C en 1.
 */
class EstadoDirectoTests {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate DIA = LocalDate.of(2026, 11, 20);
    static final Recorrido RECTO = new Recorrido("51_R4", new double[] { 0.60, 0.61, 0.62 },
            new double[] { 41.6, 41.6, 41.6 }, false);

    /**
     * Horario falso: un mapa de viajes. {@code fallan}: viajes cuya búsqueda falla como si la base de datos no
     * contestara; {@code revientan}: viajes cuya búsqueda lanza un error inesperado (no una excepción de la base de
     * datos), para probar que una muestra que falla a mitad no deja el estado a medias.
     */
    static class HorarioFalso implements FuenteHorario {
        final Map<String, Viaje> viajes = new ConcurrentHashMap<>();
        final Set<String> fallan = ConcurrentHashMap.newKeySet();
        final Set<String> revientan = ConcurrentHashMap.newKeySet();
        final AtomicInteger llamadasFallidas = new AtomicInteger();

        @Override
        public boolean enCache(String tripId) {
            // Como la caché de verdad, que guarda también los que no existen (SPECIAL_...): todo menos los que fallan.
            return !fallan.contains(tripId) && !revientan.contains(tripId);
        }

        @Override
        public Viaje viaje(String tripId) {
            if (fallan.contains(tripId)) {
                llamadasFallidas.incrementAndGet();
                throw new IllegalStateException("La base de datos no contesta (" + tripId + ")");
            }
            if (revientan.contains(tripId)) {
                throw new AssertionError("Fallo inesperado a mitad de la muestra (" + tripId + ")");
            }
            return viajes.get(tripId);
        }

        @Override
        public Set<Integer> nucleos() {
            return Set.of(51);
        }

        @Override
        public Map<Integer, Set<String>> enMarchaSegunHorario(Instant ahora) {
            return Map.of();
        }
    }

    HorarioFalso horario;
    EstadoDirecto estado;
    long cabecera;

    @BeforeEach
    void preparar() {
        horario = new HorarioFalso();
        horario.viajes.put("51T1", viaje("51T1", RECTO, 1, 0.0, 0.5, 1.0));
        estado = new EstadoDirecto(horario, MADRID, 3, 0.8);
        cabecera = hora(9, 0).getEpochSecond();
    }

    static Viaje viaje(String id, Recorrido recorrido, int sentido, double a, double b, double c) {
        return new Viaje(id, "51T0004R4", "R4", 51, "C", DIA, List.of(
                new ParadaViaje(1, "A", "A", 41.6, 0.60, s(10, 0), s(10, 0), recorrido == null ? null : a),
                new ParadaViaje(2, "B", "B", 41.6, 0.61, s(10, 10), s(10, 11), recorrido == null ? null : b),
                new ParadaViaje(3, "C", "C", 41.6, 0.62, s(10, 20), s(10, 20), recorrido == null ? null : c)),
                recorrido, sentido);
    }

    static int s(int h, int m) {
        return h * 3600 + m * 60;
    }

    static Instant hora(int h, int m) {
        return hora(h, m, 0);
    }

    static Instant hora(int h, int m, int seg) {
        return ZonedDateTime.of(DIA.atTime(h, m), MADRID).toInstant().plusSeconds(seg);
    }

    // ---------------------------------------------------------------- muestras sintéticas

    FeedMessage posiciones(Instant ahora, List<VehiclePosition> trenes) {
        cabecera = Math.max(cabecera + 1, ahora.getEpochSecond());
        FeedMessage.Builder m = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(cabecera));
        int i = 0;
        for (VehiclePosition v : trenes) {
            m.addEntity(FeedEntity.newBuilder().setId("VP" + i++).setVehicle(v));
        }
        return m.build();
    }

    FeedMessage retrasos(Instant ahora, List<TripUpdate> viajes) {
        cabecera = Math.max(cabecera + 1, ahora.getEpochSecond());
        FeedMessage.Builder m = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(cabecera));
        int i = 0;
        for (TripUpdate t : viajes) {
            m.addEntity(FeedEntity.newBuilder().setId("TU" + i++).setTripUpdate(t));
        }
        return m.build();
    }

    static VehiclePosition vp(String trip, String parada, Double lat, Double lon) {
        VehiclePosition.Builder v = VehiclePosition.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId(trip))
                .setVehicle(VehicleDescriptor.newBuilder().setId("v" + trip).setLabel("R4-" + trip))
                .setCurrentStatus(VehiclePosition.VehicleStopStatus.STOPPED_AT);
        if (parada != null) {
            v.setStopId(parada);
        }
        if (lat != null) {
            v.setPosition(Position.newBuilder().setLatitude(lat.floatValue()).setLongitude(lon.floatValue()));
        }
        return v.build();
    }

    static TripUpdate tu(String trip, String parada, int retraso) {
        return TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId(trip))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId(parada)
                        .setArrival(StopTimeEvent.newBuilder().setDelay(retraso)))
                .build();
    }

    static TripUpdate cancelado(String trip) {
        return TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId(trip)
                .setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED)).build();
    }

    /** Trenes de relleno (sin horario) para que las muestras no parezcan incompletas al quitar uno. */
    static List<VehiclePosition> relleno(int n) {
        List<VehiclePosition> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(vp("SPECIAL_10_" + i, "X", 40.4, -3.7));
        }
        return l;
    }

    static List<TripUpdate> rellenoTu(int n) {
        List<TripUpdate> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(tu("SPECIAL_10_" + i, "X", 0));
        }
        return l;
    }

    static <T> List<T> con(List<T> lista, T extra) {
        List<T> l = new ArrayList<>(lista);
        l.add(extra);
        return l;
    }

    // ---------------------------------------------------------------- estados

    @Nested
    class Estados {

        @Test
        void enOrigenAntesDeSalirYEnMarchaDespues() {
            estado.procesarPosiciones(posiciones(hora(9, 55), List.of(vp("51T1", "A", 41.6, 0.60))), hora(9, 55));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.EN_ORIGEN);

            estado.procesarPosiciones(posiciones(hora(10, 1), List.of(vp("51T1", "A", 41.6, 0.60))), hora(10, 1));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.EN_MARCHA);
        }

        @Test
        void conRetrasoSigueEnOrigenHastaSuSalidaReal() {
            estado.procesarRetrasos(retrasos(hora(10, 1), List.of(tu("51T1", "B", 300))), hora(10, 1));
            estado.procesarPosiciones(posiciones(hora(10, 1), List.of(vp("51T1", "A", 41.6, 0.60))), hora(10, 1));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.EN_ORIGEN);
            assertThat(estado.tren("51T1").retrasoS()).isEqualTo(300);
        }

        @Test
        void terminadoEnLaUltimaParada() {
            estado.procesarPosiciones(posiciones(hora(10, 21), List.of(vp("51T1", "C", 41.6, 0.62))), hora(10, 21));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.TERMINADO);
        }

        @Test
        void canceladoSoloConCanceledExplicito() {
            estado.procesarRetrasos(retrasos(hora(10, 5), List.of(cancelado("51T1"))), hora(10, 5));
            assertThat(estado.cancelados()).containsExactly("51T1");
            assertThat(estado.trenesPorEstado()).containsEntry(EstadoTren.CANCELADO, 1);

            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("51T1", "A", 41.6, 0.60))), hora(10, 5));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.CANCELADO);
            assertThat(estado.trenesPorEstado()).containsEntry(EstadoTren.CANCELADO, 1);
        }

        @Test
        void unTrenQueDesapareceNoEsUnCancelado() {
            estado.procesarPosiciones(posiciones(hora(10, 5), con(relleno(9), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5));
            for (int i = 1; i <= 3; i++) {
                estado.procesarPosiciones(posiciones(hora(10, 5, i * 20), relleno(9)), hora(10, 5, i * 20));
            }
            assertThat(estado.tren("51T1")).isNull();
            assertThat(estado.trenesPorEstado()).containsEntry(EstadoTren.CANCELADO, 0);
        }

        @Test
        void sinViajeEnElHorarioSeGuardaConLoQueTraiga() {
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("SPECIAL_10_91886C7", "17000", 40.4, -3.7))),
                    hora(10, 5));
            Tren t = estado.tren("SPECIAL_10_91886C7");
            assertThat(t.sinHorario()).isTrue();
            assertThat(t.estado()).isEqualTo(EstadoTren.EN_MARCHA);
            assertThat(t.nucleo()).isEqualTo(10);
            assertThat(t.linea()).isEqualTo("R4");
            assertThat(t.estimada().fuente()).isEqualTo(PosicionEstimada.Fuente.FEED);
            assertThat(t.estimada().lat()).isCloseTo(40.4, within(1e-5));
        }

        @Test
        void sinCoordenadasNiParadaConStop00000() {
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("SPECIAL_30_1", "00000", null, null))),
                    hora(10, 5));
            Tren t = estado.tren("SPECIAL_30_1");
            assertThat(t.stopId()).isNull();
            assertThat(t.lat()).isNull();
            assertThat(t.estimada()).isNull();
        }
    }

    // ---------------------------------------------------------------- tolerancia

    @Nested
    class Tolerancia {

        @Test
        void desapareceSoloTrasFaltarEnTresMuestrasNuevasSeguidas() {
            estado.procesarPosiciones(posiciones(hora(10, 5), con(relleno(9), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5));
            estado.procesarPosiciones(posiciones(hora(10, 5, 20), relleno(9)), hora(10, 5, 20));
            estado.procesarPosiciones(posiciones(hora(10, 5, 40), relleno(9)), hora(10, 5, 40));
            assertThat(estado.tren("51T1").ausencias()).isEqualTo(2);

            // Una muestra repetida (misma cabecera) no es nueva: no cuenta.
            long previa = cabecera;
            FeedMessage repetida = FeedMessage.newBuilder(posiciones(hora(10, 5, 40), relleno(9)))
                    .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(previa)).build();
            assertThat(estado.procesarPosiciones(repetida, hora(10, 5, 50)).nueva()).isFalse();
            assertThat(estado.tren("51T1")).isNotNull();

            // Si vuelve a aparecer, las ausencias se reinician.
            estado.procesarPosiciones(posiciones(hora(10, 6), con(relleno(9), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 6));
            assertThat(estado.tren("51T1").ausencias()).isZero();

            for (int i = 1; i <= 2; i++) {
                estado.procesarPosiciones(posiciones(hora(10, 6, i * 20), relleno(9)), hora(10, 6, i * 20));
            }
            assertThat(estado.tren("51T1")).isNotNull();
            ResultadoPosiciones r = estado.procesarPosiciones(posiciones(hora(10, 7), relleno(9)), hora(10, 7));
            assertThat(r.desaparecidos()).isEqualTo(1);
            assertThat(estado.tren("51T1")).isNull();
        }

        @Test
        void unaMuestraIncompletaNoCuentaComoDesaparicion() {
            estado.procesarPosiciones(posiciones(hora(10, 5), relleno(30)), hora(10, 5));
            // Un tercio menos, como la de las 10:29:15 de la fase 0: incompleta.
            ResultadoPosiciones r = estado.procesarPosiciones(posiciones(hora(10, 5, 20), relleno(20)), hora(10, 5, 20));
            assertThat(r.completa()).isFalse();
            assertThat(r.desaparecidos()).isZero();
            assertThat(estado.trenes()).hasSize(30);
            assertThat(estado.tren("SPECIAL_10_25").ausencias()).isZero();

            // Vuelve la muestra completa: nadie ha desaparecido.
            assertThat(estado.procesarPosiciones(posiciones(hora(10, 5, 40), relleno(30)), hora(10, 5, 40)).completa())
                    .isTrue();
            assertThat(estado.trenes()).hasSize(30);
        }

        @Test
        void tresIncompletasSeguidasSonElNuevoNivel() {
            estado.procesarPosiciones(posiciones(hora(10, 5), relleno(30)), hora(10, 5));
            List<Boolean> completas = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                completas.add(estado.procesarPosiciones(posiciones(hora(10, 5, i * 5), relleno(20)), hora(10, 5, i * 5))
                        .completa());
            }
            // Dos incompletas; la tercera se acepta como nivel nuevo y desde ahí cuentan las ausencias (3, 4 y 5).
            assertThat(completas).containsExactly(false, false, true, true, true, true);
            assertThat(estado.trenes()).hasSize(20);
        }
    }

    // ---------------------------------------------------------------- retraso final por parada

    @Nested
    class RetrasoFinalPorParada {

        @Test
        void alPasarALaSiguienteSeCierraConElUltimoRetrasoConocido() {
            assertThat(estado.procesarRetrasos(retrasos(hora(10, 2), List.of(tu("51T1", "B", 60))), hora(10, 2))
                    .cerrados()).isEmpty();
            assertThat(estado.procesarRetrasos(retrasos(hora(10, 8), List.of(tu("51T1", "B", 120))), hora(10, 8))
                    .cerrados()).isEmpty();
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 13), List.of(tu("51T1", "C", 180))),
                    hora(10, 13));
            assertThat(r.cerrados()).singleElement().satisfies(f -> {
                assertThat(f.tripId()).isEqualTo("51T1");
                assertThat(f.stopId()).isEqualTo("B");
                assertThat(f.stopSequence()).isEqualTo(2);
                assertThat(f.minutos()).isEqualTo(2); // el último conocido para B, no el nuevo (3) de C
                assertThat(f.fechaServicio()).isEqualTo(DIA);
                assertThat(f.linea()).isEqualTo("R4");
                assertThat(f.nucleo()).isEqualTo(51);
            });
        }

        @Test
        void laUltimaParadaSeCierraCuandoElViajeDesaparece() {
            estado.procesarRetrasos(retrasos(hora(10, 15), con(rellenoTu(9), tu("51T1", "C", 240))), hora(10, 15));
            List<RetrasoFinal> cerrados = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                cerrados.addAll(estado.procesarRetrasos(retrasos(hora(10, 21, i * 20), rellenoTu(9)),
                        hora(10, 21, i * 20)).cerrados());
            }
            assertThat(cerrados).singleElement().satisfies(f -> {
                assertThat(f.stopId()).isEqualTo("C");
                assertThat(f.minutos()).isEqualTo(4);
            });
        }

        @Test
        void unViajeQueDesapareceAMitadNoCierraNada() {
            estado.procesarRetrasos(retrasos(hora(10, 5), con(rellenoTu(9), tu("51T1", "B", 60))), hora(10, 5));
            List<RetrasoFinal> cerrados = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                cerrados.addAll(estado.procesarRetrasos(retrasos(hora(10, 6, i * 10), rellenoTu(9)),
                        hora(10, 6, i * 10)).cerrados());
            }
            assertThat(cerrados).isEmpty();
        }

        @Test
        void tresMuestrasIncompletasNoCierranLaUltimaParadaYTresCompletasSi() {
            // Fase 3b: con una sola ausencia tampoco cerraría una muestra completa (hacen falta 3); la prueba tiene
            // que llegar a 3 para distinguir los dos casos.
            estado.procesarRetrasos(retrasos(hora(10, 15), con(rellenoTu(29), tu("51T1", "C", 240))), hora(10, 15));
            List<ResultadoRetrasos> incompletas = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                incompletas.add(estado.procesarRetrasos(retrasos(hora(10, 16, i * 20), rellenoTu(15)),
                        hora(10, 16, i * 20)));
            }
            // Dos incompletas y la tercera, aceptada como nivel nuevo: es la primera ausencia que cuenta.
            assertThat(incompletas).extracting(ResultadoRetrasos::completa).containsExactly(false, false, true);
            assertThat(incompletas).allSatisfy(r -> assertThat(r.cerrados()).isEmpty());

            EstadoDirecto otro = new EstadoDirecto(horario, MADRID, 3, 0.8);
            otro.procesarRetrasos(retrasos(hora(10, 15), con(rellenoTu(29), tu("51T1", "C", 240))), hora(10, 15));
            List<RetrasoFinal> cerrados = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ResultadoRetrasos r = otro.procesarRetrasos(retrasos(hora(10, 16, i * 20), rellenoTu(29)),
                        hora(10, 16, i * 20));
                assertThat(r.completa()).isTrue();
                cerrados.addAll(r.cerrados());
            }
            assertThat(cerrados).singleElement().satisfies(f -> assertThat(f.stopId()).isEqualTo("C"));
        }

        @Test
        void siVuelveAtrasNoCierraYUnCanceladoDescartaLoPendiente() {
            estado.procesarRetrasos(retrasos(hora(10, 12), List.of(tu("51T1", "C", 60))), hora(10, 12));
            ResultadoRetrasos atras = estado.procesarRetrasos(retrasos(hora(10, 13), List.of(tu("51T1", "B", 60))),
                    hora(10, 13));
            assertThat(atras.cerrados()).isEmpty();
            assertThat(atras.sinCerrar()).isEqualTo(1);

            ResultadoRetrasos cancelado = estado.procesarRetrasos(retrasos(hora(10, 14), List.of(cancelado("51T1"))),
                    hora(10, 14));
            assertThat(cancelado.cerrados()).isEmpty();
            assertThat(estado.procesarRetrasos(retrasos(hora(10, 15), List.of(tu("51T1", "C", 60))), hora(10, 15))
                    .cerrados()).isEmpty();
        }

        @Test
        void sinHorarioNoHaySecuenciaYNoSeGuarda() {
            estado.procesarRetrasos(retrasos(hora(10, 5), List.of(tu("SPECIAL_10_1", "X", 60))), hora(10, 5));
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 6), List.of(tu("SPECIAL_10_1", "Y", 60))),
                    hora(10, 6));
            assertThat(r.cerrados()).isEmpty();
            assertThat(r.sinCerrar()).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- posición estimada

    @Nested
    class PosicionEstimadaYRumbo {

        @Test
        void entreDosParadasSegunLaHoraYElRecorrido() {
            // Sale de A a las 10:00 y llega a B a las 10:10: a las 10:05:30 lleva el 55 % del tramo (0 → 0,5).
            estado.procesarPosiciones(posiciones(hora(10, 5, 30), List.of(vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5, 30));
            PosicionEstimada p = estado.tren("51T1").estimada();
            assertThat(p.fuente()).isEqualTo(PosicionEstimada.Fuente.RECORRIDO);
            assertThat(p.fraccion()).isCloseTo(0.275, within(1e-9));
            assertThat(p.lon()).isCloseTo(0.6055, within(1e-9));
            assertThat(p.lat()).isCloseTo(41.6, within(1e-9));
            assertThat(p.rumbo()).isCloseTo(90, within(0.1)); // hacia el este
        }

        @Test
        void elRetrasoRetrasaLaPosicionYLaHoraLaAvanzaHastaLaSiguiente() {
            estado.procesarRetrasos(retrasos(hora(10, 5, 30), List.of(tu("51T1", "B", 120))), hora(10, 5, 30));
            estado.procesarPosiciones(posiciones(hora(10, 5, 30), List.of(vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5, 30));
            // Con 2 min: sale 10:02, llega 10:12; a las 10:05:30, el 35 % → 0,175.
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.175, within(1e-9));

            // Sin muestra nueva, la posición avanza con la hora, pero no pasa de B aunque el feed no llegue.
            estado.reestimar(hora(10, 9));
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.35, within(1e-9));
            estado.reestimar(hora(10, 30));
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.5, within(1e-9));
        }

        @Test
        void unaPosicionNuevaDelFeedCorrige() {
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("51T1", "A", 41.6, 0.60))), hora(10, 5));
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.25, within(1e-9));
            // El tren va adelantado: a las 10:08 el feed ya lo pone en B.
            estado.procesarPosiciones(posiciones(hora(10, 8), List.of(vp("51T1", "B", 41.6, 0.61))), hora(10, 8));
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.5, within(1e-9));
        }

        @Test
        void alRevesDelDibujoElRumboSeGira() {
            horario.viajes.put("51T2", viaje("51T2", RECTO, -1, 0.0, 0.5, 1.0));
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("51T2", "A", 41.6, 0.62))), hora(10, 5));
            PosicionEstimada p = estado.tren("51T2").estimada();
            // Avance 0,25 en su sentido = fracción 0,75 del dibujo, yendo hacia el oeste.
            assertThat(p.fraccion()).isCloseTo(0.75, within(1e-9));
            assertThat(p.lon()).isCloseTo(0.615, within(1e-9));
            assertThat(p.rumbo()).isCloseTo(270, within(0.1));
        }

        @Test
        void enUnRecorridoCerradoDaLaVuelta() {
            Recorrido circular = new Recorrido("30_C4", new double[] { 0.60, 0.62, 0.62, 0.60, 0.60 },
                    new double[] { 41.60, 41.60, 41.62, 41.62, 41.60 }, true);
            horario.viajes.put("30T1", viaje("30T1", circular, 1, 0.9, 0.1, 0.3));
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("30T1", "A", 41.6, 0.60))), hora(10, 5));
            // De 0,9 a 0,1 hay 0,2; a mitad de tramo, 0,9 + 0,1 = 1,0 → 0,0: el principio (y final) del recorrido.
            PosicionEstimada p = estado.tren("30T1").estimada();
            assertThat(p.lat()).isCloseTo(41.60, within(1e-6));
            assertThat(p.lon()).isCloseTo(0.60, within(1e-6));
            // A un cuarto del tramo: 0,95, en el último lado (de 0,60/41,62 hacia 0,60/41,60), yendo al sur.
            estado.reestimar(hora(10, 2, 30));
            assertThat(estado.tren("30T1").estimada().fraccion()).isCloseTo(0.95, within(1e-9));
            assertThat(estado.tren("30T1").estimada().rumbo()).isCloseTo(180, within(0.1));
        }

        @Test
        void sinRecorridoUsaElFeedYElRumboDelMovimiento() {
            horario.viajes.put("51T3", viaje("51T3", null, 1, 0, 0, 0));
            estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("51T3", "A", 41.60, 0.60))), hora(10, 5));
            assertThat(estado.tren("51T3").estimada().rumbo()).isNull();
            estado.procesarPosiciones(posiciones(hora(10, 6), List.of(vp("51T3", "A", 41.61, 0.60))), hora(10, 6));
            PosicionEstimada p = estado.tren("51T3").estimada();
            assertThat(p.fuente()).isEqualTo(PosicionEstimada.Fuente.FEED);
            assertThat(p.lat()).isCloseTo(41.61, within(1e-5));
            assertThat(p.fraccion()).isNull();
            assertThat(p.rumbo()).isCloseTo(0, within(0.1)); // hacia el norte
        }

        @Test
        void soloCambiosVanAPosiciones() {
            assertThat(estado.procesarPosiciones(posiciones(hora(10, 5), List.of(vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5)).cambios()).hasSize(1);
            assertThat(estado.procesarPosiciones(posiciones(hora(10, 5, 20), List.of(vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5, 20)).cambios()).isEmpty();
            assertThat(estado.procesarPosiciones(posiciones(hora(10, 5, 40), List.of(vp("51T1", "B", 41.6, 0.61))),
                    hora(10, 5, 40)).cambios()).hasSize(1);
        }
    }

    // ---------------------------------------------------------------- lo que encontró la revisión

    @Nested
    class CasosDeLaRevision {

        @Test
        void sinMuestraNuevaUnTrenEnOrigenPasaAEnMarchaConLaHora() {
            estado.procesarPosiciones(posiciones(hora(9, 59), List.of(vp("51T1", "A", 41.6, 0.60))), hora(9, 59));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.EN_ORIGEN);
            estado.reestimar(hora(10, 5));
            assertThat(estado.tren("51T1").estado()).isEqualTo(EstadoTren.EN_MARCHA);
            assertThat(estado.tren("51T1").estimada().fraccion()).isCloseTo(0.25, within(1e-9));
        }

        @Test
        void conStop00000ConservaLaUltimaParadaConocida() {
            estado.procesarPosiciones(posiciones(hora(10, 21), List.of(vp("51T1", "C", 41.6, 0.62))), hora(10, 21));
            estado.procesarPosiciones(posiciones(hora(10, 22), List.of(vp("51T1", "00000", null, null))), hora(10, 22));
            Tren t = estado.tren("51T1");
            assertThat(t.stopId()).isEqualTo("C");
            assertThat(t.estado()).isEqualTo(EstadoTren.TERMINADO);
        }

        @Test
        void siElRelojDelFeedRetrocedeSeAceptaTrasTresMuestras() {
            estado.procesarPosiciones(posiciones(hora(10, 5), relleno(10)), hora(10, 5));
            long atras = cabecera - 3600;
            List<Boolean> nuevas = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                FeedMessage m = FeedMessage.newBuilder(posiciones(hora(10, 5), relleno(10)))
                        .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(atras + i)).build();
                cabecera = atras + i;
                nuevas.add(estado.procesarPosiciones(m, hora(10, 6)).nueva());
            }
            assertThat(nuevas).containsExactly(false, false, true);
        }

        @Test
        void lasHorasDelHorarioCuentanDesdeMediodiaMenos12h() {
            // 29-03-2026: a las 02:00 se adelanta a las 03:00. Las 03:00 del horario (10.800 s) son las 03:00 reales.
            Instant tres = Viaje.instante(LocalDate.of(2026, 3, 29), 3 * 3600, MADRID);
            assertThat(tres).isEqualTo(ZonedDateTime.of(2026, 3, 29, 4, 0, 0, 0, MADRID).toInstant()
                    .minusSeconds(3600));
            assertThat(Viaje.instante(DIA, s(10, 0), MADRID)).isEqualTo(hora(10, 0));
        }
    }

    // ---------------------------------------------------------------- fase 3b

    @Nested
    class TresMuestrasIncompletas {

        @Test
        void noBorranUnTrenYTresCompletasSi() {
            // Una sola ausencia no borraría ni con una muestra completa: hay que llegar a 3 para que la prueba
            // distinga «incompleta» de «completa».
            estado.procesarPosiciones(posiciones(hora(10, 5), con(relleno(29), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5));
            for (int i = 1; i <= 3; i++) {
                estado.procesarPosiciones(posiciones(hora(10, 5, i * 20), relleno(15)), hora(10, 5, i * 20));
            }
            assertThat(estado.tren("51T1")).isNotNull();
            assertThat(estado.tren("51T1").ausencias()).isEqualTo(1); // solo la tercera, aceptada como nivel nuevo

            EstadoDirecto otro = new EstadoDirecto(horario, MADRID, 3, 0.8);
            otro.procesarPosiciones(posiciones(hora(10, 5), con(relleno(29), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5));
            int desaparecidos = 0;
            for (int i = 1; i <= 3; i++) {
                ResultadoPosiciones r = otro.procesarPosiciones(posiciones(hora(10, 5, i * 20), relleno(29)),
                        hora(10, 5, i * 20));
                assertThat(r.completa()).isTrue();
                desaparecidos += r.desaparecidos();
            }
            assertThat(otro.tren("51T1")).isNull();
            assertThat(desaparecidos).isEqualTo(1);
        }
    }

    @Nested
    class FalloDeLaBaseDeDatosAMitadDeUnaMuestra {

        @Test
        void unViajeQueNoSePuedeBuscarSeSaltaSinCambiosNiAusencias() {
            estado.procesarPosiciones(posiciones(hora(10, 5), con(relleno(9), vp("51T1", "A", 41.6, 0.60))),
                    hora(10, 5));
            // Falta en dos muestras completas: lleva 2 ausencias, a una de desaparecer.
            estado.procesarPosiciones(posiciones(hora(10, 6), relleno(9)), hora(10, 6));
            estado.procesarPosiciones(posiciones(hora(10, 7), relleno(9)), hora(10, 7));
            assertThat(estado.tren("51T1").ausencias()).isEqualTo(2);
            horario.fallan.add("51T1");
            for (int i = 1; i <= 3; i++) {
                ResultadoPosiciones r = estado.procesarPosiciones(
                        posiciones(hora(10, 8, i * 20), con(relleno(9), vp("51T1", "B", 41.6, 0.61))),
                        hora(10, 8, i * 20));
                assertThat(r.nueva()).isTrue();
                assertThat(r.sinResolver()).isEqualTo(1);
                assertThat(r.cambios()).noneMatch(t -> t.id().equals("51T1"));
            }
            // Ni se ha movido (el horario no contesta) ni ha desaparecido (estaba en el feed).
            Tren t = estado.tren("51T1");
            assertThat(t.stopId()).isEqualTo("A");
            assertThat(t.ausencias()).isZero();
            assertThat(estado.trenes()).hasSize(10);

            horario.fallan.clear();
            ResultadoPosiciones r = estado.procesarPosiciones(
                    posiciones(hora(10, 9), con(relleno(9), vp("51T1", "B", 41.6, 0.61))), hora(10, 9));
            assertThat(r.sinResolver()).isZero();
            assertThat(estado.tren("51T1").stopId()).isEqualTo("B");
        }

        @Test
        void trasElPrimerFalloNoSeVuelveAPreguntarALaBaseDeDatosEnEsaMuestra() {
            // Con la base de datos colgada, cada pregunta esperaría su plazo (5-30 s). Tras la primera, los viajes que
            // no están en caché se dan por no resueltos sin preguntar; los que están en caché, se procesan.
            List<VehiclePosition> vps = new ArrayList<>(List.of(vp("51T1", "A", 41.6, 0.60)));
            for (int i = 0; i < 20; i++) {
                horario.fallan.add("NUEVO" + i);
                vps.add(vp("NUEVO" + i, "A", 41.6, 0.60));
            }
            ResultadoPosiciones r = estado.procesarPosiciones(posiciones(hora(10, 5), vps), hora(10, 5));
            assertThat(horario.llamadasFallidas.get()).isEqualTo(1);
            assertThat(r.sinResolver()).isEqualTo(20);
            assertThat(estado.tren("51T1")).isNotNull();
        }

        @Test
        void unTrenQueNoSePuedeCalcularSeSaltaSinCongelarElFeed() {
            // Un viaje con datos rotos (una parada sin stop_id) hace fallar el cálculo de ese tren.
            horario.viajes.put("51T9", new Viaje("51T9", "51T0004R4", "R4", 51, "C", DIA, List.of(
                    new ParadaViaje(1, null, "?", 41.6, 0.60, s(10, 0), s(10, 0), null),
                    new ParadaViaje(2, "A", "A", 41.6, 0.61, s(10, 10), s(10, 10), null)), null, 1));
            ResultadoPosiciones r = estado.procesarPosiciones(posiciones(hora(10, 5),
                    List.of(vp("51T1", "A", 41.6, 0.60), vp("51T9", "A", 41.6, 0.60))), hora(10, 5));
            assertThat(r.nueva()).isTrue();
            assertThat(r.sinResolver()).isEqualTo(1);
            assertThat(estado.tren("51T1")).isNotNull();
            assertThat(estado.tren("51T9")).isNull();
        }

        @Test
        void unViajeQueCambiaDeVersionEnElHorarioSeVuelveABuscarPorParada() {
            // Seguimiento en B (índice 1 del viaje viejo, A-B-C). El horario nuevo alarga el viaje: X-A-Y-B-Z-C.
            estado.procesarRetrasos(retrasos(hora(10, 8), List.of(tu("51T1", "B", 120))), hora(10, 8));
            horario.viajes.put("51T1", new Viaje("51T1", "51T0004R4", "R4", 51, "C", DIA, List.of(
                    new ParadaViaje(1, "X", "X", 41.6, 0.59, s(9, 50), s(9, 50), null),
                    new ParadaViaje(2, "A", "A", 41.6, 0.60, s(10, 0), s(10, 0), null),
                    new ParadaViaje(3, "Y", "Y", 41.6, 0.605, s(10, 5), s(10, 5), null),
                    new ParadaViaje(4, "B", "B", 41.6, 0.61, s(10, 10), s(10, 11), null),
                    new ParadaViaje(5, "Z", "Z", 41.6, 0.615, s(10, 15), s(10, 15), null),
                    new ParadaViaje(6, "C", "C", 41.6, 0.62, s(10, 20), s(10, 20), null)), null, 1));
            // Va a C (índice 5 del nuevo). Con los índices del viejo, cerraría paradas que no son (o se saldría).
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 18), List.of(tu("51T1", "C", 180))),
                    hora(10, 18));
            assertThat(r.cerrados()).extracting(RetrasoFinal::stopId, RetrasoFinal::stopSequence, RetrasoFinal::inferido)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("B", 4, false),
                            org.assertj.core.groups.Tuple.tuple("Z", 5, true));
            assertThat(r.sinResolver()).isZero();
        }

        @Test
        void unaMarcaAbsurdaConLaCabeceraSinMarcaSeAcotaConLaHora() {
            // Cabecera 0 y tren sin marca: antes, 1970, fuera de toda partición.
            FeedMessage m = FeedMessage.newBuilder()
                    .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(0))
                    .addEntity(FeedEntity.newBuilder().setId("1").setVehicle(vp("51T1", "A", 41.6, 0.60))).build();
            estado.procesarPosiciones(m, hora(10, 5));
            assertThat(estado.tren("51T1").marca()).isEqualTo(hora(10, 5));
        }

        @Test
        void elCierreDeUnaParadaNoSePierdeSiElHorarioFallaJustoAlPasarla() {
            estado.procesarRetrasos(retrasos(hora(10, 8), List.of(tu("51T1", "B", 120))), hora(10, 8));
            horario.fallan.add("51T1");
            ResultadoRetrasos conFallo = estado.procesarRetrasos(retrasos(hora(10, 13), List.of(tu("51T1", "C", 180))),
                    hora(10, 13));
            assertThat(conFallo.sinResolver()).isEqualTo(1);
            assertThat(conFallo.cerrados()).isEmpty();

            horario.fallan.clear();
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 14), List.of(tu("51T1", "C", 180))),
                    hora(10, 14));
            // La muestra siguiente lo pone al día: B se cierra con su último retraso (2 min). Como ese retraso es de
            // hace dos muestras (la de en medio se saltó), se marca como inferido.
            assertThat(r.cerrados()).singleElement().satisfies(f -> {
                assertThat(f.stopId()).isEqualTo("B");
                assertThat(f.minutos()).isEqualTo(2);
                assertThat(f.inferido()).isTrue();
            });
        }

        @Test
        void siFallaElProcesadoEnteroElEstadoNoCambiaYLaMuestraSePuedeRepetir() {
            horario.viajes.put("51T2", viaje("51T2", RECTO, 1, 0.0, 0.5, 1.0));
            estado.procesarRetrasos(retrasos(hora(10, 8), List.of(tu("51T1", "B", 120), tu("51T2", "B", 0))),
                    hora(10, 8));
            estado.procesarPosiciones(posiciones(hora(10, 8), List.of(vp("51T1", "A", 41.6, 0.60),
                    vp("51T2", "A", 41.6, 0.60))), hora(10, 8));

            // A mitad de las dos muestras, un fallo inesperado (no de la base de datos): nada se aplica, tampoco lo
            // del 51T1, que va antes en la muestra.
            Instant ultimaAntes = estado.ultimaMuestraPosiciones();
            horario.revientan.add("51T2");
            FeedMessage tus = retrasos(hora(10, 13), List.of(tu("51T1", "C", 180), tu("51T2", "C", 60)));
            FeedMessage vps = posiciones(hora(10, 13), List.of(vp("51T1", "B", 41.6, 0.61),
                    vp("51T2", "B", 41.6, 0.61)));
            assertThatThrownBy(() -> estado.procesarRetrasos(tus, hora(10, 13))).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> estado.procesarPosiciones(vps, hora(10, 13))).isInstanceOf(AssertionError.class);
            assertThat(estado.tren("51T1").stopId()).isEqualTo("A");
            assertThat(estado.ultimaMuestraPosiciones()).isEqualTo(ultimaAntes);

            // La misma muestra, otra vez (la ingesta no confirmó su ETag): sigue siendo nueva y ahora se aplica.
            horario.revientan.clear();
            ResultadoRetrasos r = estado.procesarRetrasos(tus, hora(10, 13));
            assertThat(r.nueva()).isTrue();
            assertThat(r.cerrados()).extracting(RetrasoFinal::tripId).containsExactlyInAnyOrder("51T1", "51T2");
            assertThat(estado.procesarPosiciones(vps, hora(10, 13)).nueva()).isTrue();
            assertThat(estado.tren("51T1").stopId()).isEqualTo("B");
        }
    }

    @Nested
    class ParadasSaltadas {

        @Test
        void siSeSaltaParadasEntreDosMuestrasLasIntermediasSeCierranComoInferidas() {
            estado.procesarRetrasos(retrasos(hora(9, 58), List.of(tu("51T1", "A", 60))), hora(9, 58));
            // La muestra siguiente ya va a C: B no se ha visto nunca como próxima parada.
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 12), List.of(tu("51T1", "C", 180))),
                    hora(10, 12));
            assertThat(r.cerrados()).extracting(RetrasoFinal::stopId, RetrasoFinal::minutos, RetrasoFinal::inferido)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("A", 1, false),
                            org.assertj.core.groups.Tuple.tuple("B", 1, true));
            assertThat(r.inferidos()).isEqualTo(1);
            assertThat(r.sinCerrar()).isZero();
        }

        @Test
        void sinRetrasoConocidoLasSaltadasCuentanComoSinCerrar() {
            TripUpdate sinRetraso = TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId("51T1"))
                    .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("A")).build();
            estado.procesarRetrasos(retrasos(hora(9, 58), List.of(sinRetraso)), hora(9, 58));
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 12), List.of(tu("51T1", "C", 180))),
                    hora(10, 12));
            assertThat(r.cerrados()).isEmpty();
            assertThat(r.sinCerrar()).isEqualTo(2);
        }

        @Test
        void unViajeQueDesapareceAMitadYVuelveMasAdelanteCierraLoQueFaltaComoInferido() {
            estado.procesarRetrasos(retrasos(hora(9, 58), con(rellenoTu(9), tu("51T1", "A", 120))), hora(9, 58));
            List<RetrasoFinal> cerrados = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                cerrados.addAll(estado.procesarRetrasos(retrasos(hora(10, 1, i * 20), rellenoTu(9)),
                        hora(10, 1, i * 20)).cerrados());
            }
            assertThat(cerrados).isEmpty(); // desaparece a mitad: no se sabe nada aún
            assertThat(estado.retirados()).isEqualTo(1);

            // Vuelve yendo a C: A y B se cierran con el último retraso que se conocía (2 min), las dos inferidas
            // porque ese retraso es de antes de desaparecer.
            ResultadoRetrasos r = estado.procesarRetrasos(retrasos(hora(10, 15), con(rellenoTu(9), tu("51T1", "C", 300))),
                    hora(10, 15));
            assertThat(r.cerrados()).extracting(RetrasoFinal::stopId, RetrasoFinal::minutos, RetrasoFinal::inferido)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("A", 2, true),
                            org.assertj.core.groups.Tuple.tuple("B", 2, true));
            assertThat(r.inferidos()).isEqualTo(2);
            assertThat(estado.retirados()).isZero();

            // Y sigue como cualquier otro: al desaparecer en C, C se cierra con su retraso (visto).
            List<RetrasoFinal> alFinal = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                alFinal.addAll(estado.procesarRetrasos(retrasos(hora(10, 21, i * 20), rellenoTu(9)),
                        hora(10, 21, i * 20)).cerrados());
            }
            assertThat(alFinal).singleElement().satisfies(f -> {
                assertThat(f.stopId()).isEqualTo("C");
                assertThat(f.minutos()).isEqualTo(5);
                assertThat(f.inferido()).isFalse();
            });
        }

        @Test
        void siVuelveDespuesDeLaRetencionEmpiezaDeCero() {
            EstadoDirecto corto = new EstadoDirecto(horario, MADRID, 3, 0.8, java.time.Duration.ofMinutes(10));
            corto.procesarRetrasos(retrasos(hora(9, 58), con(rellenoTu(9), tu("51T1", "A", 120))), hora(9, 58));
            for (int i = 1; i <= 3; i++) {
                corto.procesarRetrasos(retrasos(hora(9, 59, i * 10), rellenoTu(9)), hora(9, 59, i * 10));
            }
            assertThat(corto.retirados()).isEqualTo(1);
            ResultadoRetrasos r = corto.procesarRetrasos(retrasos(hora(10, 15), con(rellenoTu(9), tu("51T1", "C", 300))),
                    hora(10, 15));
            assertThat(r.cerrados()).isEmpty();
            assertThat(corto.retirados()).isZero();
            // Su parada pendiente (A) no se pierde sin rastro: cuenta como sin cerrar al caducar.
            assertThat(r.sinCerrar()).isEqualTo(1);
        }
    }

    @Nested
    class Concurrencia {

        /**
         * Un escritor procesa muestras sin parar mientras cuatro lectores leen como lo hará la API de la fase 4. Ningún
         * lector ve un error ni un tren a medias: su parada, su índice y su estado cuadran siempre entre sí.
         */
        @Test
        void losLectoresNoVenNuncaUnTrenAMedias() throws InterruptedException {
            int viajes = 50;
            for (int v = 0; v < viajes; v++) {
                horario.viajes.put("51C" + v, viaje("51C" + v, RECTO, 1, 0.0, 0.5, 1.0));
            }
            AtomicBoolean parar = new AtomicBoolean();
            List<Throwable> errores = new CopyOnWriteArrayList<>();
            AtomicInteger lecturas = new AtomicInteger();
            CountDownLatch empezar = new CountDownLatch(1);
            List<Thread> lectores = new ArrayList<>();
            for (int l = 0; l < 4; l++) {
                lectores.add(Thread.ofPlatform().start(() -> {
                    try {
                        empezar.await();
                        while (!parar.get()) {
                            for (Tren t : estado.trenes()) {
                                coherente(t);
                            }
                            Tren uno = estado.tren("51C7");
                            if (uno != null) {
                                coherente(uno);
                            }
                            Map<EstadoTren, Integer> porEstado = estado.trenesPorEstado();
                            assertThat(porEstado.values().stream().mapToInt(Integer::intValue).sum())
                                    .isBetween(0, viajes);
                            lecturas.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        errores.add(e);
                    }
                }));
            }
            empezar.countDown();
            String[] paradas = { "A", "B", "C" };
            Instant t0 = hora(10, 0);
            for (int i = 0; i < 1_500; i++) {
                Instant ahora = t0.plusSeconds(i);
                List<VehiclePosition> vps = new ArrayList<>();
                List<TripUpdate> tus = new ArrayList<>();
                for (int v = 0; v < viajes; v++) {
                    String parada = paradas[(i + v) % 3];
                    vps.add(vp("51C" + v, parada, 41.6, parada.equals("A") ? 0.60 : parada.equals("B") ? 0.61 : 0.62));
                    tus.add(tu("51C" + v, parada, 60 * (i % 5)));
                }
                estado.procesarRetrasos(retrasos(ahora, tus), ahora);
                estado.procesarPosiciones(posiciones(ahora, vps), ahora);
                estado.reestimar(ahora);
            }
            parar.set(true);
            for (Thread t : lectores) {
                t.join(5_000);
            }
            assertThat(errores).isEmpty();
            assertThat(lecturas.get()).isPositive();
            assertThat(estado.trenes()).hasSize(viajes);
        }

        void coherente(Tren t) {
            assertThat(t.viaje()).isNotNull();
            assertThat(t.indiceParada()).isEqualTo(t.viaje().indiceDe(t.stopId(), 0));
            assertThat(t.stopSequence()).isEqualTo(t.indiceParada() + 1);
            if (t.estado() == EstadoTren.TERMINADO) {
                assertThat(t.stopId()).isEqualTo("C");
            }
            assertThat(t.estimada()).isNotNull();
        }
    }

    // ---------------------------------------------------------------- muestras reales

    static FeedMessage muestra(String nombre) throws IOException {
        try (InputStream in = EstadoDirectoTests.class.getResourceAsStream("/gtfsrt/" + nombre)) {
            return FeedMessage.parseFrom(in);
        }
    }

    @Test
    void lasMuestrasRealesConLaIncompletaAlFinal() throws IOException {
        EstadoDirecto real = new EstadoDirecto(new HorarioFalso(), MADRID, 3, 0.8);
        List<ResultadoPosiciones> resultados = new ArrayList<>();
        for (String t : List.of("092815", "092835", "092855", "092915")) {
            Instant ahora = Instant.parse("2026-11-20T" + t.substring(0, 2) + ":" + t.substring(2, 4) + ":"
                    + t.substring(4) + "Z");
            real.procesarRetrasos(muestra("20261120T" + t + "Z_trip_updates.pb"), ahora);
            resultados.add(real.procesarPosiciones(muestra("20261120T" + t + "Z_vehicle_positions.pb"), ahora));
        }
        assertThat(resultados.getFirst().cambios().size()).isGreaterThan(300); // la primera: todos son nuevos
        // Solo los cambios: en la segunda muestra, muchas menos filas que trenes.
        assertThat(resultados.get(1).cambios().size()).isBetween(40, 120);
        // La última trae un tercio menos: incompleta, y no desaparece nadie.
        assertThat(resultados.get(3).completa()).isFalse();
        assertThat(resultados.get(3).desaparecidos()).isZero();
        int trenes = real.trenes().size();
        assertThat(trenes).isGreaterThanOrEqualTo(337);
        assertThat(real.tren("4023V24219C1").stopId()).isEqualTo("69103");

        // Fase 3b: la misma muestra incompleta, dos veces más (con cabeceras nuevas). Tres incompletas seguidas no
        // borran a nadie: la tercera se acepta como el nivel nuevo y es solo la primera ausencia de los que faltan.
        FeedMessage incompleta = muestra("20261120T092915Z_vehicle_positions.pb");
        long cabeceraReal = incompleta.getHeader().getTimestamp();
        List<ResultadoPosiciones> mas = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            FeedMessage m = FeedMessage.newBuilder(incompleta).setHeader(FeedHeader.newBuilder(incompleta.getHeader())
                    .setTimestamp(cabeceraReal + 20L * i)).build();
            mas.add(real.procesarPosiciones(m, Instant.ofEpochSecond(cabeceraReal + 20L * i)));
        }
        assertThat(mas.get(0).completa()).isFalse();
        assertThat(mas.get(0).desaparecidos()).isZero();
        assertThat(mas.get(1).completa()).isTrue(); // la tercera incompleta seguida: nivel nuevo
        // Los que solo faltan en las incompletas llevan, como mucho, una ausencia: el C1 sigue.
        assertThat(real.tren("4023V24219C1")).isNotNull();
        // Con el nivel nuevo ya son completas: a la tercera ausencia, los que faltan desaparecen.
        assertThat(mas.get(3).desaparecidos()).isGreaterThan(50);
        int desaparecidos = mas.stream().mapToInt(ResultadoPosiciones::desaparecidos).sum();
        assertThat(real.trenes()).hasSize(trenes - desaparecidos);
    }
}
