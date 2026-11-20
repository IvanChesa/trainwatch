package com.ivanchesa.trainwatch.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.ivanchesa.trainwatch.RelojConfig;
import com.ivanchesa.trainwatch.api.Respuestas.ParadaProxima;
import com.ivanchesa.trainwatch.api.Respuestas.Salida;
import com.ivanchesa.trainwatch.api.Respuestas.TrenMapa;
import com.ivanchesa.trainwatch.directo.EstadoTren;
import com.ivanchesa.trainwatch.directo.ParadaViaje;
import com.ivanchesa.trainwatch.directo.PosicionEstimada;
import com.ivanchesa.trainwatch.directo.Tren;
import com.ivanchesa.trainwatch.directo.Viaje;

/**
 * De un {@link Tren} del estado en memoria a lo que ve el mapa ({@link TrenMapa}). Sin estado y sin base de datos:
 * todo sale del tren y de su viaje, que ya están en memoria.
 *
 * <p>Las coordenadas se redondean a 5 decimales (~1 m) y el rumbo a grados enteros: más precisión no se ve en el mapa
 * y haría que un tren parado «cambiara» en cada ciclo por el ruido de la estimación, engordando el directo.
 */
public final class VistaTrenes {

    private static final ZoneId ZONA = RelojConfig.ZONA;

    private VistaTrenes() {
    }

    public static TrenMapa mapa(Tren t) {
        Viaje viaje = t.viaje();
        PosicionEstimada e = t.estimada();
        Double lat = e != null ? Double.valueOf(e.lat()) : t.lat();
        Double lon = e != null ? Double.valueOf(e.lon()) : t.lon();
        String fuente = e != null ? e.fuente().name() : lat != null ? PosicionEstimada.Fuente.FEED.name() : null;
        Integer rumbo = e == null || e.rumbo() == null ? null : (int) Math.floorMod(Math.round(e.rumbo()), 360);
        return new TrenMapa(t.id(), t.linea(), t.nucleo(), t.estado(), destino(viaje), t.sinHorario(), redondear(lat),
                redondear(lon), fuente, rumbo, minutos(t.retrasoS()), proxima(t), salida(t), hora(t.marca()));
    }

    /** La próxima parada: la siguiente a la del feed (que es la actual o la última, no la próxima: fase 0). */
    static ParadaProxima proxima(Tren t) {
        int i = indiceProxima(t);
        if (i < 0) {
            return null;
        }
        ParadaViaje p = t.viaje().paradas().get(i);
        Instant programada = Viaje.instante(t.fechaServicio(), p.llegadaS(), ZONA);
        return new ParadaProxima(p.stopId(), p.nombre(), hora(programada), hora(conRetraso(programada, t)));
    }

    /**
     * Índice de la próxima parada en el viaje, o -1. En origen, la segunda (la primera es de donde sale); terminado o
     * cancelado, ninguna.
     */
    static int indiceProxima(Tren t) {
        Viaje viaje = t.viaje();
        if (viaje == null || t.fechaServicio() == null || t.estado() == EstadoTren.TERMINADO
                || t.estado() == EstadoTren.CANCELADO) {
            return -1;
        }
        int i = Math.max(t.indiceParada(), 0) + 1;
        if (t.indiceParada() < 0 && t.estado() != EstadoTren.EN_ORIGEN) {
            return -1; // en marcha sin saber dónde: mejor nada que una parada equivocada
        }
        return i < viaje.paradas().size() ? i : -1;
    }

    static Salida salida(Tren t) {
        if (t.estado() != EstadoTren.EN_ORIGEN || t.viaje() == null || t.viaje().paradas().isEmpty()
                || t.fechaServicio() == null) {
            return null;
        }
        Instant programada = Viaje.instante(t.fechaServicio(), t.viaje().paradas().getFirst().salidaS(), ZONA);
        return new Salida(hora(programada), hora(conRetraso(programada, t)));
    }

    /** La última parada del viaje (Renfe deja vacío el {@code trip_headsign}), o el letrero si lo trae. */
    static String destino(Viaje viaje) {
        if (viaje == null) {
            return null;
        }
        if (viaje.destino() != null && !viaje.destino().isBlank()) {
            return viaje.destino().trim();
        }
        List<ParadaViaje> p = viaje.paradas();
        return p.isEmpty() ? null : p.getLast().nombre();
    }

    static String origen(Viaje viaje) {
        return viaje == null || viaje.paradas().isEmpty() ? null : viaje.paradas().getFirst().nombre();
    }

    static Instant conRetraso(Instant programada, Tren t) {
        return t.retrasoS() == null ? programada : programada.plusSeconds(t.retrasoS());
    }

    static Integer minutos(Integer segundos) {
        return segundos == null ? null : (int) Math.round(segundos / 60.0);
    }

    static OffsetDateTime hora(Instant i) {
        return i == null ? null : i.atZone(ZONA).toOffsetDateTime();
    }

    static Double redondear(Double grados) {
        return grados == null ? null : Math.round(grados * 100_000) / 100_000.0;
    }
}
