package com.ivanchesa.trainwatch.horario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Service;

import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Resultado;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/**
 * Lanza el trabajo de importación, de una en una, lo apunta en {@code importacion_horario} y publica sus métricas
 * en Micrometer:
 * <ul>
 * <li>{@code trainwatch.horario.importacion} (timer, etiqueta {@code resultado}): duración de cada importación;</li>
 * <li>{@code trainwatch.horario.filas} (gauge, etiqueta {@code tabla}): filas de cada tabla del horario en uso;</li>
 * <li>{@code trainwatch.horario.calidad} (gauge, etiqueta {@code medida}): viajes que encajan en su recorrido, etc.</li>
 * </ul>
 * Dos cerrojos: {@code ocupado}, en la JVM, que se toma antes de encolar (dos disparos seguidos no encolan dos
 * importaciones); y un <em>advisory lock</em> de PostgreSQL ({@link #CLAVE_CERROJO}), por si hubiera dos instancias
 * de la aplicación contra la misma base de datos (por ejemplo, durante un despliegue).
 */
@Service
public class ImportadorHorario {

    private static final Logger log = LoggerFactory.getLogger(ImportadorHorario.class);

    /** Lo que devuelve una importación. */
    public record Informe(Resultado resultado, Duration duracion, Map<String, Long> filas, String error) {
    }

    private final JobOperator operador;
    private final Job trabajo;
    private final RegistroImportaciones registro;
    private final EsquemaCarga esquema;
    private final Clock reloj;
    private final MeterRegistry metricas;
    /** Clave del advisory lock de PostgreSQL para la importación del horario (un número cualquiera, fijo). */
    static final long CLAVE_CERROJO = 20_261_120_002L;

    private final DataSource dataSource;
    private final AtomicBoolean ocupado = new AtomicBoolean();
    private final ExecutorService segundoPlano = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("importacion-horario").daemon(true).factory());
    private final Map<String, AtomicLong> filasEnUso = new ConcurrentHashMap<>();

    public ImportadorHorario(JobOperator operador, Job importacionHorario, RegistroImportaciones registro,
            EsquemaCarga esquema, Clock reloj, MeterRegistry metricas, DataSource dataSource) {
        this.dataSource = dataSource;
        this.operador = operador;
        this.trabajo = importacionHorario;
        this.registro = registro;
        this.esquema = esquema;
        this.reloj = reloj;
        this.metricas = metricas;
        registro.ultimaCorrecta().ifPresent(i -> publicarFilas(i.filas()));
    }

    public boolean enCurso() {
        return ocupado.get();
    }

    public boolean horarioVacio() {
        return esquema.vivoVacio();
    }

    /** Lanza la importación en otro hilo. Devuelve false si ya había una en marcha. */
    public boolean lanzarEnSegundoPlano(Motivo motivo, boolean forzar) {
        if (!ocupado.compareAndSet(false, true)) {
            return false;
        }
        try {
            segundoPlano.submit(() -> {
                try {
                    conCerrojoDeBaseDeDatos(motivo, forzar);
                } catch (RuntimeException e) {
                    log.error("Importación del horario ({}) cortada: {}", motivo, e.getMessage(), e);
                } finally {
                    ocupado.set(false);
                }
            });
        } catch (RuntimeException e) {
            ocupado.set(false);
            throw e;
        }
        return true;
    }

    /**
     * Importa el horario en este hilo y espera a que termine. Si ya hay una importación en marcha, no hace nada y
     * devuelve {@link Resultado#EN_CURSO}.
     */
    public Informe importar(Motivo motivo, boolean forzar) {
        if (!ocupado.compareAndSet(false, true)) {
            log.info("Importación del horario ({}) descartada: ya hay una en marcha", motivo);
            return new Informe(Resultado.EN_CURSO, Duration.ZERO, Map.of(), null);
        }
        try {
            return conCerrojoDeBaseDeDatos(motivo, forzar);
        } finally {
            ocupado.set(false);
        }
    }

    /**
     * Ejecuta {@code accion} con el advisory lock de la importación cogido en una conexión propia (los advisory
     * locks de sesión son de la conexión). Devuelve false, sin ejecutarla, si lo tiene otra instancia.
     */
    boolean conCerrojoGlobal(Runnable accion) {
        try (java.sql.Connection conexion = dataSource.getConnection()) {
            boolean cogido;
            try (var st = conexion.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                st.setLong(1, CLAVE_CERROJO);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    cogido = rs.getBoolean(1);
                }
            }
            if (!cogido) {
                return false;
            }
            try {
                accion.run();
            } finally {
                try (var st = conexion.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    st.setLong(1, CLAVE_CERROJO);
                    st.execute();
                }
            }
            return true;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("No se pudo usar el cerrojo de la importación en PostgreSQL", e);
        }
    }

    private Informe conCerrojoDeBaseDeDatos(Motivo motivo, boolean forzar) {
        Informe[] informe = new Informe[1];
        long t0 = System.nanoTime();
        if (!conCerrojoGlobal(() -> informe[0] = ejecutar(motivo, forzar, t0))) {
            log.info("Importación del horario ({}) descartada: otra instancia está importando", motivo);
            return new Informe(Resultado.EN_CURSO, Duration.ZERO, Map.of(), null);
        }
        return informe[0];
    }

    private Informe ejecutar(Motivo motivo, boolean forzar, long t0) {
        long id = registro.empezar(motivo, OffsetDateTime.now(reloj));
        var anterior = registro.ultimaCorrecta();
        log.info("Importación del horario {} ({}{})", id, motivo, forzar ? ", forzada" : "");

        Resultado resultado;
        String error = null;
        ExecutionContext contexto = new ExecutionContext();
        try {
            var parametros = new JobParametersBuilder()
                    .addLong("registro", id)
                    .addString(ImportacionJobConfig.PARAM_ETAG_ANTERIOR,
                            anterior.map(RegistroImportaciones.Importacion::etag).orElse(""), false)
                    .addString(ImportacionJobConfig.PARAM_LAST_MODIFIED_ANTERIOR,
                            anterior.map(RegistroImportaciones.Importacion::lastModified).orElse(""), false)
                    .addString(ImportacionJobConfig.PARAM_FORZAR, Boolean.toString(forzar), false)
                    .toJobParameters();
            JobExecution ejecucion = operador.start(trabajo, parametros);
            contexto = ejecucion.getExecutionContext();
            if (ejecucion.getStatus() == BatchStatus.COMPLETED) {
                resultado = ImportacionJobConfig.SIN_CAMBIOS.equals(ejecucion.getExitStatus().getExitCode())
                        ? Resultado.SIN_CAMBIOS : Resultado.CORRECTA;
            } else {
                resultado = Resultado.FALLIDA;
                error = ejecucion.getAllFailureExceptions().stream().findFirst()
                        .map(ImportadorHorario::mensaje).orElse("Estado " + ejecucion.getStatus());
            }
        } catch (Exception e) {
            resultado = Resultado.FALLIDA;
            error = mensaje(e);
        }

        if (resultado == Resultado.FALLIDA) {
            try {
                esquema.borrar(EsquemaCarga.CARGA);
            } catch (RuntimeException e) {
                log.warn("No se pudo borrar {} tras el fallo: {}", EsquemaCarga.CARGA, e.getMessage());
            }
        }
        borrarZip(contexto);

        @SuppressWarnings("unchecked")
        Map<String, Long> filas = resultado == Resultado.CORRECTA
                ? (Map<String, Long>) contexto.get(ImportacionJobConfig.CLAVE_FILAS) : null;
        Duration duracion = Duration.ofNanos(System.nanoTime() - t0);
        registro.terminar(id, OffsetDateTime.now(reloj), resultado,
                contexto.getString(ImportacionJobConfig.CLAVE_ETAG, null),
                contexto.getString(ImportacionJobConfig.CLAVE_LAST_MODIFIED, null),
                fecha(contexto, ImportacionJobConfig.CLAVE_VIGENCIA_DESDE),
                fecha(contexto, ImportacionJobConfig.CLAVE_VIGENCIA_HASTA), filas, error);
        Timer.builder("trainwatch.horario.importacion").description("Duración de la importación del horario")
                .tag("resultado", resultado.name()).register(metricas).record(duracion);
        if (filas != null) {
            publicarFilas(filas);
        }

        if (resultado == Resultado.FALLIDA) {
            log.error("Importación del horario {} FALLIDA en {} s: {}. Sigue el horario anterior.", id,
                    duracion.toSeconds(), error);
        } else {
            log.info("Importación del horario {} {} en {} s{}", id, resultado, duracion.toMillis() / 1000.0,
                    filas == null ? "" : ": " + filas);
        }
        return new Informe(resultado, duracion, filas == null ? Map.of() : filas, error);
    }

    private void publicarFilas(Map<String, Long> filas) {
        if (filas == null) {
            return;
        }
        filas.forEach((clave, n) -> filasEnUso.computeIfAbsent(clave, c -> {
            AtomicLong valor = new AtomicLong();
            boolean esTabla = EsquemaCarga.TABLAS.contains(c);
            Gauge.builder(esTabla ? "trainwatch.horario.filas" : "trainwatch.horario.calidad", valor, AtomicLong::get)
                    .description(esTabla ? "Filas de cada tabla del horario en uso" : "Cifras de calidad del horario en uso")
                    .tag(esTabla ? "tabla" : "medida", c).register(metricas);
            return valor;
        }).set(n));
    }

    private static void borrarZip(ExecutionContext contexto) {
        String zip = contexto.getString(ImportacionJobConfig.CLAVE_ZIP, null);
        if (zip != null) {
            try {
                Files.deleteIfExists(Path.of(zip));
            } catch (Exception e) {
                log.warn("No se pudo borrar el ZIP temporal {}: {}", zip, e.getMessage());
            }
        }
    }

    private static LocalDate fecha(ExecutionContext contexto, String clave) {
        String v = contexto.getString(clave, null);
        return v == null ? null : LocalDate.parse(v);
    }

    private static String mensaje(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        String m = causa.getClass().getSimpleName() + ": " + causa.getMessage();
        return causa == e ? m : e.getMessage() + " ← " + m;
    }

    @PreDestroy
    void parar() {
        segundoPlano.shutdownNow();
    }
}
