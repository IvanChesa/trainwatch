package com.ivanchesa.trainwatch.directo;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la ingesta en directo ({@code trainwatch.directo.*}).
 *
 * @param activada               si el {@code @Scheduled} descarga los feeds (las pruebas la desactivan y llaman a mano)
 * @param intervalo              pausa entre el final de un ciclo y el principio del siguiente (5-10 s; Renfe renueva cada ~20 s)
 * @param urlPosiciones          {@code vehicle_positions.pb}
 * @param urlRetrasos            {@code trip_updates.pb}
 * @param urlAvisos              {@code alerts.pb}
 * @param tiempoConexion         tiempo máximo para conectar
 * @param tiempoRespuesta        tiempo máximo de cada petición, cuerpo incluido
 * @param tamanoMaximo           bytes; un feed más grande se rechaza (hoy pesan ~30 KB)
 * @param reintentos             intentos de cada descarga (el primero incluido)
 * @param esperaReintento        espera antes del segundo intento; se dobla en cada uno
 * @param umbralFallos           % de fallos en la ventana que abre el circuit breaker
 * @param ventanaCircuito        llamadas que mira el circuit breaker
 * @param esperaCircuitoAbierto  cuánto tiempo queda abierto antes de dejar pasar llamadas de prueba
 * @param muestrasParaDesaparecer muestras nuevas y completas seguidas sin un tren para darlo por desaparecido
 * @param fraccionIncompleta     una muestra con menos entidades que esta fracción de la última completa es incompleta
 * @param retencionPosiciones    cuánto se guardan las posiciones
 */
@ConfigurationProperties("trainwatch.directo")
public record DirectoProperties(
        @DefaultValue("true") boolean activada,
        @DefaultValue("7s") Duration intervalo,
        @DefaultValue("https://gtfsrt.renfe.com/vehicle_positions.pb") URI urlPosiciones,
        @DefaultValue("https://gtfsrt.renfe.com/trip_updates.pb") URI urlRetrasos,
        @DefaultValue("https://gtfsrt.renfe.com/alerts.pb") URI urlAvisos,
        @DefaultValue("3s") Duration tiempoConexion,
        @DefaultValue("5s") Duration tiempoRespuesta,
        @DefaultValue("5000000") int tamanoMaximo,
        @DefaultValue("3") int reintentos,
        @DefaultValue("500ms") Duration esperaReintento,
        @DefaultValue("50") float umbralFallos,
        @DefaultValue("10") int ventanaCircuito,
        @DefaultValue("60s") Duration esperaCircuitoAbierto,
        @DefaultValue("3") int muestrasParaDesaparecer,
        @DefaultValue("0.8") double fraccionIncompleta,
        @DefaultValue("48h") Duration retencionPosiciones) {

    URI url(Feed feed) {
        return switch (feed) {
            case POSICIONES -> urlPosiciones;
            case RETRASOS -> urlRetrasos;
            case AVISOS -> urlAvisos;
        };
    }
}
