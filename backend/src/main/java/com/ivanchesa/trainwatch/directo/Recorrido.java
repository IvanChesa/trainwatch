package com.ivanchesa.trainwatch.directo;

import java.util.Arrays;

/**
 * El recorrido de una línea ({@code shapes}) en memoria, para situar un tren en una fracción de él.
 *
 * <p>La fracción es la misma que calculó la importación con {@code ST_LineLocatePoint} sobre la geometría en
 * WGS 84, es decir, proporcional a la longitud en el plano de grados (fase 2). Por eso aquí se interpola también en
 * ese plano: así la fracción de una parada cae exactamente sobre su proyección. El rumbo sí es geodésico.
 *
 * <p>Inmutable: se comparte entre hilos sin sincronizar.
 */
public final class Recorrido {

    private final String shapeId;
    private final double[] lon;
    private final double[] lat;
    /** Longitud acumulada en el plano de grados hasta cada punto. */
    private final double[] acumulada;
    private final boolean cerrado;

    public Recorrido(String shapeId, double[] lon, double[] lat, boolean cerrado) {
        if (lon.length != lat.length || lon.length < 2) {
            throw new IllegalArgumentException("Un recorrido necesita al menos dos puntos: " + shapeId);
        }
        this.shapeId = shapeId;
        this.lon = lon.clone();
        this.lat = lat.clone();
        this.cerrado = cerrado;
        this.acumulada = new double[lon.length];
        for (int i = 1; i < lon.length; i++) {
            acumulada[i] = acumulada[i - 1] + Math.hypot(lon[i] - lon[i - 1], lat[i] - lat[i - 1]);
        }
    }

    public String shapeId() {
        return shapeId;
    }

    public boolean cerrado() {
        return cerrado;
    }

    /** {lat, lon} del punto en la fracción {@code f} (0..1) del recorrido. */
    public double[] punto(double f) {
        int i = segmento(f);
        double t = fraccionEnSegmento(i, f);
        return new double[] { lat[i] + t * (lat[i + 1] - lat[i]), lon[i] + t * (lon[i + 1] - lon[i]) };
    }

    /**
     * Rumbo (grados desde el norte, 0..360) en la fracción {@code f}: el del tramo en que cae el punto, en el sentido
     * del dibujo si {@code sentido} es 1 y al revés si es -1.
     */
    public double rumbo(double f, int sentido) {
        int i = segmento(f);
        double r = rumboEntre(lat[i], lon[i], lat[i + 1], lon[i + 1]);
        return sentido < 0 ? (r + 180) % 360 : r;
    }

    private int segmento(double f) {
        double objetivo = Math.clamp(f, 0.0, 1.0) * acumulada[acumulada.length - 1];
        int i = Arrays.binarySearch(acumulada, objetivo);
        if (i < 0) {
            i = -i - 2; // el punto anterior al objetivo
        }
        i = Math.clamp(i, 0, acumulada.length - 2);
        // Saltar tramos de longitud cero (puntos repetidos).
        while (i < acumulada.length - 2 && acumulada[i + 1] == acumulada[i]) {
            i++;
        }
        return i;
    }

    private double fraccionEnSegmento(int i, double f) {
        double largo = acumulada[i + 1] - acumulada[i];
        if (largo == 0) {
            return 0;
        }
        double objetivo = Math.clamp(f, 0.0, 1.0) * acumulada[acumulada.length - 1];
        return Math.clamp((objetivo - acumulada[i]) / largo, 0.0, 1.0);
    }

    /** Rumbo inicial geodésico (sobre la esfera) de un punto a otro, en grados desde el norte. */
    public static double rumboEntre(double lat1, double lon1, double lat2, double lon2) {
        double f1 = Math.toRadians(lat1);
        double f2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(f2);
        double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Distancia en metros entre dos puntos (haversine). */
    public static double distanciaM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(a));
    }
}
