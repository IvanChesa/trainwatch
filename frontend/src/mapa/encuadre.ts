import type { Nucleo, Punto } from '../api/tipos';

export interface Limites {
  latMin: number;
  lonMin: number;
  latMax: number;
  lonMax: number;
}

/**
 * Lo que el mapa tiene que enseñar: un núcleo o toda España. `clave` cambia solo cuando hay que volver a encuadrar
 * (al cambiar de núcleo), no cada vez que se refresca `/api/nucleos`.
 */
export interface Encuadre {
  clave: string;
  /** El rectángulo donde está el servicio (ADR 0023): lo primero que se usa. */
  limites: Limites | null;
  /** Si no hay `limites` (un núcleo sin horario), el centro y el zoom; si tampoco, no se mueve el mapa. */
  centro: Punto | null;
  zoom: number | null;
}

export function encuadreDeNucleo(nucleo: Nucleo): Encuadre {
  return { clave: String(nucleo.id), limites: nucleo.limites, centro: nucleo.centro, zoom: nucleo.zoom };
}

/** Toda España: el rectángulo que contiene los de todos los núcleos (la península, de Ferrol a Cádiz y a Girona). */
export function encuadreDeTodos(nucleos: readonly Nucleo[]): Encuadre {
  let limites: Limites | null = null;
  for (const { limites: l } of nucleos) {
    if (!l) continue;
    limites = limites
      ? {
          latMin: Math.min(limites.latMin, l.latMin),
          lonMin: Math.min(limites.lonMin, l.lonMin),
          latMax: Math.max(limites.latMax, l.latMax),
          lonMax: Math.max(limites.lonMax, l.lonMax),
        }
      : { ...l };
  }
  return { clave: 'todos', limites, centro: null, zoom: null };
}

/** Desde este ancho, el panel flota sobre la izquierda del mapa (App.css). */
export const ANCHO_ESCRITORIO = 768;

export type Margenes = { top: number; bottom: number; left: number; right: number };

/**
 * El hueco que hay que dejar alrededor de los límites: en pantallas anchas, el panel de control (340 px más su
 * margen) tapa la izquierda y los créditos el borde de abajo; en el móvil el panel va encima del mapa, no sobre él.
 */
export function margenesEncuadre(anchoMapa: number): Margenes {
  return anchoMapa >= ANCHO_ESCRITORIO
    ? { top: 30, bottom: 50, left: 370, right: 30 }
    : { top: 24, bottom: 24, left: 24, right: 24 };
}

/** Lo que hay que pedirle a MapLibre para encuadrar: `fitBounds`, `jumpTo` o nada. */
export type Movimiento =
  | {
      tipo: 'limites';
      limites: [[number, number], [number, number]];
      opciones: { padding: Margenes; animate: boolean; duration: number };
    }
  | { tipo: 'centro'; centro: [number, number]; zoom: number }
  | null;

/**
 * Cómo encuadrar. Sin animación si el usuario pide movimiento reducido o si es el primer encuadre (al abrir la web, el
 * mapa aparece ya encuadrado, sin un vuelo desde un sitio cualquiera).
 */
export function movimientoEncuadre(
  encuadre: Encuadre,
  anchoMapa: number,
  { reducirMovimiento, primero }: { reducirMovimiento: boolean; primero: boolean },
): Movimiento {
  if (encuadre.limites) {
    const { lonMin, latMin, lonMax, latMax } = encuadre.limites;
    return {
      tipo: 'limites',
      limites: [
        [lonMin, latMin],
        [lonMax, latMax],
      ],
      opciones: { padding: margenesEncuadre(anchoMapa), animate: !reducirMovimiento && !primero, duration: 800 },
    };
  }
  if (encuadre.centro) return { tipo: 'centro', centro: [encuadre.centro.lon, encuadre.centro.lat], zoom: encuadre.zoom ?? 9 };
  return null;
}
