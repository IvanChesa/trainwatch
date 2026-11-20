package com.ivanchesa.trainwatch.directo;

import java.time.Instant;

/**
 * Evento de Spring que publica {@link IngestaDirecto} al final de cada ciclo, con el estado en memoria ya al día y la
 * posición de los trenes reestimada. Lo escucha el directo por SSE (fase 4) para mandar los cambios: así la ingesta
 * no sabe nada de la API.
 *
 * @param instante la hora del final del ciclo
 */
public record CicloDirectoTerminado(Instant instante) {
}
