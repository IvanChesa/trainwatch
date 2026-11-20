package com.ivanchesa.trainwatch.horario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ivanchesa.trainwatch.TestcontainersConfiguration;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Importacion;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Motivo;
import com.ivanchesa.trainwatch.horario.RegistroImportaciones.Resultado;

/**
 * Fase 3b: el registro de la importación como {@code CORRECTA} va en la misma transacción que el cambio de esquema.
 * La ingesta vacía sus cachés cuando ve una importación correcta nueva ({@code HorarioDirectoJdbc}), así que la
 * versión apuntada y el horario en uso tienen que cambiar a la vez.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ImportacionAtomicaTests {

    static final ServidorGtfs RENFE = new ServidorGtfs();
    static final AtomicInteger VERSION = new AtomicInteger();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("trainwatch.horario.url", () -> RENFE.url().toString());
    }

    @AfterAll
    static void apagar() {
        RENFE.close();
    }

    @Autowired
    ImportadorHorario importador;
    @MockitoSpyBean
    EsquemaCarga esquema;
    @MockitoSpyBean
    RegistroImportaciones registro;
    @Autowired
    JdbcClient jdbc;

    String etagAnterior;

    @BeforeEach
    void horarioEnUso() {
        etagAnterior = etagNuevo();
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), etagAnterior);
        assertThat(importador.importar(Motivo.MANUAL, false).resultado()).isEqualTo(Resultado.CORRECTA);
    }

    @AfterEach
    void limpiar() {
        reset(esquema, registro);
    }

    static String etagNuevo() {
        return "\"atomica-" + VERSION.incrementAndGet() + "\"";
    }

    long viajes() {
        return jdbc.sql("SELECT count(*) FROM horario.trips").query(Long.class).single();
    }

    @Test
    void siElCambioSeDeshaceElRegistroTambien() {
        // El cambio de esquema se hace y, en la misma transacción, algo falla: se deshacen las dos cosas.
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new IllegalStateException("Fallo justo después del cambio de esquema");
        }).when(esquema).cambiar();
        long viajesAntes = viajes();
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(java.util.Map.of("trips.txt", s -> s.lines()
                .limit(s.lines().count() - 1).reduce("", (a, b) -> a + b + "\n"))), etagNuevo());

        assertThat(importador.importar(Motivo.MANUAL, false).resultado()).isEqualTo(Resultado.FALLIDA);

        Importacion ultima = registro.ultima().orElseThrow();
        assertThat(ultima.resultado()).isEqualTo(Resultado.FALLIDA);
        assertThat(registro.ultimaCorrecta().orElseThrow().etag()).isEqualTo(etagAnterior);
        assertThat(viajes()).isEqualTo(viajesAntes);
        assertThat(jdbc.sql("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'horario%' ORDER BY 1")
                .query(String.class).list()).containsExactly("horario");
    }

    @Test
    void siFallaElRegistroDespuesDelCambioLaImportacionQuedaCorrecta() {
        // El paso «cambio» ya ha confirmado; luego falla la escritura final del registro (antes, la única).
        doThrow(new IllegalStateException("La base de datos se cae al apuntar el resultado")).doCallRealMethod()
                .when(registro).terminar(anyLong(), any(), eq(Resultado.CORRECTA), any(), any(), any(), any(), any(),
                        any());
        String etag = etagNuevo();
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), etag);

        assertThatThrownBy(() -> importador.importar(Motivo.MANUAL, true)).isInstanceOf(IllegalStateException.class);

        // La versión que ve la ingesta es la del horario en uso, con sus cifras: no se queda EN_CURSO.
        Importacion correcta = registro.ultimaCorrecta().orElseThrow();
        assertThat(correcta).isEqualTo(registro.ultima().orElseThrow());
        assertThat(correcta.etag()).isEqualTo(etag);
        assertThat(correcta.filas()).containsEntry("trips", 7L);
        assertThat(correcta.vigenciaDesde()).isNotNull();
    }

    @Test
    void siElTrabajoFallaDespuesDelCambioNoSeDesdice() {
        // Un error en la limpieza (después del cambio) hace que Spring Batch dé el trabajo por fallido. El horario
        // nuevo ya está en uso y apuntado: la importación queda CORRECTA, no FALLIDA.
        doThrow(new AssertionError("Fallo inesperado en la limpieza")).when(esquema).borrar(EsquemaCarga.ANTERIOR);
        String etag = etagNuevo();
        RENFE.sirve(ServidorGtfs.zipDeEjemplo(), etag);

        var informe = importador.importar(Motivo.MANUAL, true);

        assertThat(informe.resultado()).isEqualTo(Resultado.CORRECTA);
        assertThat(informe.filas()).containsEntry("trips", 7L);
        Importacion ultima = registro.ultima().orElseThrow();
        assertThat(ultima.resultado()).isEqualTo(Resultado.CORRECTA);
        assertThat(ultima.etag()).isEqualTo(etag);
        assertThat(ultima.fin()).isNotNull();
        assertThat(jdbc.sql("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'horario%' ORDER BY 1")
                .query(String.class).list()).isEqualTo(List.of("horario", "horario_anterior"));
    }
}
