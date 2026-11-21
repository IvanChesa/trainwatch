package com.ivanchesa.trainwatch.estadisticas;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de las estadísticas de puntualidad ({@code trainwatch.estadisticas.*}, ADR 0022 y 0023).
 *
 * @param muestraMinima      viajes (o pasos, en los tramos) por debajo de los cuales una cifra no se enseña («datos
 *                           insuficientes»)
 * @param publicarDesde      día (de Madrid) desde el que la API da cifras; antes, solo «recogiendo datos desde el …,
 *                           cifras a partir del …». Sin valor, se publica siempre
 * @param ventanaDias        días del periodo por defecto, que acaba ayer (4 semanas)
 * @param periodoMaximoDias  días como mucho entre {@code desde} y {@code hasta}, en la API y al recalcular a mano
 * @param diasARecuperar     además de ayer, cuántos días anteriores sin calcular recupera la tarea nocturna (por si la
 *                           aplicación estuvo parada a esa hora)
 * @param destacadas         líneas destacadas, como {@code núcleo:línea} (la RL4 de Lleida)
 * @param plazoSentencia     {@code statement_timeout} del cálculo de un día (el de la aplicación es 15 s)
 * @param esperaCerrojo      {@code lock_timeout} del cálculo: si algo retiene sus tablas, falla en vez de hacer cola
 * @param maximoTramos       tramos con más retraso en la respuesta de una línea
 */
@ConfigurationProperties("trainwatch.estadisticas")
public record EstadisticasProperties(
        @DefaultValue("30") int muestraMinima,
        LocalDate publicarDesde,
        @DefaultValue("28") int ventanaDias,
        @DefaultValue("400") int periodoMaximoDias,
        @DefaultValue("2") int diasARecuperar,
        @DefaultValue("51:RL4") List<String> destacadas,
        @DefaultValue("2m") Duration plazoSentencia,
        @DefaultValue("5s") Duration esperaCerrojo,
        @DefaultValue("10") int maximoTramos) {

    /** {@code true} si la línea está destacada (sin distinguir mayúsculas). */
    public boolean destacada(int nucleo, String linea) {
        return destacadas.stream().anyMatch(d -> d.equalsIgnoreCase(nucleo + ":" + linea));
    }
}
