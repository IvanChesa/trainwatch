package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ivanchesa.trainwatch.api.ConsultasApi.Paso;
import com.ivanchesa.trainwatch.api.Respuestas.ProximoTren;
import com.ivanchesa.trainwatch.directo.EstadoDirecto;
import com.ivanchesa.trainwatch.directo.EstadoTren;
import com.ivanchesa.trainwatch.directo.ParadaViaje;
import com.ivanchesa.trainwatch.directo.Tren;
import com.ivanchesa.trainwatch.directo.Viaje;

/** Los próximos trenes de una estación, sin base de datos: el horario y el directo, simulados. */
class ProximosTrenesTests {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate DIA = LocalDate.of(2026, 11, 20);

    final ConsultasApi consultas = Mockito.mock(ConsultasApi.class);
    final EstadoDirecto estado = Mockito.mock(EstadoDirecto.class);
    final EstacionesController controlador = new EstacionesController(consultas, estado, Clock.systemUTC());

    static Instant hora(LocalDate dia, int h, int m) {
        return ZonedDateTime.of(dia.atTime(h, m), MADRID).toInstant();
    }

    static int s(int h, int m) {
        return h * 3600 + m * 60;
    }

    /** Un tren en directo que va por la parada B (secuencia 2) del viaje A-B-C, con 2 min de retraso. */
    static Tren enB(String estadoFeed) {
        Viaje viaje = new Viaje("T1", "51T0004R4", "R4", 51, "", DIA, List.of(
                new ParadaViaje(1, "A", "A", 41.6, 0.60, s(10, 0), s(10, 0), null),
                new ParadaViaje(2, "B", "B", 41.6, 0.61, s(10, 4), s(10, 5), null),
                new ParadaViaje(3, "C", "C", 41.6, 0.62, s(10, 15), s(10, 15), null)), null, 1);
        return new Tren("T1", "T1", "v1", "R4", 51, viaje, DIA, EstadoTren.EN_MARCHA, "B", 1, estadoFeed, 41.6, 0.61,
                hora(DIA, 10, 11), 120, null, 0);
    }

    @Test
    void unTrenQueYaHaSalidoDeLaEstacionNoSaleAunqueElFeedSigaDandoEsaParada() {
        // A las 10:12, el feed sigue diciendo B (la última que ha dejado atrás) pero va IN_TRANSIT_TO hacia C: salió
        // de B a las 10:07 (10:05 + 2 min).
        when(consultas.pasos(eq("B"), eq(DIA), anyLong(), anyLong()))
                .thenReturn(List.of(new Paso("T1", 2, s(10, 5), "R4", 51, "C")));
        when(estado.trenes()).thenReturn(List.of(enB("IN_TRANSIT_TO")));
        assertThat(controlador.proximos("B", hora(DIA, 10, 12))).isEmpty();

        // Si está parado en B (STOPPED_AT), sí: aún no ha salido, aunque su hora estimada ya haya pasado.
        when(estado.trenes()).thenReturn(List.of(enB("STOPPED_AT")));
        assertThat(controlador.proximos("B", hora(DIA, 10, 12))).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("T1");
            assertThat(p.retrasoMin()).isEqualTo(2);
            assertThat(p.salidaEstimada().toInstant()).isEqualTo(hora(DIA, 10, 7));
        });
    }

    @Test
    void pasadaLaMedianocheSalenLosViajesDelDiaAnterior() {
        // A las 00:05 del 21, un viaje del 20 que pasa a las 24:10 (00:10 del 21) y otro del 21 a las 00:30.
        LocalDate manana = DIA.plusDays(1);
        when(consultas.pasos(eq("B"), eq(DIA), anyLong(), anyLong()))
                .thenReturn(List.of(new Paso("NOCHE", 2, s(24, 10), "R4", 51, "C")));
        when(consultas.pasos(eq("B"), eq(manana), anyLong(), anyLong()))
                .thenReturn(List.of(new Paso("MADRUGADA", 1, s(0, 30), "R4", 51, "C")));
        when(estado.trenes()).thenReturn(List.of());
        List<ProximoTren> proximos = controlador.proximos("B", hora(manana, 0, 5));
        assertThat(proximos).extracting(p -> p.salidaProgramada().toInstant())
                .containsExactly(hora(manana, 0, 10), hora(manana, 0, 30));
        assertThat(proximos).allSatisfy(p -> {
            assertThat(p.enDirecto()).isFalse();
            assertThat(p.retrasoMin()).isNull();
        });
        // La ventana del día anterior se pide en sus segundos (más de 86.400).
        Mockito.verify(consultas).pasos(eq("B"), eq(DIA), eq((long) s(24, 5) - 1800), eq((long) s(24, 5) + 3600));
    }
}
