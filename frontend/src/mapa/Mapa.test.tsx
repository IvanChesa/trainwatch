import { act, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Encuadre } from './encuadre';
import type { PropsMapa } from './Mapa';

/**
 * El efecto de encuadre de `Mapa.tsx` con un MapLibre de mentira (jsdom no tiene WebGL): qué se le pide al mapa al
 * abrir, al cambiar de núcleo y si el contenedor aún no tiene tamaño.
 */
const mapas: MapaFalso[] = [];
let tamano = { ancho: 1440, alto: 900 };

class MapaFalso {
  readonly oyentes = new Map<string, ((...a: unknown[]) => void)[]>();
  readonly fitBounds = vi.fn();
  readonly jumpTo = vi.fn();
  readonly easeTo = vi.fn();
  readonly touchZoomRotate = { disableRotation: vi.fn() };
  readonly contenedor: { clientWidth: number; clientHeight: number };

  constructor() {
    this.contenedor = {
      get clientWidth() {
        return tamano.ancho;
      },
      get clientHeight() {
        return tamano.alto;
      },
    };
    mapas.push(this);
  }

  setStyle() {}
  setMissingStyleImageResolver() {}
  addControl() {}
  isStyleLoaded() {
    return false;
  }
  getContainer() {
    return this.contenedor;
  }
  getCanvas() {
    return document.createElement('canvas');
  }
  remove() {}
  on(evento: string, oyente: (...a: unknown[]) => void) {
    this.oyentes.set(evento, [...(this.oyentes.get(evento) ?? []), oyente]);
  }
  once(evento: string, oyente: (...a: unknown[]) => void) {
    this.on(evento, oyente);
  }
  off(evento: string, oyente: (...a: unknown[]) => void) {
    this.oyentes.set(evento, (this.oyentes.get(evento) ?? []).filter((o) => o !== oyente));
  }
  disparar(evento: string) {
    const oyentes = this.oyentes.get(evento) ?? [];
    this.oyentes.set(evento, []);
    oyentes.forEach((o) => o());
  }
}

vi.mock('maplibre-gl', () => ({
  Map: MapaFalso,
  NavigationControl: class {},
  setWorkerUrl: () => {},
}));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

const { default: Mapa } = await import('./Mapa');

const RODALIES: Encuadre = {
  clave: '51',
  limites: { lonMin: 0.54, latMin: 40.81, lonMax: 3.15, latMax: 42.41 },
  centro: { lat: 41.61, lon: 1.84 },
  zoom: 7.4,
};
const MADRID: Encuadre = {
  clave: '10',
  limites: { lonMin: -4.07, latMin: 40.04, lonMax: -3.18, latMax: 40.69 },
  centro: { lat: 40.36, lon: -3.62 },
  zoom: 8.7,
};

function props(encuadre: Encuadre | null, reducirMovimiento = false): PropsMapa {
  return {
    encuadre,
    trenes: [],
    recibido: null,
    linea: null,
    seleccionado: null,
    reducirMovimiento,
    descripcion: 'Mapa',
    alPulsarTren: () => {},
    alPulsarEstacion: () => {},
  };
}

beforeEach(() => {
  mapas.length = 0;
  tamano = { ancho: 1440, alto: 900 };
});

describe('el encuadre del mapa', () => {
  it('al abrir encuadra sin animación, con el hueco del panel y el margen absoluto; al cambiar, con un vuelo', () => {
    const { rerender } = render(<Mapa {...props(RODALIES)} />);
    const m = mapas.at(-1)!;
    expect(m.fitBounds).toHaveBeenCalledTimes(1);
    const [limites, opciones] = m.fitBounds.mock.calls[0];
    expect(limites).toEqual([
      [0.54, 40.81],
      [3.15, 42.41],
    ]);
    expect(opciones).toMatchObject({ animate: false, absolutePadding: true, maxZoom: 12, padding: { left: 370 } });

    // Refrescar /api/nucleos (un objeto nuevo con la misma clave) no vuelve a encuadrar.
    rerender(<Mapa {...props({ ...RODALIES })} />);
    expect(m.fitBounds).toHaveBeenCalledTimes(1);

    rerender(<Mapa {...props(MADRID)} />);
    expect(m.fitBounds).toHaveBeenCalledTimes(2);
    expect(m.fitBounds.mock.calls[1][1]).toMatchObject({ animate: true, absolutePadding: true });
  });

  it('con movimiento reducido, el cambio de núcleo tampoco se anima', () => {
    const { rerender } = render(<Mapa {...props(RODALIES, true)} />);
    rerender(<Mapa {...props(MADRID, true)} />);
    expect(mapas.at(-1)!.fitBounds.mock.calls[1][1]).toMatchObject({ animate: false });
  });

  it('si el contenedor aún no tiene tamaño, espera a su primer «resize» para encuadrar', () => {
    tamano = { ancho: 0, alto: 0 };
    render(<Mapa {...props(RODALIES)} />);
    const m = mapas.at(-1)!;
    expect(m.fitBounds).not.toHaveBeenCalled();
    tamano = { ancho: 412, alto: 600 };
    act(() => m.disparar('resize'));
    expect(m.fitBounds).toHaveBeenCalledTimes(1);
    expect(m.fitBounds.mock.calls[0][1]).toMatchObject({ animate: false, padding: { left: 24 } });
  });

  it('sin núcleos todavía no encuadra; en cuanto llegan, sí', () => {
    const { rerender } = render(<Mapa {...props(null)} />);
    const m = mapas.at(-1)!;
    expect(m.fitBounds).not.toHaveBeenCalled();
    rerender(<Mapa {...props(RODALIES)} />);
    expect(m.fitBounds).toHaveBeenCalledTimes(1);
    expect(m.fitBounds.mock.calls[0][1]).toMatchObject({ animate: false });
  });
});
