package com.ivanchesa.trainwatch.directo;

/**
 * Decide si una muestra de un feed está completa comparando cuántas entidades trae con la última completa.
 *
 * <p>La fase 0 vio una muestra con un tercio de trenes menos (242 frente a ~360). Si se tomara por buena, un tercio
 * de los trenes empezaría a «desaparecer». Una muestra con menos de {@code fraccion} × la referencia es incompleta:
 * sus trenes se actualizan, pero los que faltan no suman ausencias. Si llegan {@code maximoSeguidas} incompletas
 * seguidas, el nivel bajo se acepta como el nuevo normal (un núcleo que deja de emitir, el final del servicio).
 *
 * <p>No es seguro entre hilos: lo usa solo el hilo de la ingesta.
 */
final class DetectorMuestrasIncompletas {

    private final double fraccion;
    private final int maximoSeguidas;
    private int referencia;
    private int bajasSeguidas;

    DetectorMuestrasIncompletas(double fraccion, int maximoSeguidas) {
        this.fraccion = fraccion;
        this.maximoSeguidas = maximoSeguidas;
    }

    boolean completa(int entidades) {
        if (referencia == 0 || entidades >= fraccion * referencia) {
            referencia = entidades;
            bajasSeguidas = 0;
            return true;
        }
        bajasSeguidas++;
        if (bajasSeguidas >= maximoSeguidas) {
            referencia = entidades;
            bajasSeguidas = 0;
            return true;
        }
        return false;
    }
}
