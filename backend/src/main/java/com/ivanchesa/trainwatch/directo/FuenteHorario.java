package com.ivanchesa.trainwatch.directo;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Lo que la ingesta pregunta al horario importado (fase 2). Es una interfaz para poder probar el estado en memoria
 * sin base de datos. Las implementaciones tienen que ser seguras entre hilos.
 */
public interface FuenteHorario {

    /** El viaje, o {@code null} si no está en el horario (por ejemplo, las circulaciones {@code SPECIAL_…}). */
    Viaje viaje(String tripId);

    /** Los núcleos con trenes en el horario (sin los de solo autobús). */
    Set<Integer> nucleos();

    /** Por núcleo, los viajes de tren (sin autobuses) que según el horario están en marcha en ese instante. */
    Map<Integer, Set<String>> enMarchaSegunHorario(Instant ahora);
}
