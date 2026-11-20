/**
 * Movimiento suave de los trenes entre dos actualizaciones del directo.
 *
 * La API manda, en cada ciclo de la ingesta (~7 s), la posición **estimada** de cada tren sobre su recorrido. Aquí no
 * se inventa nada: cuando llega una posición nueva, el tren va en línea recta desde donde se le ve ahora hasta ella,
 * a velocidad constante, durante lo que se espera que tarde la actualización siguiente. Así el tren llega a la posición
 * nueva justo cuando llega la siguiente, sin paradas ni saltos (a cambio de ir un ciclo por detrás de la estimación).
 *
 * Es lógica pura (sin MapLibre ni el DOM): el reloj se pasa como parámetro, lo que la hace fácil de probar.
 */

export type LonLat = readonly [number, number];

interface Movimiento {
  desde: LonLat;
  hasta: LonLat;
  inicio: number;
  duracion: number;
}

/** Duración de partida: un ciclo de la ingesta en producción (fase 4). */
export const DURACION_INICIAL_MS = 7000;
const DURACION_MIN_MS = 1000;
const DURACION_MAX_MS = 20000;
/** Más lejos que esto, se teletransporta: es un cambio de posición del feed o de viaje, no movimiento. */
export const SALTO_MAXIMO_M = 5000;

export function distanciaM(a: LonLat, b: LonLat): number {
  // Equirectangular: de sobra para distancias de unos pocos kilómetros.
  const rad = Math.PI / 180;
  const x = (b[0] - a[0]) * rad * Math.cos(((a[1] + b[1]) / 2) * rad);
  const y = (b[1] - a[1]) * rad;
  return Math.sqrt(x * x + y * y) * 6_371_000;
}

function iguales(a: LonLat, b: LonLat): boolean {
  return a[0] === b[0] && a[1] === b[1];
}

function posicionEn(m: Movimiento, ahora: number): LonLat {
  if (m.duracion <= 0 || ahora >= m.inicio + m.duracion) return m.hasta;
  const t = Math.max(0, (ahora - m.inicio) / m.duracion);
  return [m.desde[0] + (m.hasta[0] - m.desde[0]) * t, m.desde[1] + (m.hasta[1] - m.desde[1]) * t];
}

export class Interpolador {
  private readonly movimientos = new Map<string, Movimiento>();
  private ultimaActualizacion: number | null = null;
  private duracionMs = DURACION_INICIAL_MS;
  /** Con `prefers-reduced-motion`, sin animación: cada tren salta a su posición nueva. */
  animar: boolean;

  constructor(animar = true) {
    this.animar = animar;
  }

  /** La duración que se usa para el próximo movimiento: la media del intervalo entre actualizaciones. */
  get duracion(): number {
    return this.duracionMs;
  }

  /**
   * Recibe **todos** los trenes que se ven ahora (tras aplicar una instantánea o unos cambios). Los que faltan se
   * borran; los que no han cambiado de sitio siguen con su movimiento; los que sí, empiezan uno nuevo desde donde están.
   */
  sincronizar(trenes: Iterable<{ id: string; lon: number; lat: number }>, ahora: number, medir = true): void {
    // Solo los datos nuevos del directo marcan el ritmo; un cambio de filtro, no.
    if (medir && this.ultimaActualizacion !== null) {
      const intervalo = ahora - this.ultimaActualizacion;
      // Un intervalo de más de 20 s es un corte (o una reconexión), no el ritmo normal: no cuenta para la media.
      if (intervalo > 0 && intervalo <= DURACION_MAX_MS) {
        const media = (this.duracionMs + intervalo) / 2;
        this.duracionMs = Math.min(DURACION_MAX_MS, Math.max(DURACION_MIN_MS, media));
      }
    }
    if (medir) this.ultimaActualizacion = ahora;

    const vistos = new Set<string>();
    for (const tren of trenes) {
      vistos.add(tren.id);
      const nueva: LonLat = [tren.lon, tren.lat];
      const anterior = this.movimientos.get(tren.id);
      if (anterior && iguales(anterior.hasta, nueva)) continue;
      const actual = anterior ? posicionEn(anterior, ahora) : null;
      if (!this.animar || actual === null || distanciaM(actual, nueva) > SALTO_MAXIMO_M) {
        this.movimientos.set(tren.id, { desde: nueva, hasta: nueva, inicio: ahora, duracion: 0 });
      } else {
        this.movimientos.set(tren.id, { desde: actual, hasta: nueva, inicio: ahora, duracion: this.duracionMs });
      }
    }
    for (const id of [...this.movimientos.keys()]) {
      if (!vistos.has(id)) this.movimientos.delete(id);
    }
  }

  /** Dónde pintar el tren ahora; `null` si no se conoce. */
  posicion(id: string, ahora: number): LonLat | null {
    const m = this.movimientos.get(id);
    return m ? posicionEn(m, ahora) : null;
  }

  /** ¿Queda algún tren a medio camino? Si no, no hace falta pedir más fotogramas. */
  enMovimiento(ahora: number): boolean {
    for (const m of this.movimientos.values()) {
      if (m.duracion > 0 && ahora < m.inicio + m.duracion && !iguales(m.desde, m.hasta)) return true;
    }
    return false;
  }

  /** Lleva cada tren a su destino al instante (al activar el movimiento reducido, por ejemplo). */
  terminar(): void {
    for (const [id, m] of this.movimientos) {
      this.movimientos.set(id, { desde: m.hasta, hasta: m.hasta, inicio: m.inicio, duracion: 0 });
    }
  }

  vaciar(): void {
    this.movimientos.clear();
    this.ultimaActualizacion = null;
    this.duracionMs = DURACION_INICIAL_MS;
  }
}
