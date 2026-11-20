package com.ivanchesa.trainwatch.horario;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/**
 * Cómo pasa cada fichero del GTFS a su tabla: qué columnas se llenan y cómo se convierte cada fila.
 * La conversión valida: un dato obligatorio ausente o mal formado lanza {@link GtfsInvalidoException} y la
 * importación entera falla (la aplicación sigue con el horario anterior).
 *
 * @param fichero  nombre del fichero dentro del ZIP
 * @param tabla    tabla de destino en el esquema de carga
 * @param columnas columnas de destino, en el orden de {@code mapeo}
 * @param mapeo    de una fila del CSV a los valores de las columnas
 * @param opcional si el ZIP puede no traer el fichero
 */
public record TablaGtfs(String fichero, String tabla, List<String> columnas, Function<FilaCsv, Object[]> mapeo,
        boolean opcional) {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.BASIC_ISO_DATE;

    /** Ficheros que se importan, en el orden en que se cargan. agency.txt (una fila) no se usa. */
    public static final List<TablaGtfs> TODAS = List.of(
            new TablaGtfs("stops.txt", "stops",
                    List.of("stop_id", "stop_name", "stop_lat", "stop_lon", "wheelchair_boarding"),
                    f -> new Object[] { f.requerido("stop_id"), f.requerido("stop_name"), f.decimal("stop_lat"),
                            f.decimal("stop_lon"), f.entero("wheelchair_boarding") },
                    false),
            new TablaGtfs("routes.txt", "routes",
                    List.of("route_id", "route_short_name", "route_long_name", "route_type", "route_color",
                            "route_text_color", "nucleo"),
                    f -> new Object[] { f.requerido("route_id"), f.requerido("route_short_name"),
                            f.get("route_long_name"), entero(f, "route_type"), f.get("route_color"),
                            f.get("route_text_color"), nucleo(f.requerido("route_id")) },
                    false),
            new TablaGtfs("calendar.txt", "calendar",
                    List.of("service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
                            "sunday", "start_date", "end_date"),
                    f -> new Object[] { f.requerido("service_id"), f.booleano01("monday"), f.booleano01("tuesday"),
                            f.booleano01("wednesday"), f.booleano01("thursday"), f.booleano01("friday"),
                            f.booleano01("saturday"), f.booleano01("sunday"), fecha(f, "start_date"),
                            fecha(f, "end_date") },
                    false),
            new TablaGtfs("trips.txt", "trips",
                    List.of("trip_id", "route_id", "service_id", "trip_headsign", "wheelchair_accessible", "block_id",
                            "shape_id"),
                    f -> new Object[] { f.requerido("trip_id"), f.requerido("route_id"), f.requerido("service_id"),
                            f.get("trip_headsign"), f.entero("wheelchair_accessible"), f.get("block_id"),
                            f.get("shape_id") },
                    false),
            new TablaGtfs("stop_times.txt", "stop_times",
                    List.of("trip_id", "stop_sequence", "stop_id", "llegada_s", "salida_s"),
                    TablaGtfs::horaDePaso,
                    false),
            // Los puntos van a una tabla auxiliar del esquema de carga; de ahí salen los LineString de "shapes".
            new TablaGtfs("shapes.txt", EsquemaCarga.TABLA_PUNTOS,
                    List.of("shape_id", "lat", "lon", "secuencia"),
                    f -> new Object[] { f.requerido("shape_id"), f.decimal("shape_pt_lat"), f.decimal("shape_pt_lon"),
                            entero(f, "shape_pt_sequence") },
                    false),
            new TablaGtfs("transfers.txt", "transfers",
                    List.of("from_stop_id", "to_stop_id", "from_route_id", "to_route_id", "from_trip_id", "to_trip_id",
                            "transfer_type", "min_transfer_time"),
                    f -> new Object[] { f.requerido("from_stop_id"), f.requerido("to_stop_id"), f.get("from_route_id"),
                            f.get("to_route_id"), f.get("from_trip_id"), f.get("to_trip_id"),
                            f.get("transfer_type") == null ? 0 : f.entero("transfer_type"),
                            f.entero("min_transfer_time") },
                    true));

    /** Una fila de stop_times. Si falta la llegada o la salida se usa la otra; si faltan las dos, error. */
    static Object[] horaDePaso(FilaCsv f) {
        Integer llegada = f.hora("arrival_time");
        Integer salida = f.hora("departure_time");
        if (llegada == null && salida == null) {
            throw f.error("sin arrival_time ni departure_time");
        }
        return new Object[] { f.requerido("trip_id"), entero(f, "stop_sequence"), f.requerido("stop_id"),
                llegada != null ? llegada : salida, salida != null ? salida : llegada };
    }

    /** El núcleo son las cifras iniciales del route_id: {@code 51T0049RL4} es del 51, Rodalies de Catalunya. */
    static Integer nucleo(String routeId) {
        int i = 0;
        while (i < routeId.length() && i < 4 && Character.isDigit(routeId.charAt(i))) {
            i++;
        }
        return i == 0 ? null : Integer.valueOf(routeId.substring(0, i));
    }

    private static int entero(FilaCsv f, String columna) {
        f.requerido(columna);
        return f.entero(columna);
    }

    private static LocalDate fecha(FilaCsv f, String columna) {
        String v = f.requerido(columna);
        try {
            return LocalDate.parse(v, FECHA);
        } catch (DateTimeParseException e) {
            throw f.error(columna + " no es una fecha AAAAMMDD: " + v);
        }
    }
}
