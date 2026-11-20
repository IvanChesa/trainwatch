package com.ivanchesa.trainwatch.directo;

/** Los tres feeds GTFS Realtime de Renfe. El nombre en minúsculas es la etiqueta de las métricas. */
public enum Feed {
    POSICIONES, RETRASOS, AVISOS;

    public String etiqueta() {
        return name().toLowerCase();
    }
}
