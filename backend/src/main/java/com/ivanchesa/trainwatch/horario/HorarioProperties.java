package com.ivanchesa.trainwatch.horario;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la importación del horario ({@code trainwatch.horario.*}).
 *
 * @param url                  dónde se descarga el GTFS estático
 * @param cron                 cuándo se importa (hora de Madrid); {@code -} lo desactiva
 * @param importarAlArrancar   si la aplicación importa al arrancar cuando el horario está vacío
 * @param tokenAdmin           token del disparo manual (cabecera {@code X-Trainwatch-Token}); vacío lo desactiva
 * @param tamanoLote           filas por lote (una transacción y un COPY por lote)
 * @param minimoRelativo       el horario nuevo se rechaza si trae menos viajes que esta fracción del actual
 * @param distanciaMaximaForma metros: una parada más lejos de su recorrido hace que el viaje no encaje
 * @param toleranciaRetrocesoM metros que una parada puede «retroceder» sobre el recorrido sin romper el orden
 * @param tiempoMaximoDescarga tiempo máximo de la descarga
 */
@ConfigurationProperties("trainwatch.horario")
public record HorarioProperties(
        @DefaultValue("https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip") URI url,
        @DefaultValue("0 30 4,6 * * *") String cron,
        @DefaultValue("true") boolean importarAlArrancar,
        @DefaultValue("") String tokenAdmin,
        @DefaultValue("10000") int tamanoLote,
        @DefaultValue("0.5") double minimoRelativo,
        @DefaultValue("200") double distanciaMaximaForma,
        @DefaultValue("25") double toleranciaRetrocesoM,
        @DefaultValue("5m") Duration tiempoMaximoDescarga) {
}
