package com.ivanchesa.trainwatch.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.ivanchesa.trainwatch.directo.EstadoTren;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Lo que devuelve la API: records inmutables, sin entidades ni filas de la base de datos (el contrato no cambia si
 * cambia el esquema). Las horas van en hora de Madrid con su desfase ({@code 2026-11-20T10:24:00+01:00}); los
 * retrasos, en minutos enteros, como los da Renfe. Un campo que no se sabe va a {@code null}, no se inventa.
 */
public final class Respuestas {

    private Respuestas() {
    }

    // ---------------------------------------------------------------- núcleos

    @Schema(description = "Un núcleo de Cercanías o Rodalies, con dónde centrar el mapa y el estado de su directo")
    public record Nucleo(
            @Schema(description = "Código del núcleo: las dos cifras con que empiezan sus líneas y viajes", example = "51") int id,
            @Schema(example = "Rodalies de Catalunya") String nombre,
            @Schema(description = "Centro de «limites»; null si el horario no tiene ninguna estación del núcleo") Punto centro,
            @Schema(description = "Zoom de MapLibre para ver «limites» en un mapa de unos 800 × 600 px", example = "7.6") Double zoom,
            @Schema(description = "Rectángulo donde está el servicio del núcleo, para encuadrar el mapa: el de sus estaciones sin las más apartadas que casi no tienen trenes (en cada eje, se quita por cada lado el 0,5 % de las horas de paso del horario); null si el horario no tiene ninguna") Limites limites,
            @Schema(description = "EN_DIRECTO, SIN_DATOS_EN_DIRECTO (el horario da trenes en marcha y el feed ninguno) o SIN_SERVICIO (el horario no da ninguno ahora)", example = "EN_DIRECTO") String estado,
            @Schema(description = "El estado para enseñarlo", example = "con directo") String estadoTexto,
            @Schema(description = "Trenes del núcleo en el feed, en cualquier estado salvo cancelado", example = "111") int trenesEnDirecto,
            @Schema(description = "Cuántos de los trenes que el horario da en marcha están en el feed; null si aún no hay datos") Cobertura cobertura) {
    }

    @Schema(description = "Un punto en WGS 84")
    public record Punto(@Schema(example = "41.3870") double lat, @Schema(example = "2.1700") double lon) {
    }

    @Schema(description = "Rectángulo en WGS 84")
    public record Limites(double latMin, double lonMin, double latMax, double lonMax) {
    }

    @Schema(description = "Cobertura del directo: trenes en marcha según el horario y, de ellos, los que están en el feed")
    public record Cobertura(
            @Schema(example = "65") int enMarchaHorario,
            @Schema(example = "55") int enMarchaVistos,
            @Schema(description = "enMarchaVistos / enMarchaHorario en %, con un decimal; null si el horario no da ninguno", example = "84.6") Double porcentaje) {
    }

    // ---------------------------------------------------------------- trenes en el mapa (también el directo)

    @Schema(description = "Un tren en el mapa: lo que manda el directo (SSE) y GET /api/nucleos/{id}/trenes")
    public record TrenMapa(
            @Schema(description = "Identificador para GET /api/trenes/{id}: el trip_id de Renfe, o «vehiculo:…» si el feed no trae viaje", example = "5123R15001R4") String id,
            @Schema(example = "R4") String linea,
            @Schema(example = "51") Integer nucleo,
            @Schema(description = "EN_ORIGEN (en su estación de origen, antes de salir), EN_MARCHA, TERMINADO o CANCELADO") EstadoTren estado,
            @Schema(description = "Última parada del viaje; null sin horario", example = "Manresa") String destino,
            @Schema(description = "El viaje no está en el horario de Renfe: se pinta con lo que trae el feed") boolean sinHorario,
            @Schema(description = "Posición estimada (o la del feed); null si no se sabe", example = "41.54321") Double lat,
            @Schema(example = "2.10987") Double lon,
            @Schema(description = "RECORRIDO: estimada con el horario, el retraso y el trazado de la línea; FEED: las coordenadas del feed tal cual", example = "RECORRIDO") String fuentePosicion,
            @Schema(description = "Grados desde el norte hacia donde va; null si no se sabe", example = "312") Integer rumbo,
            @Schema(description = "Retraso en minutos para la próxima parada; null si no se sabe", example = "4") Integer retrasoMin,
            @Schema(description = "La próxima parada del viaje; null si no se sabe o ha terminado") ParadaProxima proximaParada,
            @Schema(description = "Solo si está EN_ORIGEN: cuándo sale") Salida salida,
            @Schema(description = "Marca del tren en el feed") OffsetDateTime actualizado) {
    }

    @Schema(description = "La próxima parada de un tren, con la hora programada y la estimada (programada + retraso)")
    public record ParadaProxima(
            @Schema(example = "72400") String id,
            @Schema(example = "Terrassa Est") String nombre,
            OffsetDateTime llegadaProgramada,
            OffsetDateTime llegadaEstimada) {
    }

    @Schema(description = "La salida de un tren que aún está en su estación de origen")
    public record Salida(OffsetDateTime programada, OffsetDateTime estimada) {
    }

    // ---------------------------------------------------------------- ficha del tren

    @Schema(description = "La ficha de un tren en directo")
    public record FichaTren(
            @Schema(example = "5123R15001R4") String id,
            @Schema(description = "trip_id de Renfe; null si el feed no trae viaje") String viaje,
            @Schema(description = "Identificador del vehículo en el feed") String vehiculo,
            @Schema(example = "R4") String linea,
            @Schema(example = "51") Integer nucleo,
            @Schema(example = "Rodalies de Catalunya") String nombreNucleo,
            EstadoTren estado,
            boolean sinHorario,
            @Schema(description = "Día de servicio del viaje (puede ser el de ayer si pasa de medianoche)") LocalDate fechaServicio,
            @Schema(example = "Sant Vicenç de Calders") String origen,
            @Schema(example = "Manresa") String destino,
            @Schema(description = "Retraso actual en minutos (para la próxima parada)", example = "4") Integer retrasoMin,
            Double lat,
            Double lon,
            String fuentePosicion,
            Integer rumbo,
            OffsetDateTime actualizado,
            @Schema(description = "Paradas del viaje en orden; vacía sin horario") List<ParadaFicha> paradas,
            @Schema(description = "Evolución del retraso: el retraso final con que dejó atrás cada parada en este viaje") List<PuntoEvolucion> evolucion,
            @Schema(description = "Avisos de Renfe sobre su línea") List<Aviso> avisos) {
    }

    @Schema(description = "Una parada del viaje en la ficha del tren")
    public record ParadaFicha(
            @Schema(example = "7") int secuencia,
            @Schema(example = "72400") String id,
            @Schema(example = "Terrassa Est") String nombre,
            double lat,
            double lon,
            OffsetDateTime llegadaProgramada,
            OffsetDateTime salidaProgramada,
            @Schema(description = "Programada + retraso actual, solo en las paradas que faltan; null en las pasadas") OffsetDateTime llegadaEstimada,
            @Schema(description = "PASADA, ACTUAL (está o acaba de salir), PROXIMA o PENDIENTE", example = "PROXIMA") String situacion,
            @Schema(description = "Retraso final con que la dejó atrás, si ya se ha cerrado", example = "3") Integer retrasoFinalMin,
            @Schema(description = "Si ese retraso final es inferido (se la saltó entre dos muestras del feed)") Boolean inferido) {
    }

    @Schema(description = "Un punto de la evolución del retraso en el viaje")
    public record PuntoEvolucion(
            @Schema(example = "6") int secuencia,
            @Schema(example = "72305") String parada,
            @Schema(example = "Terrassa Rambla") String nombre,
            @Schema(description = "Retraso final en minutos", example = "3") int retrasoMin,
            boolean inferido,
            @Schema(description = "Hora programada de llegada a la parada") OffsetDateTime llegadaProgramada,
            @Schema(description = "Cuándo se cerró") OffsetDateTime registrado) {
    }

    // ---------------------------------------------------------------- estaciones

    @Schema(description = "La ficha de una estación: líneas, próximos trenes con su retraso y avisos")
    public record FichaEstacion(
            @Schema(example = "71801") String id,
            @Schema(example = "Barcelona-Sants") String nombre,
            double lat,
            double lon,
            List<LineaEstacion> lineas,
            @Schema(description = "Trenes que pasan en los próximos 60 min (y los que, con retraso, aún no han pasado), por hora estimada") List<ProximoTren> proximos,
            @Schema(description = "Avisos de Renfe sobre la estación o sus líneas") List<Aviso> avisos,
            @Schema(description = "Hora de la consulta") OffsetDateTime consultado) {
    }

    @Schema(description = "Una línea que para en la estación")
    public record LineaEstacion(
            @Schema(example = "51") Integer nucleo,
            @Schema(example = "R4") String linea,
            @Schema(description = "Color de la línea en el GTFS de Renfe", example = "#F7A30E") String color,
            String colorTexto) {
    }

    @Schema(description = "Un tren que va a pasar por la estación")
    public record ProximoTren(
            @Schema(description = "Para GET /api/trenes/{id}, si está en directo", example = "5123R15001R4") String id,
            @Schema(example = "R4") String linea,
            @Schema(example = "51") Integer nucleo,
            @Schema(example = "Manresa") String destino,
            OffsetDateTime salidaProgramada,
            @Schema(description = "Programada + retraso; igual que la programada si no está en directo") OffsetDateTime salidaEstimada,
            @Schema(description = "null si no está en directo", example = "4") Integer retrasoMin,
            @Schema(description = "Si el tren está en el feed ahora") boolean enDirecto,
            @Schema(description = "Su estado en directo; null si no está en el feed") EstadoTren estado) {
    }

    @Schema(description = "Una estación cerca de un punto")
    public record EstacionCercana(
            @Schema(example = "71801") String id,
            @Schema(example = "Barcelona-Sants") String nombre,
            double lat,
            double lon,
            @Schema(description = "Distancia en metros, sobre el elipsoide", example = "420") int distanciaM) {
    }

    // ---------------------------------------------------------------- líneas y avisos

    @Schema(description = "Una línea con su recorrido, sus paradas y sus avisos")
    public record Linea(
            @Schema(example = "51") int nucleo,
            @Schema(example = "Rodalies de Catalunya") String nombreNucleo,
            @Schema(example = "R4") String linea,
            @Schema(description = "Nombre largo del GTFS") String nombreLargo,
            @Schema(example = "#F7A30E") String color,
            String colorTexto,
            @Schema(description = "route_id de Renfe que forman la línea") List<String> rutas,
            @Schema(description = "Recorridos de la línea en GeoJSON (FeatureCollection de LineString, una por shape_id)", type = "object") JsonNode recorrido,
            @Schema(description = "Paradas, en el orden del recorrido más usado") List<ParadaLinea> paradas,
            List<Aviso> avisos) {
    }

    @Schema(description = "Una parada de la línea")
    public record ParadaLinea(
            @Schema(example = "72400") String id,
            @Schema(example = "Terrassa Est") String nombre,
            double lat,
            double lon) {
    }

    @Schema(description = "Un aviso de Renfe (alerts.pb), con las líneas y las estaciones a las que afecta")
    public record Aviso(
            @Schema(example = "AVISO_1234") String id,
            @Schema(description = "Texto en español si Renfe lo da") String texto,
            @Schema(description = "Desde cuándo vale; null si no lo dice") OffsetDateTime inicio,
            @Schema(description = "Hasta cuándo vale; null si no lo dice") OffsetDateTime fin,
            @Schema(description = "Núcleos afectados") List<Integer> nucleos,
            @Schema(description = "Líneas afectadas (nombre corto)") List<String> lineas,
            @Schema(description = "Estaciones afectadas (stop_id)") List<String> estaciones) {
    }

    // ---------------------------------------------------------------- directo (SSE)

    @Schema(description = "Evento «instantanea» del directo: todos los trenes del núcleo (o de todos los núcleos). El primero de cada conexión")
    public record Instantanea(
            @Schema(description = "Número de la última actualización incluida (también es el id del evento)", example = "1532") long secuencia,
            @Schema(description = "Código del núcleo; null en el directo de todos los núcleos (nucleo=todos)", example = "51", nullable = true) Integer nucleo,
            @Schema(description = "Cuándo se calculó") OffsetDateTime generado,
            @Schema(description = "Marca de la última muestra del feed de posiciones; null si aún no ha llegado") OffsetDateTime ultimoDato,
            List<TrenMapa> trenes) {
    }

    @Schema(description = "Evento «cambios» del directo: lo que ha cambiado desde la secuencia anterior")
    public record Cambios(
            @Schema(example = "1533") long secuencia,
            @Schema(description = "Código del núcleo; null en el directo de todos los núcleos (nucleo=todos)", example = "51", nullable = true) Integer nucleo,
            OffsetDateTime generado,
            OffsetDateTime ultimoDato,
            @Schema(description = "Trenes nuevos en el núcleo") List<TrenMapa> altas,
            @Schema(description = "Trenes que han cambiado (posición, estado, retraso…), enteros") List<TrenMapa> cambios,
            @Schema(description = "Ids de los trenes que ya no están") List<String> bajas) {
    }

    @Schema(description = "Evento «latido» del directo, cada ~15 s: la conexión sigue viva y cómo de frescos son los datos")
    public record Latido(
            OffsetDateTime instante,
            OffsetDateTime ultimoDato,
            @Schema(description = "Segundos desde el último dato del feed; null si aún no ha llegado ninguno", example = "12") Long antiguedadS) {
    }
}
