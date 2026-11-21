package com.ivanchesa.trainwatch.api;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.ivanchesa.trainwatch.api.ConsultasApi.Extension;

/**
 * El rectángulo donde está el servicio de un núcleo: el de sus estaciones, pero sin las más apartadas que casi no
 * tienen trenes (ADR 0023).
 *
 * <p>El rectángulo de todas las estaciones encuadraba mal el mapa: en Rodalies, la R15 llega a Aragón (Villanueva de
 * Gállego), la R16 a Ulldecona y la R11 a Cerbère, y con ellas el rectángulo iba de Zaragoza a Francia y cubría casi
 * media península; en Bilbao, la C4 llega hasta León. Aquí cada estación pesa lo que sus horas de paso en el horario
 * y, en cada eje por separado, se quita por cada lado lo que sume menos del {@link #CORTE} del total: una estación
 * con muchos trenes no se pierde nunca, y unas pocas paradas lejanas con dos trenes al día, sí.
 */
final class ExtensionDelServicio {

    /** Lo que se recorta por cada lado y en cada eje: el 0,5 % de las horas de paso (queda el 99 %). */
    static final double CORTE = 0.005;

    /** Una estación de un núcleo con sus horas de paso en el horario. */
    record Parada(double lon, double lat, long pasos) {
    }

    private ExtensionDelServicio() {
    }

    /** La extensión de un núcleo con al menos una parada. */
    static Extension de(int nucleo, List<Parada> paradas) {
        if (paradas.isEmpty()) {
            throw new IllegalArgumentException("El núcleo " + nucleo + " no tiene paradas");
        }
        double total = 0;
        for (Parada p : paradas) {
            total += Math.max(p.pasos(), 0);
        }
        if (total == 0) {
            // Sin horas de paso (no debería pasar): el rectángulo de todas.
            return new Extension(nucleo, min(paradas, Parada::lon), min(paradas, Parada::lat), max(paradas, Parada::lon),
                    max(paradas, Parada::lat));
        }
        double[] lon = recortar(paradas, Parada::lon, total);
        double[] lat = recortar(paradas, Parada::lat, total);
        return new Extension(nucleo, lon[0], lat[0], lon[1], lat[1]);
    }

    /**
     * Los extremos del eje tras quitar por cada lado las paradas que, juntas, pesan menos del {@link #CORTE}: el
     * primero es el de la parada en que el peso acumulado desde abajo llega al corte; el segundo, igual desde arriba.
     */
    private static double[] recortar(List<Parada> paradas, ToDoubleFunction<Parada> eje, double total) {
        List<Parada> orden = paradas.stream().sorted(Comparator.comparingDouble(eje)).toList();
        double limite = CORTE * total;
        double acumulado = 0;
        double desde = eje.applyAsDouble(orden.getFirst());
        for (Parada p : orden) {
            acumulado += Math.max(p.pasos(), 0);
            if (acumulado >= limite) {
                desde = eje.applyAsDouble(p);
                break;
            }
        }
        acumulado = 0;
        double hasta = eje.applyAsDouble(orden.getLast());
        for (Parada p : orden.reversed()) {
            acumulado += Math.max(p.pasos(), 0);
            if (acumulado >= limite) {
                hasta = eje.applyAsDouble(p);
                break;
            }
        }
        return new double[] { desde, hasta };
    }

    private static double min(List<Parada> paradas, ToDoubleFunction<Parada> eje) {
        return paradas.stream().mapToDouble(eje).min().orElseThrow();
    }

    private static double max(List<Parada> paradas, ToDoubleFunction<Parada> eje) {
        return paradas.stream().mapToDouble(eje).max().orElseThrow();
    }
}
