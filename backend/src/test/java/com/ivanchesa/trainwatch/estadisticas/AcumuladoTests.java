package com.ivanchesa.trainwatch.estadisticas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ivanchesa.trainwatch.estadisticas.Acumulado.Fila;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Cifras;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.LineaRanking;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Porcentajes;

/** Lo que se calcula en Java al sumar agregados: percentiles del histograma, redondeos, muestra mínima y ranking. */
class AcumuladoTests {

    /** Una fila de agregado hecha con una lista de minutos, como la haría el SQL de la V6. */
    static Fila fila(int viajes, int... minutos) {
        int[] h = new int[Acumulado.POSICIONES];
        long suma = 0;
        int a = 0;
        int b = 0;
        int c = 0;
        int d = 0;
        for (int m : minutos) {
            h[Math.min(Math.max(m, 0), 60)]++;
            suma += m;
            if (m < 3) {
                a++;
            } else if (m <= 5) {
                b++;
            } else if (m <= 15) {
                c++;
            } else {
                d++;
            }
        }
        return new Fila(viajes, minutos.length, suma, a, b, c, d, h, 0, 0, 0, 0);
    }

    /** {@code percentile_disc(p)} de PostgreSQL: el primer valor cuya distribución acumulada llega a p. */
    static int percentileDisc(int[] valores, double p) {
        int[] v = Arrays.stream(valores).map(m -> Math.min(Math.max(m, 0), 60)).sorted().toArray();
        for (int i = 0; i < v.length; i++) {
            if ((i + 1) / (double) v.length >= p) {
                return v[i];
            }
        }
        return v[v.length - 1];
    }

    @Test
    void lasCifrasDeDosFilasSumadasSonLasDeTodasLasParadasJuntas() {
        Acumulado a = new Acumulado();
        a.sumar(fila(2, 0, 2, 5, 4));
        a.sumar(fila(2, 6, 10, -1));
        Cifras c = a.cifras(3, 2);
        // 0 2 5 4 6 10 -1 → suma 26 / 7 = 3,714 → 3,7. Ordenadas (el -1 como 0): 0 0 2 4 5 6 10.
        assertThat(c.mediaMinutos()).isEqualTo(3.7);
        assertThat(c.medianaMinutos()).isEqualTo(4);
        assertThat(c.p90Minutos()).isEqualTo(10);
        assertThat(c.porcentajes()).isEqualTo(new Porcentajes(42.9, 28.6, 28.6, 0.0));
    }

    @Test
    void elPercentilDelHistogramaEsElPercentileDiscDePostgresql() {
        Random azar = new Random(6);
        for (int prueba = 0; prueba < 500; prueba++) {
            int[] minutos = azar.ints(1 + azar.nextInt(80), -3, 75).toArray();
            Acumulado a = new Acumulado();
            a.sumar(fila(1, minutos));
            assertThat(a.percentil(50)).as("mediana de %s", Arrays.toString(minutos))
                    .isEqualTo(percentileDisc(minutos, 0.5));
            assertThat(a.percentil(90)).as("p90 de %s", Arrays.toString(minutos))
                    .isEqualTo(percentileDisc(minutos, 0.9));
        }
    }

    @Test
    void conMenosViajesQueElMinimoNoHayCifras() {
        Acumulado a = new Acumulado();
        a.sumar(fila(29, 1, 2, 3));
        Cifras c = a.cifras(29, 30);
        assertThat(c.suficiente()).isFalse();
        assertThat(c.viajes()).isEqualTo(29);
        assertThat(c.paradas()).isEqualTo(3);
        assertThat(c.mediaMinutos()).isNull();
        assertThat(c.porcentajes()).isNull();
        assertThat(a.cifras(30, 30).suficiente()).isTrue();
        // Sin paradas tampoco, aunque haya viajes.
        assertThat(new Acumulado().cifras(40, 30).suficiente()).isFalse();
    }

    @Test
    void redondeosConUnDecimalComoRoundDePostgresql() {
        // round(numeric, 1): la mitad, lejos del cero.
        assertThat(Acumulado.decimal(15, 4)).isEqualTo(3.8);
        assertThat(Acumulado.decimal(-9, 4)).isEqualTo(-2.3);
        assertThat(Acumulado.decimal(2, 3)).isEqualTo(0.7);
        assertThat(Acumulado.decimal(72, 13)).isEqualTo(5.5);
        assertThat(Acumulado.porcentaje(1, 3)).isEqualTo(33.3);
        assertThat(Acumulado.porcentaje(2, 3)).isEqualTo(66.7);
        assertThat(Acumulado.porcentaje(1, 0)).isNull();
    }

    @Test
    void rankingPorPuntualesLuegoPorMediaYLasInsuficientesAlFinalSinPuesto() {
        List<LineaRanking> lineas = new ArrayList<>();
        lineas.add(linea("R1", true, 80.0, 2.0));
        lineas.add(linea("R2", true, 90.0, 3.0));
        lineas.add(linea("R3", true, 80.0, 1.5));
        lineas.add(linea("A0", false, null, null));
        List<LineaRanking> r = EstadisticasController.ranking(lineas);
        assertThat(r).extracting(LineaRanking::linea).containsExactly("R2", "R3", "R1", "A0");
        assertThat(r).extracting(LineaRanking::puesto).containsExactly(1, 2, 3, null);
    }

    static LineaRanking linea(String nombre, boolean suficiente, Double menosDe3, Double media) {
        Cifras c = suficiente ? new Cifras(40, 400, true, media, 1, 5, new Porcentajes(menosDe3, 0.0, 0.0, 0.0))
                : new Cifras(3, 20, false, null, null, null, null);
        return new LineaRanking(null, nombre, false, c, null, null);
    }
}
