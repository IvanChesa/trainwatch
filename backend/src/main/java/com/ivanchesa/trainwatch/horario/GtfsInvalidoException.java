package com.ivanchesa.trainwatch.horario;

/** El GTFS descargado no se puede importar: falta un fichero, un dato obligatorio o un valor no tiene sentido. */
public class GtfsInvalidoException extends RuntimeException {

    public GtfsInvalidoException(String mensaje) {
        super(mensaje);
    }
}
