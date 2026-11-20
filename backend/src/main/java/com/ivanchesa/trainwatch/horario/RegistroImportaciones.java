package com.ivanchesa.trainwatch.horario;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** La tabla {@code importacion_horario}: una fila por intento de importación, con su resultado. */
@Repository
public class RegistroImportaciones {

    public enum Motivo { NOCTURNA, MANUAL, ARRANQUE }

    public enum Resultado { EN_CURSO, CORRECTA, SIN_CAMBIOS, FALLIDA }

    public record Importacion(long id, Motivo motivo, OffsetDateTime inicio, OffsetDateTime fin, Resultado resultado,
            String etag, String lastModified, LocalDate vigenciaDesde, LocalDate vigenciaHasta, Map<String, Long> filas,
            String error) {
    }

    private static final TypeReference<Map<String, Long>> MAPA = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RegistroImportaciones(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long empezar(Motivo motivo, OffsetDateTime inicio) {
        return jdbc.sql("""
                INSERT INTO importacion_horario (motivo, inicio, resultado) VALUES (:motivo, :inicio, 'EN_CURSO')
                RETURNING id
                """).param("motivo", motivo.name()).param("inicio", inicio).query(Long.class).single();
    }

    public void terminar(long id, OffsetDateTime fin, Resultado resultado, String etag, String lastModified,
            LocalDate vigenciaDesde, LocalDate vigenciaHasta, Map<String, Long> filas, String error) {
        jdbc.sql("""
                UPDATE importacion_horario SET fin = :fin, resultado = :resultado, etag = :etag,
                       last_modified = :lastModified, vigencia_desde = :desde, vigencia_hasta = :hasta,
                       filas = CAST(:filas AS jsonb), error = :error
                WHERE id = :id
                """)
                .param("fin", fin).param("resultado", resultado.name()).param("etag", etag)
                .param("lastModified", lastModified).param("desde", vigenciaDesde).param("hasta", vigenciaHasta)
                .param("filas", filas == null ? null : json.writeValueAsString(filas)).param("error", error)
                .param("id", id)
                .update();
    }

    /**
     * Marca la importación como {@code CORRECTA} con sus cifras. Se llama dentro de la transacción del cambio de
     * esquema (paso {@code cambio}): si el cambio se deshace, esto también (fase 3b).
     */
    public void marcarCorrecta(long id, String etag, String lastModified, LocalDate vigenciaDesde,
            LocalDate vigenciaHasta, Map<String, Long> filas) {
        jdbc.sql("""
                UPDATE importacion_horario SET resultado = 'CORRECTA', etag = :etag, last_modified = :lastModified,
                       vigencia_desde = :desde, vigencia_hasta = :hasta, filas = CAST(:filas AS jsonb)
                WHERE id = :id
                """)
                .param("etag", etag).param("lastModified", lastModified).param("desde", vigenciaDesde)
                .param("hasta", vigenciaHasta).param("filas", filas == null ? null : json.writeValueAsString(filas))
                .param("id", id)
                .update();
    }

    /** El resultado apuntado para una importación. */
    public Optional<Resultado> resultado(long id) {
        return jdbc.sql("SELECT resultado FROM importacion_horario WHERE id = :id").param("id", id)
                .query((rs, n) -> Resultado.valueOf(rs.getString(1))).optional();
    }

    /** La última importación que cambió el horario: la que está en uso. */
    public Optional<Importacion> ultimaCorrecta() {
        return jdbc.sql("SELECT * FROM importacion_horario WHERE resultado = 'CORRECTA' ORDER BY inicio DESC, id DESC LIMIT 1")
                .query(mapeo()).optional();
    }

    /** El último intento, sea cual sea su resultado. */
    public Optional<Importacion> ultima() {
        return jdbc.sql("SELECT * FROM importacion_horario ORDER BY inicio DESC, id DESC LIMIT 1")
                .query(mapeo()).optional();
    }

    /** Intentos que quedaron EN_CURSO porque la aplicación se paró a medias: se marcan como fallidos al arrancar. */
    public int cerrarInterrumpidas(OffsetDateTime ahora) {
        return jdbc.sql("""
                UPDATE importacion_horario SET resultado = 'FALLIDA', fin = :ahora,
                       error = 'Interrumpida: la aplicación se paró durante la importación'
                WHERE resultado = 'EN_CURSO'
                """).param("ahora", ahora).update();
    }

    private RowMapper<Importacion> mapeo() {
        return (rs, n) -> {
            String filas = rs.getString("filas");
            return new Importacion(rs.getLong("id"), Motivo.valueOf(rs.getString("motivo")),
                    rs.getObject("inicio", OffsetDateTime.class), rs.getObject("fin", OffsetDateTime.class),
                    Resultado.valueOf(rs.getString("resultado")), rs.getString("etag"), rs.getString("last_modified"),
                    rs.getObject("vigencia_desde", LocalDate.class), rs.getObject("vigencia_hasta", LocalDate.class),
                    filas == null ? null : json.readValue(filas, MAPA), rs.getString("error"));
        };
    }
}
