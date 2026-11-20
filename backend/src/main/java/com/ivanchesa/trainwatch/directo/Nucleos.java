package com.ivanchesa.trainwatch.directo;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Los núcleos de Cercanías y Rodalies: el código son las dos cifras con que empiezan route_id y trip_id. */
public final class Nucleos {

    public static final Map<Integer, String> NOMBRES = Map.ofEntries(
            Map.entry(10, "Madrid"), Map.entry(20, "Asturias"), Map.entry(30, "Sevilla"), Map.entry(31, "Cádiz"),
            Map.entry(32, "Málaga"), Map.entry(40, "Valencia"), Map.entry(41, "Murcia/Alicante"),
            Map.entry(45, "Cartagena"), Map.entry(46, "Ferrol"), Map.entry(47, "León"),
            Map.entry(51, "Rodalies de Catalunya"), Map.entry(60, "Bilbao"), Map.entry(61, "San Sebastián"),
            Map.entry(62, "Santander"), Map.entry(70, "Zaragoza"));

    /** Dos cifras al principio o detrás de un «_» ({@code 4023V24219C1}, {@code SPECIAL_10_91886C7}). */
    private static final Pattern CODIGO = Pattern.compile("(?:^|_)(\\d{2})");

    private Nucleos() {
    }

    /** El núcleo de un identificador de Renfe, o {@code null} si no empieza por dos cifras. */
    public static Integer deIdentificador(String id) {
        if (id == null) {
            return null;
        }
        Matcher m = CODIGO.matcher(id);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static String nombre(Integer nucleo) {
        return nucleo == null ? "Desconocido" : NOMBRES.getOrDefault(nucleo, "Núcleo " + nucleo);
    }
}
