package com.ivanchesa.trainwatch.horario;

import java.util.Map;

/**
 * Una fila de un fichero del GTFS, con acceso por nombre de columna. Los valores se recortan (Renfe rellena
 * todos los campos con espacios) y una cadena vacía se trata como ausente.
 */
public record FilaCsv(String fichero, long linea, Map<String, Integer> cabecera, String[] valores) {

    /** El valor de la columna, recortado, o {@code null} si la columna no existe o está vacía. */
    public String get(String columna) {
        Integer i = cabecera.get(columna);
        if (i == null || i >= valores.length) {
            return null;
        }
        String v = valores[i].strip();
        return v.isEmpty() ? null : v;
    }

    /** Como {@link #get}, pero falla si falta: un dato obligatorio ausente invalida la importación entera. */
    public String requerido(String columna) {
        String v = get(columna);
        if (v == null) {
            throw error("falta " + columna);
        }
        return v;
    }

    public Integer entero(String columna) {
        String v = get(columna);
        try {
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw error(columna + " no es un entero: " + v);
        }
    }

    public double decimal(String columna) {
        String v = requerido(columna);
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw error(columna + " no es un número: " + v);
        }
    }

    /** {@code HH:MM:SS} del GTFS en segundos desde la medianoche del día de servicio (la hora puede pasar de 23). */
    public Integer hora(String columna) {
        String v = get(columna);
        if (v == null) {
            return null;
        }
        String[] p = v.split(":");
        try {
            if (p.length != 3) {
                throw new NumberFormatException();
            }
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int s = Integer.parseInt(p[2]);
            if (h < 0 || m < 0 || m > 59 || s < 0 || s > 59) {
                throw new NumberFormatException();
            }
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            throw error(columna + " no es una hora: " + v);
        }
    }

    public boolean booleano01(String columna) {
        String v = requerido(columna);
        return switch (v) {
            case "1" -> true;
            case "0" -> false;
            default -> throw error(columna + " no es 0 ni 1: " + v);
        };
    }

    public GtfsInvalidoException error(String detalle) {
        return new GtfsInvalidoException(fichero + ", línea " + linea + ": " + detalle);
    }
}
