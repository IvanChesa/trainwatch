package com.ivanchesa.trainwatch.estadisticas;

import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Cifras;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.LlegadaFinal;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Porcentajes;

/**
 * Suma filas de agregados (días, horas, estaciones) sin perder exactitud: solo sumas y recuentos, y el histograma
 * posición a posición. De aquí salen la media, los porcentajes, la mediana y el p90 de cualquier periodo, iguales a los
 * que darían las filas originales de {@code retrasos} (ADR 0023).
 */
final class Acumulado {

    static final int POSICIONES = 61;

    int viajes;
    int paradas;
    long sumaMinutos;
    int menosDe3;
    int de3a5;
    int de6a15;
    int de16OMas;
    final int[] histograma = new int[POSICIONES];
    int finales;
    long sumaFinales;
    int finalesMenosDe3;

    void sumar(Fila f) {
        paradas += f.paradas();
        sumaMinutos += f.sumaMinutos();
        menosDe3 += f.menosDe3();
        de3a5 += f.de3a5();
        de6a15 += f.de6a15();
        de16OMas += f.de16OMas();
        for (int i = 0; i < POSICIONES; i++) {
            histograma[i] += f.histograma()[i];
        }
        finales += f.finales();
        sumaFinales += f.sumaFinales();
        finalesMenosDe3 += f.finalesMenosDe3();
    }

    /** Una fila de {@code estad_linea_hora} o {@code estad_estacion_dia} (sin llegada final, a 0). */
    record Fila(int viajes, int paradas, long sumaMinutos, int menosDe3, int de3a5, int de6a15, int de16OMas,
            int[] histograma, int finales, long sumaFinales, int finalesMenosDe3) {
    }

    /**
     * Las cifras con estos {@code viajes} (que no se pueden sumar por horas: los pone quien llama). Con menos de
     * {@code minimo} viajes, o sin paradas, {@code suficiente} es {@code false} y no hay ninguna cifra.
     */
    Cifras cifras(int viajes, int minimo) {
        boolean suficiente = viajes >= minimo && paradas > 0;
        if (!suficiente) {
            return new Cifras(viajes, paradas, false, null, null, null, null);
        }
        return new Cifras(viajes, paradas, true, decimal((double) sumaMinutos / paradas), percentil(50),
                percentil(90), new Porcentajes(porcentaje(menosDe3, paradas), porcentaje(de3a5, paradas),
                        porcentaje(de6a15, paradas), porcentaje(de16OMas, paradas)));
    }

    LlegadaFinal llegadaFinal(int minimo) {
        boolean suficiente = finales >= minimo;
        return new LlegadaFinal(finales, suficiente, suficiente ? decimal((double) sumaFinales / finales) : null,
                suficiente ? porcentaje(finalesMenosDe3, finales) : null);
    }

    /**
     * El primer minuto en que el acumulado llega al {@code porcentaje} % de las paradas: lo mismo que
     * {@code percentile_disc(porcentaje / 100.0)} sobre {@code least(greatest(minutos, 0), 60)}. En enteros, sin errores
     * de redondeo de los {@code double}.
     */
    Integer percentil(int porcentaje) {
        if (paradas == 0) {
            return null;
        }
        long acumulado = 0;
        for (int i = 0; i < POSICIONES; i++) {
            acumulado += histograma[i];
            if (acumulado * 100 >= (long) porcentaje * paradas) {
                return i;
            }
        }
        return POSICIONES - 1;
    }

    static Double decimal(double x) {
        return Math.round(x * 10) / 10.0;
    }

    static Double porcentaje(long parte, long total) {
        return total == 0 ? null : Math.round(1000.0 * parte / total) / 10.0;
    }
}
