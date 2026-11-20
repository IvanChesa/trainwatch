package com.ivanchesa.trainwatch.horario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Importacion;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;

/**
 * Estado del horario y disparo manual de la importación.
 *
 * <p>El disparo manual se protege con un token compartido en la cabecera {@code X-Trainwatch-Token}
 * ({@code TRAINWATCH_ADMIN_TOKEN}). Sin token configurado, el endpoint no existe (404). No hay Spring Security
 * porque es el único endpoint que escribe; si llegan más, toca (ver el informe de la fase 2).
 */
@RestController
@RequestMapping("/api/horario")
public class HorarioController {

    public static final String CABECERA_TOKEN = "X-Trainwatch-Token";

    private final RegistroImportaciones registro;
    private final ImportadorHorario importador;
    private final HorarioProperties propiedades;

    public HorarioController(RegistroImportaciones registro, ImportadorHorario importador,
            HorarioProperties propiedades) {
        this.registro = registro;
        this.importador = importador;
        this.propiedades = propiedades;
    }

    /** El horario en uso (de qué importación sale, cuándo lo publicó Renfe, qué días cubre y sus filas). */
    public record HorarioEnUso(OffsetDateTime importadoEn, String publicadoPorRenfe, LocalDate vigenciaDesde,
            LocalDate vigenciaHasta, Map<String, Long> filas) {
    }

    /** El último intento de importación. */
    public record Intento(OffsetDateTime inicio, OffsetDateTime fin, Long duracionMs, String motivo, String resultado,
            String error) {
    }

    public record Estado(HorarioEnUso horario, Intento ultimoIntento, boolean importacionEnCurso) {
    }

    @GetMapping("/estado")
    public Estado estado() {
        HorarioEnUso horario = registro.ultimaCorrecta()
                .map(i -> new HorarioEnUso(i.fin(), i.lastModified(), i.vigenciaDesde(), i.vigenciaHasta(), i.filas()))
                .orElse(null);
        Intento intento = registro.ultima().map(HorarioController::intento).orElse(null);
        return new Estado(horario, intento, importador.enCurso());
    }

    @PostMapping("/importaciones")
    public ResponseEntity<Map<String, String>> importar(
            @RequestHeader(name = CABECERA_TOKEN, required = false) String token,
            @RequestParam(defaultValue = "false") boolean forzar) {
        String esperado = propiedades.tokenAdmin();
        if (esperado == null || esperado.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (token == null || !MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Token incorrecto"));
        }
        if (!importador.lanzarEnSegundoPlano(Motivo.MANUAL, forzar)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya hay una importación en marcha"));
        }
        return ResponseEntity.accepted()
                .body(Map.of("estado", "Importación lanzada; el resultado, en GET /api/horario/estado"));
    }

    private static Intento intento(Importacion i) {
        Long ms = i.fin() == null ? null : java.time.Duration.between(i.inicio(), i.fin()).toMillis();
        return new Intento(i.inicio(), i.fin(), ms, i.motivo().name(), i.resultado().name(), i.error());
    }
}
