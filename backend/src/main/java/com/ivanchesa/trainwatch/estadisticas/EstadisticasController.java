package com.ivanchesa.trainwatch.estadisticas;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivanchesa.trainwatch.RelojConfig;
import com.ivanchesa.trainwatch.api.Problemas;
import com.ivanchesa.trainwatch.api.RespuestasDeError;
import com.ivanchesa.trainwatch.directo.Nucleos;
import com.ivanchesa.trainwatch.estadisticas.ConsultasEstadisticas.EstacionDia;
import com.ivanchesa.trainwatch.estadisticas.ConsultasEstadisticas.LineaDia;
import com.ivanchesa.trainwatch.estadisticas.ConsultasEstadisticas.LineaHora;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Cobertura;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.DiaSemana;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Franja;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.LineaEnEstacion;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.LineaRanking;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Muestra;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Parada;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Periodo;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Publicacion;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadEstacion;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadLinea;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.PuntualidadNucleo;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Semana;
import com.ivanchesa.trainwatch.estadisticas.RespuestasEstadisticas.Tramo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * La API de estadísticas de puntualidad (ADR 0022): ranking de las líneas de un núcleo, una línea por hora, día y
 * semana con sus tramos, y una estación. Lee solo los agregados de la V6, que cambian una vez por noche: se puede
 * guardar 1 h y lleva {@code ETag}.
 */
@RestController
@RequestMapping("/api/estadisticas")
@Tag(name = "Estadísticas", description = "Puntualidad propia, calculada con los trenes vistos en directo")
public class EstadisticasController {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String[] DIAS = { "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo" };
    private static final String DESCRIPCION_PERIODO = " Con desde y hasta (días de servicio, ambos incluidos); por"
            + " defecto, las últimas 4 semanas hasta ayer. Antes de la fecha de publicación"
            + " (trainwatch.estadisticas.publicar-desde) responde igual, pero sin ninguna cifra.";

    private final ConsultasEstadisticas consultas;
    private final EstadisticasProperties propiedades;
    private final Clock reloj;

    public EstadisticasController(ConsultasEstadisticas consultas, EstadisticasProperties propiedades, Clock reloj) {
        this.consultas = consultas;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    // ---------------------------------------------------------------- núcleo

    @GetMapping("/nucleos/{nucleo}")
    @Operation(operationId = "puntualidadNucleo", summary = "Ranking de las líneas de un núcleo",
            description = "Las líneas ordenadas por el % de paradas con menos de 3 min y luego por la media; las que"
                    + " no llegan a la muestra mínima van al final, sin puesto." + DESCRIPCION_PERIODO)
    @RespuestasDeError
    public ResponseEntity<PuntualidadNucleo> nucleo(
            @Parameter(description = "Código del núcleo", example = "51") @PathVariable int nucleo,
            @Parameter(description = "Primer día de servicio", example = "2026-11-21") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Último día de servicio", example = "2026-12-18") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Periodo periodo = periodo(desde, hasta);
        if (!consultas.hayNucleo(nucleo)) {
            throw Problemas.noEncontrado("No hay ningún núcleo " + nucleo + ".");
        }
        Publicacion publicacion = publicacion();
        if (!publicacion.publicado()) {
            return ok(new PuntualidadNucleo(nucleo, Nucleos.nombre(nucleo), publicacion, periodo, null, null, List.of(),
                    RespuestasEstadisticas.FUENTE));
        }
        List<LineaDia> dias = consultas.lineaDia(nucleo, null, periodo.desde(), periodo.hasta());
        List<LineaHora> horas = consultas.lineaHora(nucleo, null, periodo.desde(), periodo.hasta());
        Map<String, Acumulado> porLinea = new TreeMap<>();
        for (LineaHora h : horas) {
            porLinea.computeIfAbsent(h.linea(), k -> new Acumulado()).sumar(h.fila());
        }
        Map<String, List<LineaDia>> diasPorLinea = new TreeMap<>();
        for (LineaDia d : dias) {
            diasPorLinea.computeIfAbsent(d.linea(), k -> new ArrayList<>()).add(d);
            porLinea.computeIfAbsent(d.linea(), k -> new Acumulado());
        }
        List<LineaRanking> lineas = new ArrayList<>();
        for (Map.Entry<String, Acumulado> e : porLinea.entrySet()) {
            List<LineaDia> suyas = diasPorLinea.getOrDefault(e.getKey(), List.of());
            int viajes = suyas.stream().mapToInt(LineaDia::viajes).sum();
            lineas.add(new LineaRanking(null, e.getKey(), propiedades.destacada(nucleo, e.getKey()),
                    e.getValue().cifras(viajes, propiedades.muestraMinima()), cancelados(suyas), cobertura(suyas)));
        }
        return ok(new PuntualidadNucleo(nucleo, Nucleos.nombre(nucleo), publicacion, periodo, muestra(dias, horas),
                cobertura(dias), ranking(lineas), RespuestasEstadisticas.FUENTE));
    }

    /** Por % de menos de 3 min (mayor primero), luego por media (menor primero) y por nombre; las insuficientes, al final. */
    static List<LineaRanking> ranking(List<LineaRanking> lineas) {
        List<LineaRanking> suficientes = new ArrayList<>(lineas.stream().filter(l -> l.cifras().suficiente()).toList());
        suficientes.sort(Comparator
                .comparing((LineaRanking l) -> l.cifras().porcentajes().menosDe3(), Comparator.reverseOrder())
                .thenComparing(l -> l.cifras().mediaMinutos())
                .thenComparing(LineaRanking::linea));
        List<LineaRanking> resultado = new ArrayList<>();
        for (int i = 0; i < suficientes.size(); i++) {
            LineaRanking l = suficientes.get(i);
            resultado.add(new LineaRanking(i + 1, l.linea(), l.destacada(), l.cifras(), l.cancelados(), l.cobertura()));
        }
        lineas.stream().filter(l -> !l.cifras().suficiente()).sorted(Comparator.comparing(LineaRanking::linea))
                .forEach(resultado::add);
        return resultado;
    }

    // ---------------------------------------------------------------- línea

    @GetMapping("/lineas/{nucleo}/{linea}")
    @Operation(operationId = "puntualidadLinea", summary = "Puntualidad de una línea",
            description = "Todo el periodo, la llegada a la última parada vista de cada viaje, por hora programada (hora"
                    + " de Madrid), por día de la semana, semana a semana y los tramos que más minutos pierden."
                    + DESCRIPCION_PERIODO)
    @RespuestasDeError
    public ResponseEntity<PuntualidadLinea> linea(
            @Parameter(description = "Código del núcleo", example = "51") @PathVariable int nucleo,
            @Parameter(description = "Nombre corto de la línea, sin distinguir mayúsculas", example = "RL4") @PathVariable String linea,
            @Parameter(description = "Primer día de servicio", example = "2026-11-21") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Último día de servicio", example = "2026-12-18") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Periodo periodo = periodo(desde, hasta);
        String nombre = consultas.linea(nucleo, linea).orElseThrow(() -> Problemas.noEncontrado(
                "No hay ninguna línea «" + linea + "» en el núcleo " + nucleo + "."));
        boolean destacada = propiedades.destacada(nucleo, nombre);
        Publicacion publicacion = publicacion();
        if (!publicacion.publicado()) {
            return ok(new PuntualidadLinea(nucleo, Nucleos.nombre(nucleo), nombre, destacada, publicacion, periodo,
                    null, null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                    RespuestasEstadisticas.FUENTE));
        }
        int minimo = propiedades.muestraMinima();
        List<LineaDia> delNucleo = consultas.lineaDia(nucleo, null, periodo.desde(), periodo.hasta());
        List<LineaDia> dias = delNucleo.stream().filter(d -> d.linea().equals(nombre)).toList();
        List<LineaHora> horas = consultas.lineaHora(nucleo, nombre, periodo.desde(), periodo.hasta());
        List<LocalDate> calculados = consultas.diasCalculados(periodo.desde(), periodo.hasta());

        Acumulado total = new Acumulado();
        horas.forEach(h -> total.sumar(h.fila()));
        int viajes = dias.stream().mapToInt(LineaDia::viajes).sum();

        Map<Integer, Acumulado> porHora = new TreeMap<>();
        Map<Integer, Integer> viajesPorHora = new TreeMap<>();
        for (LineaHora h : horas) {
            porHora.computeIfAbsent(h.hora(), k -> new Acumulado()).sumar(h.fila());
            viajesPorHora.merge(h.hora(), h.fila().viajes(), Integer::sum);
        }
        List<Franja> franjas = new ArrayList<>();
        porHora.forEach((hora, a) -> franjas.add(new Franja(hora, a.cifras(viajesPorHora.get(hora), minimo))));

        List<DiaSemana> porDia = new ArrayList<>();
        agrupar(horas, dias, calculados, f -> f.getDayOfWeek().getValue()).forEach((dia, g) -> porDia.add(
                new DiaSemana(dia, DIAS[dia - 1], g.dias(), g.acumulado().cifras(g.viajes(), minimo))));
        List<Semana> porSemana = new ArrayList<>();
        agrupar(horas, dias, calculados, f -> (int) f.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toEpochDay()).forEach((lunes, g) -> porSemana.add(
                        new Semana(LocalDate.ofEpochDay(lunes), g.dias(), g.acumulado().cifras(g.viajes(), minimo))));

        List<Tramo> tramos = consultas.tramos(nucleo, nombre, periodo.desde(), periodo.hasta(), minimo,
                propiedades.maximoTramos()).stream()
                .map(t -> new Tramo(new Parada(t.desde(), t.nombreDesde()), new Parada(t.hasta(), t.nombreHasta()),
                        t.pasos(), true, Acumulado.decimal((double) t.suma() / t.pasos()),
                        Acumulado.porcentaje(t.pierden(), t.pasos()), Acumulado.porcentaje(t.ganan(), t.pasos())))
                .toList();

        return ok(new PuntualidadLinea(nucleo, Nucleos.nombre(nucleo), nombre, destacada, publicacion, periodo,
                muestra(dias, horas), cobertura(delNucleo), cobertura(dias), total.cifras(viajes, minimo),
                total.llegadaFinal(minimo), franjas, porDia, porSemana, tramos, RespuestasEstadisticas.FUENTE));
    }

    private record Grupo(Acumulado acumulado, int viajes, int dias) {
    }

    /**
     * Agrupa por una clave del día de servicio (día de la semana o lunes de la semana): las paradas de las filas por
     * hora, los viajes de las filas por día y cuántos días calculados caen en cada grupo.
     */
    private static Map<Integer, Grupo> agrupar(List<LineaHora> horas, List<LineaDia> dias, List<LocalDate> calculados,
            Function<LocalDate, Integer> clave) {
        Map<Integer, Acumulado> acumulados = new TreeMap<>();
        Map<Integer, Integer> viajes = new TreeMap<>();
        Map<Integer, Integer> cuantos = new TreeMap<>();
        for (LocalDate f : calculados) {
            cuantos.merge(clave.apply(f), 1, Integer::sum);
            acumulados.computeIfAbsent(clave.apply(f), k -> new Acumulado());
        }
        for (LineaHora h : horas) {
            acumulados.computeIfAbsent(clave.apply(h.fecha()), k -> new Acumulado()).sumar(h.fila());
        }
        for (LineaDia d : dias) {
            viajes.merge(clave.apply(d.fecha()), d.viajes(), Integer::sum);
        }
        Map<Integer, Grupo> grupos = new TreeMap<>();
        acumulados.forEach((k, a) -> grupos.put(k, new Grupo(a, viajes.getOrDefault(k, 0), cuantos.getOrDefault(k, 0))));
        return grupos;
    }

    // ---------------------------------------------------------------- estación

    @GetMapping("/estaciones/{stopId}")
    @Operation(operationId = "puntualidadEstacion", summary = "Puntualidad de una estación",
            description = "Los trenes que llegan a la estación, en total y por línea." + DESCRIPCION_PERIODO)
    @RespuestasDeError
    public ResponseEntity<PuntualidadEstacion> estacion(
            @Parameter(description = "stop_id de la estación", example = "71801") @PathVariable String stopId,
            @Parameter(description = "Primer día de servicio", example = "2026-11-21") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Último día de servicio", example = "2026-12-18") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Periodo periodo = periodo(desde, hasta);
        if (!consultas.hayEstacion(stopId)) {
            throw Problemas.noEncontrado("No hay ninguna estación «" + stopId + "».");
        }
        String nombre = consultas.nombreEstacion(stopId).orElse(null);
        Publicacion publicacion = publicacion();
        if (!publicacion.publicado()) {
            return ok(new PuntualidadEstacion(stopId, nombre, publicacion, periodo, null, null, null, List.of(),
                    RespuestasEstadisticas.FUENTE));
        }
        int minimo = propiedades.muestraMinima();
        List<EstacionDia> filas = consultas.estacionDia(stopId, periodo.desde(), periodo.hasta());
        Acumulado total = new Acumulado();
        Map<String, Acumulado> porLinea = new TreeMap<>();
        Map<String, Integer> viajesPorLinea = new TreeMap<>();
        Set<Integer> nucleos = new TreeSet<>();
        int viajes = 0;
        int inferidas = 0;
        for (EstacionDia f : filas) {
            String clave = f.nucleo() + ":" + f.linea();
            total.sumar(f.fila());
            porLinea.computeIfAbsent(clave, k -> new Acumulado()).sumar(f.fila());
            viajesPorLinea.merge(clave, f.fila().viajes(), Integer::sum);
            nucleos.add(f.nucleo());
            viajes += f.fila().viajes();
            inferidas += f.inferidas();
        }
        List<LineaEnEstacion> lineas = new ArrayList<>();
        porLinea.forEach((clave, a) -> {
            int i = clave.indexOf(':');
            lineas.add(new LineaEnEstacion(Integer.parseInt(clave.substring(0, i)), clave.substring(i + 1),
                    a.cifras(viajesPorLinea.get(clave), minimo)));
        });
        Cobertura cobertura = nucleos.isEmpty() ? null
                : cobertura(consultas.lineaDiaDeNucleos(List.copyOf(nucleos), periodo.desde(), periodo.hasta()));
        return ok(new PuntualidadEstacion(stopId, nombre, publicacion, periodo,
                new Muestra(viajes, total.paradas, inferidas, 0, null), cobertura, total.cifras(viajes, minimo), lineas,
                RespuestasEstadisticas.FUENTE));
    }

    // ---------------------------------------------------------------- comunes

    /** El periodo pedido: por defecto, las últimas {@code ventanaDias} hasta ayer (día de Madrid). */
    Periodo periodo(LocalDate desde, LocalDate hasta) {
        LocalDate ayer = hoy().minusDays(1);
        LocalDate h = hasta != null ? hasta : ayer;
        LocalDate d = desde != null ? desde : h.minusDays(propiedades.ventanaDias() - 1L);
        if (d.isAfter(h)) {
            throw Problemas.parametroInvalido("desde", "«desde» (" + d + ") no puede ser posterior a «hasta» (" + h + ").");
        }
        if (ChronoUnit.DAYS.between(d, h) + 1 > propiedades.periodoMaximoDias()) {
            throw Problemas.parametroInvalido("hasta",
                    "El periodo no puede pasar de " + propiedades.periodoMaximoDias() + " días.");
        }
        return new Periodo(d, h, consultas.diasCalculados(d, h).size());
    }

    Publicacion publicacion() {
        LocalDate desde = consultas.primerDia().orElse(null);
        LocalDate cifras = propiedades.publicarDesde();
        boolean publicado = cifras == null || !hoy().isBefore(cifras);
        String texto;
        if (publicado) {
            texto = desde == null ? "Aún no hay datos." : "Datos recogidos desde el " + FECHA.format(desde) + ".";
        } else {
            texto = (desde == null ? "Aún no hay datos" : "Recogiendo datos desde el " + FECHA.format(desde))
                    + "; cifras a partir del " + FECHA.format(cifras) + ".";
        }
        return new Publicacion(publicado, desde, cifras, texto);
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(RelojConfig.ZONA));
    }

    static Muestra muestra(List<LineaDia> dias, List<LineaHora> horas) {
        int paradas = horas.stream().mapToInt(h -> h.fila().paradas()).sum();
        return new Muestra(dias.stream().mapToInt(LineaDia::viajes).sum(), paradas,
                dias.stream().mapToInt(LineaDia::inferidas).sum(), dias.stream().mapToInt(LineaDia::sinHora).sum(),
                cancelados(dias));
    }

    /** La suma de los cancelados de los días en que se sabe; {@code null} si no se sabe en ninguno. */
    static Integer cancelados(List<LineaDia> dias) {
        return dias.stream().anyMatch(d -> d.cancelados() != null)
                ? dias.stream().filter(d -> d.cancelados() != null).mapToInt(LineaDia::cancelados).sum()
                : null;
    }

    /** De los días en que se sabe cuántos viajes daba el horario, cuántos se vieron (como mucho, el 100 %). */
    static Cobertura cobertura(List<LineaDia> dias) {
        long programados = 0;
        long vistos = 0;
        boolean alguno = false;
        for (LineaDia d : dias) {
            if (d.programados() != null) {
                alguno = true;
                programados += d.programados();
                vistos += d.viajesConDatos();
            }
        }
        if (!alguno) {
            return new Cobertura(null, null, null);
        }
        Double porcentaje = programados == 0 ? null : Math.min(100.0, Acumulado.porcentaje(vistos, programados));
        return new Cobertura((int) programados, (int) vistos, porcentaje);
    }

    private static <T> ResponseEntity<T> ok(T cuerpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(cuerpo);
    }
}
