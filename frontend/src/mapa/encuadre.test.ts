import { describe, expect, it } from 'vitest';
import nucleos from '../../../docs/api/nucleos.json';
import type { Nucleo } from '../api/tipos';
import { encuadreDeNucleo, encuadreDeTodos, movimientoEncuadre } from './encuadre';

const NUCLEOS = nucleos as Nucleo[];
const rodalies = NUCLEOS.find((n) => n.id === 51)!;

/** El zoom con el que MapLibre (teselas de 512 px) mete `grados` de longitud en `px` píxeles. */
function zoomPara(px: number, grados: number): number {
  return Math.log2((px * 360) / (512 * grados));
}

describe('el encuadre de un núcleo', () => {
  it('usa los límites del servicio y deja el hueco del panel en pantallas anchas', () => {
    const m = movimientoEncuadre(encuadreDeNucleo(rodalies), 1440, { reducirMovimiento: false, primero: false });
    expect(m?.tipo).toBe('limites');
    if (m?.tipo !== 'limites') return;
    expect(m.limites).toEqual([
      [rodalies.limites!.lonMin, rodalies.limites!.latMin],
      [rodalies.limites!.lonMax, rodalies.limites!.latMax],
    ]);
    expect(m.opciones.padding.left).toBeGreaterThanOrEqual(352); // el panel: 12 px + 340 px
    expect(m.opciones.animate).toBe(true);
    // El margen sustituye al que haya dejado la ficha de un tren, y nunca más cerca que una ciudad.
    expect(m.opciones.absolutePadding).toBe(true);
    expect(m.opciones.maxZoom).toBe(12);
  });

  it('en el móvil no deja el hueco del panel (va encima del mapa)', () => {
    const m = movimientoEncuadre(encuadreDeNucleo(rodalies), 412, { reducirMovimiento: false, primero: false });
    expect(m?.tipo === 'limites' && m.opciones.padding).toEqual({ top: 24, bottom: 24, left: 24, right: 24 });
  });

  it('el primer encuadre y el de movimiento reducido van sin animación', () => {
    const e = encuadreDeNucleo(rodalies);
    const primero = movimientoEncuadre(e, 1440, { reducirMovimiento: false, primero: true });
    const reducido = movimientoEncuadre(e, 1440, { reducirMovimiento: true, primero: false });
    expect(primero?.tipo === 'limites' && primero.opciones.animate).toBe(false);
    expect(reducido?.tipo === 'limites' && reducido.opciones.animate).toBe(false);
  });

  it('sin límites, centro y zoom; sin nada, no se mueve', () => {
    const sinLimites = { ...encuadreDeNucleo(rodalies), limites: null };
    expect(movimientoEncuadre(sinLimites, 800, { reducirMovimiento: false, primero: false })).toEqual({
      tipo: 'centro',
      centro: [rodalies.centro!.lon, rodalies.centro!.lat],
      zoom: rodalies.zoom,
      padding: { top: 30, bottom: 50, left: 370, right: 30 },
    });
    expect(
      movimientoEncuadre({ clave: '99', limites: null, centro: null, zoom: null }, 800, {
        reducirMovimiento: false,
        primero: false,
      }),
    ).toBeNull();
  });

  // El fallo de la fase 5 (21-11): con los límites de todas las estaciones (de Villanueva de Gállego, en Aragón, a
  // Cerbère), Rodalies abría a zoom ~6,8 en una ventana de 1024 px y ~5,9 en el móvil: media península a la vista.
  it('Rodalies cabe en Cataluña: ni Aragón ni Francia, y un zoom de verdad', () => {
    const l = rodalies.limites!;
    expect(l.lonMin).toBeGreaterThan(0.4); // sin la R15 por Aragón
    expect(l.lonMin).toBeLessThan(0.634); // con Lleida
    expect(l.latMin).toBeGreaterThan(40.6); // sin Ulldecona
    expect(l.lonMax).toBeLessThan(3.163); // sin Cerbère
    const ancho = l.lonMax - l.lonMin;
    expect(zoomPara(1024 - 400, ancho)).toBeGreaterThan(7.2);
    expect(zoomPara(412 - 48, ancho)).toBeGreaterThan(6.3);
  });
});

describe('el encuadre de toda España', () => {
  it('contiene los límites de todos los núcleos', () => {
    const e = encuadreDeTodos(NUCLEOS);
    expect(e.clave).toBe(`todos:${NUCLEOS.length}`);
    for (const n of NUCLEOS) {
      expect(e.limites!.lonMin).toBeLessThanOrEqual(n.limites!.lonMin);
      expect(e.limites!.latMin).toBeLessThanOrEqual(n.limites!.latMin);
      expect(e.limites!.lonMax).toBeGreaterThanOrEqual(n.limites!.lonMax);
      expect(e.limites!.latMax).toBeGreaterThanOrEqual(n.limites!.latMax);
    }
    // La península: de Ferrol a Portbou y de Cádiz a la costa cantábrica.
    expect(e.limites!.lonMin).toBeLessThan(-8);
    expect(e.limites!.lonMax).toBeGreaterThan(3);
    expect(e.limites!.latMin).toBeLessThan(36.5);
    expect(e.limites!.latMax).toBeGreaterThan(43.5);
  });

  it('sin núcleos con límites, no hay rectángulo', () => {
    expect(encuadreDeTodos([{ ...rodalies, limites: null }]).limites).toBeNull();
  });
});
