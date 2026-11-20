package com.ivanchesa.trainwatch.horario;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * El trabajo de Spring Batch que importa el horario. Pasos, en orden:
 * <ol>
 * <li>{@code descarga}: descarga condicional. Si no ha cambiado, el trabajo termina aquí ({@value #SIN_CAMBIOS});</li>
 * <li>{@code preparar}: esquema de carga vacío, con las tablas del vivo y sin índices;</li>
 * <li>un paso por fichero ({@code carga-stops.txt}...): lectura en streaming del ZIP y COPY por lotes;</li>
 * <li>{@code indices}: claves e índices, y ANALYZE;</li>
 * <li>{@code derivados}: recorridos, autobuses y paradas proyectadas sobre el recorrido;</li>
 * <li>{@code validar}: cuenta filas y rechaza un horario vacío o mucho más pequeño que el actual;</li>
 * <li>{@code cambio}: el cambio de golpe, en una transacción;</li>
 * <li>{@code limpieza}: borra el horario anterior (si falla, no pasa nada: se borra en la siguiente importación).</li>
 * </ol>
 * Si cualquier paso falla, el trabajo para y el horario vivo no se ha tocado.
 */
@Configuration(proxyBeanMethods = false)
public class ImportacionJobConfig {

    public static final String NOMBRE = "importacionHorario";
    public static final String SIN_CAMBIOS = "SIN_CAMBIOS";

    /** Claves del contexto del trabajo, que los pasos se pasan entre sí y lee {@link ImportadorHorario}. */
    public static final String CLAVE_ZIP = "zip";
    public static final String CLAVE_ETAG = "etag";
    public static final String CLAVE_LAST_MODIFIED = "lastModified";
    public static final String CLAVE_BYTES = "bytes";
    public static final String CLAVE_FILAS = "filas";
    public static final String CLAVE_VIGENCIA_DESDE = "vigenciaDesde";
    public static final String CLAVE_VIGENCIA_HASTA = "vigenciaHasta";

    /** Parámetros del trabajo. */
    public static final String PARAM_ETAG_ANTERIOR = "etagAnterior";
    public static final String PARAM_LAST_MODIFIED_ANTERIOR = "lastModifiedAnterior";
    public static final String PARAM_FORZAR = "forzar";

    private static final Logger log = LoggerFactory.getLogger(ImportacionJobConfig.class);

    @Bean
    Job importacionHorario(JobRepository repositorio, PlatformTransactionManager transacciones, DataSource dataSource,
            DescargaHorario descarga, EsquemaCarga esquema, HorarioProperties propiedades) {

        Step pasoDescarga = tarea("descarga", repositorio, transacciones, (contribucion, contexto) -> {
            JobParameters p = contexto.getStepContext().getStepExecution().getJobParameters();
            DescargaHorario.Resultado r = descarga.descargar(
                    p.getString(PARAM_ETAG_ANTERIOR, ""), p.getString(PARAM_LAST_MODIFIED_ANTERIOR, ""),
                    Boolean.parseBoolean(p.getString(PARAM_FORZAR, "false")));
            ExecutionContext trabajo = contextoDelTrabajo(contexto);
            if (r.etag() != null) {
                trabajo.putString(CLAVE_ETAG, r.etag());
            }
            if (r.lastModified() != null) {
                trabajo.putString(CLAVE_LAST_MODIFIED, r.lastModified());
            }
            if (!r.cambiado()) {
                contribucion.setExitStatus(new ExitStatus(SIN_CAMBIOS));
            } else {
                trabajo.putString(CLAVE_ZIP, r.zip().toString());
                trabajo.putLong(CLAVE_BYTES, r.bytes());
            }
            return RepeatStatus.FINISHED;
        });

        Step pasoPreparar = tarea("preparar", repositorio, transacciones, (c, x) -> {
            esquema.preparar();
            return RepeatStatus.FINISHED;
        });

        FlowBuilder<Flow> carga = new FlowBuilder<Flow>("carga").start(pasoPreparar);
        for (TablaGtfs tabla : TablaGtfs.TODAS) {
            carga.next(pasoDeCarga(tabla, repositorio, transacciones, dataSource, propiedades.tamanoLote()));
        }

        Step pasoIndices = tarea("indices", repositorio, transacciones, (c, x) -> {
            esquema.crearIndices();
            esquema.analizar();
            return RepeatStatus.FINISHED;
        });

        Step pasoDerivados = tarea("derivados", repositorio, transacciones, (c, x) -> {
            esquema.calcularDerivados(propiedades.distanciaMaximaForma(), propiedades.toleranciaRetrocesoM());
            esquema.analizar();
            return RepeatStatus.FINISHED;
        });

        Step pasoValidar = tarea("validar", repositorio, transacciones, (c, x) -> {
            Map<String, Long> filas = new LinkedHashMap<>(esquema.contar(EsquemaCarga.CARGA));
            filas.putAll(esquema.calidad(EsquemaCarga.CARGA));
            for (String tabla : new String[] { "stops", "routes", "calendar", "trips", "stop_times" }) {
                if (filas.get(tabla) == 0) {
                    throw new GtfsInvalidoException("El horario nuevo no trae ninguna fila en " + tabla);
                }
            }
            long viajesVivos = esquema.contar(EsquemaCarga.VIVO).get("trips");
            if (filas.get("trips") < viajesVivos * propiedades.minimoRelativo()) {
                throw new GtfsInvalidoException("El horario nuevo trae " + filas.get("trips")
                        + " viajes, menos del " + Math.round(propiedades.minimoRelativo() * 100)
                        + " % de los " + viajesVivos + " del actual: se rechaza");
            }
            LocalDate[] vigencia = esquema.vigencia(EsquemaCarga.CARGA);
            ExecutionContext trabajo = contextoDelTrabajo(x);
            trabajo.put(CLAVE_FILAS, filas);
            trabajo.putString(CLAVE_VIGENCIA_DESDE, vigencia[0].toString());
            trabajo.putString(CLAVE_VIGENCIA_HASTA, vigencia[1].toString());
            log.info("Horario cargado y válido: {}", filas);
            return RepeatStatus.FINISHED;
        });

        Step pasoCambio = tarea("cambio", repositorio, transacciones, (c, x) -> {
            esquema.cambiar();
            log.info("Horario cambiado: {} es ahora el vivo", EsquemaCarga.CARGA);
            return RepeatStatus.FINISHED;
        });

        Step pasoLimpieza = tarea("limpieza", repositorio, transacciones, (c, x) -> {
            try {
                esquema.borrar(EsquemaCarga.ANTERIOR);
            } catch (RuntimeException e) {
                log.warn("No se pudo borrar {} (se borrará en la siguiente importación): {}", EsquemaCarga.ANTERIOR,
                        e.getMessage());
            }
            return RepeatStatus.FINISHED;
        });

        Flow resto = carga.next(pasoIndices).next(pasoDerivados).next(pasoValidar).next(pasoCambio)
                .next(pasoLimpieza).build();

        return new JobBuilder(NOMBRE, repositorio)
                .start(pasoDescarga).on(SIN_CAMBIOS).end(SIN_CAMBIOS)
                .from(pasoDescarga).on(ExitStatus.COMPLETED.getExitCode()).to(resto)
                .end()
                .build();
    }

    /** Un paso por fichero: lector en streaming, conversión y validación de cada fila, y COPY por lotes. */
    private static Step pasoDeCarga(TablaGtfs tabla, JobRepository repositorio, PlatformTransactionManager transacciones,
            DataSource dataSource, int tamanoLote) {
        LectorCsvZip lector = new LectorCsvZip(tabla.fichero(), tabla.opcional());
        return new StepBuilder("carga-" + tabla.fichero(), repositorio)
                .<FilaCsv, Object[]>chunk(tamanoLote)
                .transactionManager(transacciones)
                .reader(lector)
                .processor(fila -> tabla.mapeo().apply(fila))
                .writer(new EscritorCopy(dataSource, EsquemaCarga.CARGA, tabla.tabla(), tabla.columnas()))
                .listener(lector)
                .build();
    }

    private static Step tarea(String nombre, JobRepository repositorio, PlatformTransactionManager transacciones,
            Tasklet tasklet) {
        return new StepBuilder(nombre, repositorio).tasklet(tasklet, transacciones).build();
    }

    private static ExecutionContext contextoDelTrabajo(
            org.springframework.batch.core.scope.context.ChunkContext contexto) {
        return contexto.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
    }
}
