package com.ivanchesa.trainwatch.estadisticas;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lo que devuelve la API de estadísticas (ADR 0025). Cada respuesta dice de dónde sale: el periodo, los viajes y las
 * paradas usados, lo que se ha quitado (inferidas, sin hora), los cancelados y la cobertura. Una cifra con menos viajes
 * que la muestra mínima va a {@code null} con {@code suficiente: false}. Antes de la fecha de publicación no hay
 * ninguna cifra: solo {@code publicacion} y el periodo.
 */
public final class RespuestasEstadisticas {

    /** Lo que acompaña siempre a las cifras. */
    public static final String FUENTE = "Datos: Renfe (CC BY 4.0). Cifras propias de TrainWatch, proyecto no oficial:"
            + " solo trenes vistos en directo; las paradas inferidas no cuentan.";

    private RespuestasEstadisticas() {
    }

    @Schema(description = "Si ya se publican cifras. Antes de cifrasDesde, la respuesta no trae ninguna")
    public record Publicacion(
            @Schema(example = "true") boolean publicado,
            @Schema(description = "Primer día de servicio con datos; null si aún no hay ninguno", example = "2026-11-21") LocalDate recogiendoDesde,
            @Schema(description = "Día desde el que se publican cifras; null si siempre", example = "2026-12-19") LocalDate cifrasDesde,
            @Schema(description = "Para enseñarlo tal cual", example = "Recogiendo datos desde el 21-11-2026; cifras a partir del 19-12-2026.") String texto,
            @Schema(description = "Viajes por debajo de los cuales una cifra no se da (suficiente: false)", example = "30") int muestraMinima) {
    }

    @Schema(description = "Días de servicio que abarca la respuesta (ambos incluidos)")
    public record Periodo(
            @Schema(example = "2026-11-21") LocalDate desde,
            @Schema(example = "2026-12-18") LocalDate hasta,
            @Schema(description = "Días del periodo que están calculados", example = "28") int diasConDatos) {
    }

    @Schema(description = "De dónde salen las cifras: lo que entra y lo que se ha quitado o contado aparte")
    public record Muestra(
            @Schema(description = "Viajes con alguna parada vista (uno por tren y día)", example = "1843") int viajes,
            @Schema(description = "Paradas vistas que entran en las cifras", example = "21904") int paradas,
            @Schema(description = "Paradas inferidas (ADR 0012) que se han quitado", example = "412") int paradasInferidasQuitadas,
            @Schema(description = "Paradas sin hora programada (anteriores a la V5) que se han quitado", example = "0") int paradasSinHoraQuitadas,
            @Schema(description = "Viajes cancelados con CANCELED explícito (no cuentan como retraso); null si no se sabe", example = "3") Integer cancelados) {
    }

    @Schema(description = "Cobertura: de lo que daba el horario, cuánto se vio en directo. Por viajes (alguna parada vista) y por paradas (un tren que solo se ve en 2 de sus 20 paradas cuenta entero en los viajes, pero no en las paradas)")
    public record Cobertura(
            @Schema(example = "2120") Integer viajesProgramados,
            @Schema(example = "1890") Integer viajesVistos,
            @Schema(description = "viajesVistos / viajesProgramados en %, con un decimal; null si no se sabe", example = "89.2") Double porcentaje,
            @Schema(example = "30410") Integer paradasProgramadas,
            @Schema(description = "Paradas con fila en retrasos (vistas, inferidas o sin hora)", example = "24800") Integer paradasVistas,
            @Schema(description = "paradasVistas / paradasProgramadas en %, con un decimal; null si no se sabe", example = "81.6") Double porcentajeParadas) {
    }

    @Schema(description = "Reparto de las paradas por tramos de retraso (los colores del mapa), en %, con un decimal")
    public record Porcentajes(
            @Schema(description = "Menos de 3 min (incluye los adelantos): «puntual»", example = "78.4") Double menosDe3,
            @Schema(example = "12.1") Double de3a5,
            @Schema(example = "7.9") Double de6a15,
            @Schema(example = "1.6") Double de16OMas) {
    }

    @Schema(description = "Puntualidad por parada. Con menos viajes que la muestra mínima, suficiente es false y las cifras van a null")
    public record Cifras(
            @Schema(example = "1843") int viajes,
            @Schema(example = "21904") int paradas,
            @Schema(description = "Hay viajes suficientes para enseñar la cifra (trainwatch.estadisticas.muestra-minima)", example = "true") boolean suficiente,
            @Schema(description = "Media de minutos de retraso al llegar a cada parada, con un decimal", example = "2.4") Double mediaMinutos,
            @Schema(description = "Mediana, en minutos enteros (0 = en hora o adelantado)", example = "1") Integer medianaMinutos,
            @Schema(description = "Percentil 90, en minutos enteros (60 = 60 o más)", example = "6") Integer p90Minutos,
            Porcentajes porcentajes) {
    }

    @Schema(description = "Por viaje: el retraso al llegar a su destino, solo de los viajes que se vieron llegar a él")
    public record LlegadaDestino(
            @Schema(description = "Viajes con alguna parada vista", example = "1843") int viajes,
            @Schema(description = "De ellos, los que se vieron en su destino (la última parada del horario): los de la cifra", example = "1520") int viajesEnDestino,
            @Schema(description = "Hay viajes en destino suficientes para enseñar la cifra", example = "true") boolean suficiente,
            @Schema(example = "3.1") Double mediaMinutos,
            @Schema(description = "% de esos viajes con menos de 3 min al llegar a su destino", example = "71.5") Double menosDe3) {
    }

    @Schema(description = "Una franja horaria: la hora programada de llegada a la parada, en hora de Madrid")
    public record Franja(@Schema(description = "De 0 a 23 (la 0, pasada la medianoche del mismo día de servicio)", example = "8") int hora,
            Cifras cifras) {
    }

    @Schema(description = "Un día de la semana (del día de servicio)")
    public record DiaSemana(
            @Schema(description = "1 = lunes … 7 = domingo", example = "1") int dia,
            @Schema(example = "lunes") String nombre,
            @Schema(description = "Cuántos días de este tipo hay en el periodo con datos", example = "4") int dias,
            Cifras cifras) {
    }

    @Schema(description = "Una semana (ISO, de lunes a domingo)")
    public record Semana(
            @Schema(example = "2026-11-23") LocalDate lunes,
            @Schema(description = "Días de esta semana dentro del periodo y con datos (menos de 7 en los bordes)", example = "7") int dias,
            Cifras cifras) {
    }

    @Schema(description = "Una estación")
    public record Parada(@Schema(example = "71801") String stopId,
            @Schema(description = "Del horario actual; null si ya no está", example = "Barcelona-Sants") String nombre) {
    }

    @Schema(description = "Un tramo entre dos paradas seguidas, en un sentido: los minutos que se ganan (negativo) o se pierden (positivo)")
    public record Tramo(Parada desde, Parada hasta,
            @Schema(description = "Trenes que pasaron por el tramo viéndose en las dos paradas", example = "240") int pasos,
            @Schema(example = "true") boolean suficiente,
            @Schema(description = "Media de minutos perdidos en el tramo (retraso al llegar a la segunda menos a la primera)", example = "1.3") Double mediaMinutos,
            @Schema(description = "% de pasos que pierden 2 min o más", example = "24.6") Double pierden2OMas,
            @Schema(description = "% de pasos que ganan 2 min o más", example = "2.1") Double ganan2OMas) {
    }

    @Schema(description = "Una línea en el ranking del núcleo")
    public record LineaRanking(
            @Schema(description = "Puesto por % de paradas con menos de 3 min (y luego por media); null con datos insuficientes", example = "1") Integer puesto,
            @Schema(example = "RL4") String linea,
            Cifras cifras,
            @Schema(description = "Cancelados explícitos en el periodo; null si no se sabe", example = "0") Integer cancelados,
            Cobertura cobertura) {
    }

    @Schema(description = "Un núcleo en el ranking de toda España")
    public record NucleoRanking(
            @Schema(description = "Puesto por % de paradas con menos de 3 min (y luego por media); null con datos insuficientes", example = "1") Integer puesto,
            @Schema(example = "51") int nucleo,
            @Schema(example = "Rodalies de Catalunya") String nombre,
            Cifras cifras,
            @Schema(description = "Cancelados explícitos en el periodo; null si no se sabe", example = "4") Integer cancelados,
            @Schema(description = "Cobertura del núcleo: con poca, la cifra es solo de los trenes que se ven") Cobertura cobertura) {
    }

    @Schema(description = "Toda España: el ranking de los núcleos (no de las líneas: sus nombres se repiten entre núcleos)")
    public record PuntualidadTodos(
            Publicacion publicacion, Periodo periodo,
            @Schema(description = "null antes de la publicación") Muestra muestra,
            @Schema(description = "Cobertura de todos los núcleos juntos; null antes de la publicación") Cobertura cobertura,
            List<NucleoRanking> nucleos,
            @Schema(example = FUENTE) String fuente) {
    }

    @Schema(description = "Puntualidad de un núcleo: ranking de sus líneas")
    public record PuntualidadNucleo(
            @Schema(example = "51") int nucleo,
            @Schema(example = "Rodalies de Catalunya") String nombre,
            Publicacion publicacion, Periodo periodo,
            @Schema(description = "null antes de la publicación") Muestra muestra,
            @Schema(description = "Cobertura del núcleo en el periodo; null antes de la publicación") Cobertura cobertura,
            List<LineaRanking> lineas,
            @Schema(example = FUENTE) String fuente) {
    }

    @Schema(description = "Puntualidad de una línea: por hora, por día de la semana, semana a semana y sus tramos con más retraso")
    public record PuntualidadLinea(
            @Schema(example = "51") int nucleo,
            @Schema(example = "Rodalies de Catalunya") String nombreNucleo,
            @Schema(example = "RL4") String linea,
            Publicacion publicacion, Periodo periodo, Muestra muestra,
            @Schema(description = "Cobertura de su núcleo en el periodo") Cobertura coberturaNucleo,
            @Schema(description = "Cobertura de la línea en el periodo") Cobertura coberturaLinea,
            @Schema(description = "Todo el periodo") Cifras resumen,
            LlegadaDestino llegadaDestino,
            @Schema(description = "Solo las horas con alguna parada") List<Franja> porHora,
            @Schema(description = "Los días de la semana que hay en el periodo") List<DiaSemana> porDiaSemana,
            List<Semana> porSemana,
            @Schema(description = "Los que más minutos pierden de media, solo con pasos suficientes") List<Tramo> tramos,
            @Schema(example = FUENTE) String fuente) {
    }

    @Schema(description = "Puntualidad de una estación, en total y por línea")
    public record LineaEnEstacion(@Schema(example = "51") int nucleo, @Schema(example = "R2 Sud") String linea,
            Cifras cifras) {
    }

    @Schema(description = "Puntualidad de una estación: los trenes que llegan a ella")
    public record PuntualidadEstacion(
            @Schema(example = "71801") String stopId,
            @Schema(example = "Barcelona-Sants") String nombre,
            Publicacion publicacion, Periodo periodo, Muestra muestra,
            @Schema(description = "Cobertura de su núcleo en el periodo") Cobertura coberturaNucleo,
            Cifras resumen, List<LineaEnEstacion> porLinea,
            @Schema(example = FUENTE) String fuente) {
    }
}
