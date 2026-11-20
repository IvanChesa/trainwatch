package com.ivanchesa.trainwatch.api;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la API pública ({@code trainwatch.api.*}).
 *
 * @param origenesPermitidos orígenes con CORS: la web y el servidor de Vite en local
 * @param directo            límites y ritmo del directo por SSE
 */
@ConfigurationProperties("trainwatch.api")
public record ApiProperties(
        @DefaultValue({ "https://trainwatch.ivanchesa.com", "http://localhost:5173" }) List<String> origenesPermitidos,
        @DefaultValue Directo directo) {

    /**
     * @param maximoConexiones conexiones SSE abiertas a la vez, entre todos los núcleos; la siguiente recibe un 503
     * @param latido           cada cuánto se manda un {@code latido} a cada conexión: mantiene vivos los proxies (como
     *                         Cloudflare, que corta a los 100 s sin datos) y descubre los clientes que se han ido
     * @param duracionMaxima   tiempo máximo de una conexión: al pasarlo se cierra y el navegador se reconecta solo
     *                         (reparte la carga si hay varias instancias y limpia conexiones medio muertas)
     * @param colaMaxima       eventos que pueden esperar a un cliente lento; si se pasa, se le desconecta (se
     *                         reconectará y recibirá una instantánea nueva) en vez de acumular memoria
     * @param reintento        lo que se pide al navegador que espere antes de reconectarse ({@code retry:}); se le suma
     *                         hasta otro tanto al azar para que no vuelvan todos a la vez
     */
    public record Directo(
            @DefaultValue("500") int maximoConexiones,
            @DefaultValue("15s") Duration latido,
            @DefaultValue("30m") Duration duracionMaxima,
            @DefaultValue("50") int colaMaxima,
            @DefaultValue("3s") Duration reintento) {
    }
}
