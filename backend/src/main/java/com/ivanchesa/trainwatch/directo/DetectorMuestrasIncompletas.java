package com.ivanchesa.trainwatch.directo;

/**
 * Decide si una muestra de un feed está completa comparando cuántas entidades trae con la última completa.
 *
 * <p>La fase 0 vio una muestra con un tercio de trenes menos (242 frente a ~360). Si se tomara por buena, un tercio
 * de los trenes empezaría a «desaparecer». Una muestra con menos de {@code fraccion} × la referencia es incompleta:
 * sus trenes se actualizan, pero los que faltan no suman ausencias. Si llegan {@code maximoSeguidas} incompletas
 * seguidas, el nivel bajo se acepta como el nuevo normal (un núcleo que deja de emitir, el final del servicio).
 *
 * <p>En dos pasos (fase 3b): {@link #evaluar} no cambia nada y {@link #aceptar} guarda el resultado. Así, si el
 * procesado de la muestra falla después de evaluarla, el detector queda como estaba y la muestra se puede repetir.
 *
 * <p>No es seguro entre hilos: lo usa solo el hilo de la ingesta.
 */
final class DetectorMuestrasIncompletas {

    /** Lo que se decidió de una muestra y cómo queda el detector si se acepta. */
    record Evaluacion(boolean completa, int referencia, int bajasSeguidas) {
    }

    private final double fraccion;
    private final int maximoSeguidas;
    private int referencia;
    private int bajasSeguidas;

    DetectorMuestrasIncompletas(double fraccion, int maximoSeguidas) {
        this.fraccion = fraccion;
        this.maximoSeguidas = maximoSeguidas;
    }

    /** Decide sin cambiar nada. */
    Evaluacion evaluar(int entidades) {
        if (referencia == 0 || entidades >= fraccion * referencia) {
            return new Evaluacion(true, entidades, 0);
        }
        int bajas = bajasSeguidas + 1;
        if (bajas >= maximoSeguidas) {
            return new Evaluacion(true, entidades, 0);
        }
        return new Evaluacion(false, referencia, bajas);
    }

    void aceptar(Evaluacion e) {
        referencia = e.referencia();
        bajasSeguidas = e.bajasSeguidas();
    }

    /** Evalúa y acepta de una vez. */
    boolean completa(int entidades) {
        Evaluacion e = evaluar(entidades);
        aceptar(e);
        return e.completa();
    }
}
