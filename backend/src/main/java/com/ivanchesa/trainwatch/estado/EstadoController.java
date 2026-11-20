package com.ivanchesa.trainwatch.estado;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint mínimo para comprobar que el backend responde. La salud de verdad (base de datos incluida)
 * está en {@code /actuator/health}.
 */
@RestController
@RequestMapping("/api")
public class EstadoController {

    static final String VERSION_DESCONOCIDA = "desconocida";

    private final Clock reloj;
    private final String version;

    public EstadoController(Clock reloj, ObjectProvider<BuildProperties> buildProperties) {
        this.reloj = reloj;
        BuildProperties propiedades = buildProperties.getIfAvailable();
        this.version = propiedades != null ? propiedades.getVersion() : VERSION_DESCONOCIDA;
    }

    @GetMapping("/estado")
    public Estado estado() {
        return new Estado("TrainWatch", version, OffsetDateTime.now(reloj));
    }
}
