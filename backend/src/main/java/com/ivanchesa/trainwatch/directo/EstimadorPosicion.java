package com.ivanchesa.trainwatch.directo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.ivanchesa.trainwatch.directo.PosicionEstimada.Fuente;

/**
 * Estima dónde está un tren (cambio 1 tras la fase 0; ADR 0010).
 *
 * <p>El feed pone a los trenes casi siempre en una estación: su {@code stop_id} es la parada actual o la última.
 * Con esa parada (A), la siguiente del viaje (B), sus horas programadas más el retraso y la proyección de las dos
 * sobre el recorrido (fase 2):
 *
 * <pre>
 *   sale   = salida(A) + retraso          llega = llegada(B) + retraso
 *   t      = (ahora - sale) / (llega - sale), entre 0 y 1
 *   avance = avance(A) + t · (avance(B) - avance(A))     (módulo 1 en los recorridos cerrados)
 *   fracción del recorrido = avance si el viaje va en el sentido del dibujo; 1 - avance si va al revés
 * </pre>
 *
 * Con la fracción salen las coordenadas (un punto del recorrido) y el rumbo (el del tramo en que cae). Cuando llega
 * una posición nueva del feed con otra parada, A cambia y la estimación se corrige sola: nunca se adelanta a B
 * aunque el tren vaya tarde (t no pasa de 1), y si el tren va adelantado, el feed la pone en B.
 *
 * <p>Sin recorrido (viaje sin horario o que no encaja, el 6,5 %), la posición es la del feed tal cual, y el rumbo,
 * el del último desplazamiento de más de 30 m entre dos posiciones del feed.
 */
final class EstimadorPosicion {

    /** Por debajo de esto, dos posiciones del feed son el mismo sitio y no dan rumbo. */
    static final double DESPLAZAMIENTO_MINIMO_M = 30;

    private EstimadorPosicion() {
    }

    static PosicionEstimada estimar(Tren tren, Tren anterior, Instant ahora, ZoneId zona) {
        PosicionEstimada sobreRecorrido = sobreRecorrido(tren, ahora, zona);
        if (sobreRecorrido != null) {
            return sobreRecorrido;
        }
        PosicionEstimada previa = anterior == null ? null : anterior.estimada();
        if (tren.lat() == null || tren.lon() == null) {
            // Sin coordenadas (stop_id «00000»): se queda donde estaba, si se sabía.
            return previa;
        }
        Double rumbo = previa == null ? null : previa.rumbo();
        if (anterior != null && anterior.lat() != null && anterior.lon() != null && Recorrido.distanciaM(anterior.lat(),
                anterior.lon(), tren.lat(), tren.lon()) >= DESPLAZAMIENTO_MINIMO_M) {
            rumbo = Recorrido.rumboEntre(anterior.lat(), anterior.lon(), tren.lat(), tren.lon());
        }
        return new PosicionEstimada(tren.lat(), tren.lon(), null, rumbo, Fuente.FEED, ahora);
    }

    private static PosicionEstimada sobreRecorrido(Tren tren, Instant ahora, ZoneId zona) {
        Viaje viaje = tren.viaje();
        if (viaje == null || viaje.recorrido() == null || tren.indiceParada() < 0 || tren.fechaServicio() == null
                || tren.estado() == EstadoTren.CANCELADO) {
            return null;
        }
        List<ParadaViaje> paradas = viaje.paradas();
        int i = tren.indiceParada();
        ParadaViaje a = paradas.get(i);
        if (a.avance() == null) {
            return null;
        }
        Recorrido recorrido = viaje.recorrido();
        double avance = a.avance();
        if (tren.estado() == EstadoTren.EN_MARCHA && i + 1 < paradas.size() && paradas.get(i + 1).avance() != null) {
            ParadaViaje b = paradas.get(i + 1);
            long retraso = tren.retrasoS() == null ? 0 : tren.retrasoS();
            Instant sale = Viaje.instante(tren.fechaServicio(), a.salidaS(), zona).plusSeconds(retraso);
            Instant llega = Viaje.instante(tren.fechaServicio(), b.llegadaS(), zona).plusSeconds(retraso);
            double delta = b.avance() - a.avance();
            if (recorrido.cerrado() && delta < 0) {
                delta += 1; // de 0,9 a 0,1 en un recorrido circular es avanzar 0,2
            }
            avance += fraccionDeTiempo(sale, llega, ahora) * delta;
        }
        avance = recorrido.cerrado() ? avance - Math.floor(avance) : Math.clamp(avance, 0.0, 1.0);
        double fraccion = viaje.sentido() >= 0 ? avance : 1 - avance;
        double[] punto = recorrido.punto(fraccion);
        return new PosicionEstimada(punto[0], punto[1], fraccion, recorrido.rumbo(fraccion, viaje.sentido()),
                Fuente.RECORRIDO, ahora);
    }

    /** Qué parte del tramo de {@code sale} a {@code llega} ha pasado en {@code ahora}, entre 0 y 1. */
    static double fraccionDeTiempo(Instant sale, Instant llega, Instant ahora) {
        long total = Duration.between(sale, llega).toMillis();
        if (total <= 0) {
            return ahora.isBefore(sale) ? 0 : 1;
        }
        return Math.clamp((double) Duration.between(sale, ahora).toMillis() / total, 0.0, 1.0);
    }
}
