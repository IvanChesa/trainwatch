package com.ivanchesa.trainwatch.horario;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;

/**
 * Cuándo se importa el horario:
 * <ul>
 * <li>cada noche, con {@code trainwatch.horario.cron} en hora de Madrid (por defecto, 04:30 y 06:30). Renfe
 * publica el fichero hacia las 04:09 de Madrid; la segunda pasada es el reintento si la primera falla o llega
 * pronto, y si el fichero no ha cambiado solo cuesta una petición con 304;</li>
 * <li>al arrancar, en segundo plano, si el horario está vacío (base de datos nueva);</li>
 * <li>a mano, con {@code POST /api/horario/importaciones} ({@link HorarioController}).</li>
 * </ul>
 */
@Component
public class ProgramacionHorario {

    private static final Logger log = LoggerFactory.getLogger(ProgramacionHorario.class);

    private final ImportadorHorario importador;
    private final RegistroImportaciones registro;
    private final HorarioProperties propiedades;
    private final Clock reloj;

    public ProgramacionHorario(ImportadorHorario importador, RegistroImportaciones registro,
            HorarioProperties propiedades, Clock reloj) {
        this.importador = importador;
        this.registro = registro;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    @Scheduled(cron = "${trainwatch.horario.cron:0 30 4,6 * * *}", zone = "Europe/Madrid")
    void nocturna() {
        importador.importar(Motivo.NOCTURNA, false);
    }

    @EventListener(ApplicationReadyEvent.class)
    void alArrancar() {
        int interrumpidas = registro.cerrarInterrumpidas(OffsetDateTime.now(reloj));
        if (interrumpidas > 0) {
            log.warn("{} importaciones del horario quedaron a medias en la ejecución anterior", interrumpidas);
        }
        if (propiedades.importarAlArrancar() && importador.horarioVacio()) {
            log.info("El horario está vacío: se importa ahora, en segundo plano");
            importador.lanzarEnSegundoPlano(Motivo.ARRANQUE, false);
        }
    }
}
