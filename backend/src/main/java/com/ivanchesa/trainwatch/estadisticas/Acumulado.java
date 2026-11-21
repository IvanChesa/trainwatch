package com.ivanchesa.trainwatch.estadisticas;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Cifras;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.LlegadaDestino;
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
    int destinos;
    long sumaDestinos;
    int destinosMenosDe3;

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
        destinos += f.destinos();
        sumaDestinos += f.sumaDestinos();
        destinosMenosDe3 += f.destinosMenosDe3();
    }

    /** Una fila de {@code estad_linea_hora} o {@code estad_estacion_dia} (sin llegadas a destino, a 0). */
    record Fila(int viajes, int paradas, long sumaMinutos, int menosDe3, int de3a5, int de6a15, int de16OMas,
            int[] histograma, int finales, int destinos, long sumaDestinos, int destinosMenosDe3) {
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
        return new Cifras(viajes, paradas, true, decimal(sumaMinutos, paradas), percentil(50),
                percentil(90), new Porcentajes(porcentaje(menosDe3, paradas), porcentaje(de3a5, paradas),
                        porcentaje(de6a15, paradas), porcentaje(de16OMas, paradas)));
    }

    /**
     * La cifra por viaje: solo los viajes que se vieron llegar a su destino (la última parada del horario). Los que
     * dejaron de verse antes se cuentan, pero no entran: han tenido menos recorrido para acumular retraso.
     */
    LlegadaDestino llegadaDestino(int minimo) {
        boolean suficiente = destinos >= minimo;
        return new LlegadaDestino(finales, destinos, suficiente, suficiente ? decimal(sumaDestinos, destinos) : null,
                suficiente ? porcentaje(destinosMenosDe3, destinos) : null);
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

    /**
     * {@code numerador / denominador} con un decimal, redondeando la mitad lejos del cero, como {@code round(x, 1)} de
     * PostgreSQL sobre {@code numeric} (-2,25 → -2,3): así la API y una consulta a mano dan lo mismo.
     */
    static Double decimal(long numerador, long denominador) {
        return BigDecimal.valueOf(numerador).divide(BigDecimal.valueOf(denominador), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** {@code 100 * parte / total} con un decimal (como {@link #decimal}); {@code null} si el total es 0. */
    static Double porcentaje(long parte, long total) {
        return total == 0 ? null : decimal(100 * parte, total);
    }
}
