package com.ivanchesa.trainwatch.estado;

import java.time.OffsetDateTime;

/**
 * Respuesta de {@code GET /api/estado}: qué aplicación responde, en qué versión y a qué hora.
 *
 * @param aplicacion nombre de la aplicación
 * @param version    versión del artefacto (sale de {@code META-INF/build-info.properties})
 * @param hora       hora del servidor en Europe/Madrid, con su desfase
 */
public record Estado(String aplicacion, String version, OffsetDateTime hora) {
}
