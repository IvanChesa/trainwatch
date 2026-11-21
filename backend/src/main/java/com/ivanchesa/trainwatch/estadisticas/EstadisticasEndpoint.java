package com.ivanchesa.trainwatch.estadisticas;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Recalcular las estadísticas a mano, por Actuator (no es la API pública: en producción Actuator escucha en el 8081,
 * que solo se alcanza desde la máquina).
 *
 * <ul>
 * <li>{@code GET /actuator/estadisticas}: los últimos 14 días calculados, con cuándo y con qué fuentes.</li>
 * <li>{@code POST /actuator/estadisticas} con {@code {"desde": "2026-11-21", "hasta": "2026-11-27"}}: recalcula esos
 * días (ambos incluidos), uno por transacción, y devuelve lo que salió de cada uno.</li>
 * </ul>
 */
@Component
@Endpoint(id = "estadisticas")
public class EstadisticasEndpoint {

    public record DiaCalculado(LocalDate fecha, OffsetDateTime calculado, int filasRetrasos, boolean conHorario,
            boolean conPosiciones) {
    }

    private final CalculoEstadisticas calculo;
    private final JdbcClient jdbc;

    public EstadisticasEndpoint(CalculoEstadisticas calculo, JdbcClient jdbc) {
        this.calculo = calculo;
        this.jdbc = jdbc;
    }

    @ReadOperation
    public List<DiaCalculado> calculados() {
        return jdbc.sql("""
                SELECT fecha_servicio, calculado, filas_retrasos, con_horario, con_posiciones
                FROM estad_dias ORDER BY fecha_servicio DESC LIMIT 14""")
                .query((rs, n) -> new DiaCalculado(rs.getObject(1, LocalDate.class),
                        rs.getObject(2, OffsetDateTime.class), rs.getInt(3), rs.getBoolean(4), rs.getBoolean(5)))
                .list();
    }

    /** Lo que salió de un día: su resultado o, si falló, el error (los demás días se calculan igual). */
    public record Recalculo(LocalDate fecha, CalculoEstadisticas.Resultado resultado, String error) {
    }

    /** 400 si las fechas no valen; si no, 200 con un {@link Recalculo} por día. */
    @WriteOperation
    public WebEndpointResponse<Object> recalcular(String desde, String hasta) {
        LocalDate d;
        LocalDate h;
        try {
            d = LocalDate.parse(desde);
            h = LocalDate.parse(hasta);
            calculo.validar(d, h);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return new WebEndpointResponse<>(Map.of("error", "Fechas no válidas (AAAA-MM-DD, desde ≤ hasta): "
                    + e.getMessage()), WebEndpointResponse.STATUS_BAD_REQUEST);
        }
        List<Recalculo> dias = new java.util.ArrayList<>();
        for (LocalDate f = d; !f.isAfter(h); f = f.plusDays(1)) {
            try {
                dias.add(new Recalculo(f, calculo.calcular(f), null));
            } catch (RuntimeException e) {
                dias.add(new Recalculo(f, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return new WebEndpointResponse<>(dias, WebEndpointResponse.STATUS_OK);
    }
}
