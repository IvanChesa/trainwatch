import { describe, expect, it } from 'vitest';
import trenes from '../../../docs/api/trenesDeNucleo.json';
import type { TrenMapa } from '../api/tipos';
import {
  FRANJAS,
  categoriaRetraso,
  colorRetraso,
  compararLineas,
  etiquetaRetraso,
  seVeEnElMapa,
  textoRetraso,
  textoSalida,
} from './retraso';

const ejemplo = trenes as TrenMapa[];

describe('color por retraso', () => {
  it('reparte los minutos en las cuatro franjas del encargo', () => {
    expect(categoriaRetraso(0)).toBe('a-tiempo');
    expect(categoriaRetraso(-2)).toBe('a-tiempo');
    expect(categoriaRetraso(1)).toBe('leve');
    expect(categoriaRetraso(5)).toBe('leve');
    expect(categoriaRetraso(6)).toBe('medio');
    expect(categoriaRetraso(15)).toBe('medio');
    expect(categoriaRetraso(16)).toBe('grave');
    expect(categoriaRetraso(90)).toBe('grave');
  });

  it('separa los trenes sin dato de retraso', () => {
    expect(categoriaRetraso(null)).toBe('sin-dato');
    expect(categoriaRetraso(undefined)).toBe('sin-dato');
  });

  it('da un color distinto a cada franja, y cada una tiene su texto', () => {
    const colores = new Set(FRANJAS.map((f) => f.color));
    expect(colores.size).toBe(FRANJAS.length);
    expect(FRANJAS.map((f) => f.texto)).toEqual(['A tiempo', '1-5 min', '6-15 min', 'Más de 15 min', 'Sin dato de retraso']);
    expect(colorRetraso(3)).toBe('#facc15');
  });

  it('tiene contraste de al menos 3:1 con el fondo del mapa en todas las franjas (WCAG 1.4.11)', () => {
    const luminancia = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => {
        const c = parseInt(hex.slice(i, i + 2), 16) / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const fondo = luminancia('#101114');
    for (const f of FRANJAS) {
      expect((luminancia(f.color) + 0.05) / (fondo + 0.05)).toBeGreaterThanOrEqual(3);
    }
  });
});

describe('textos del retraso', () => {
  it('lo dice con palabras', () => {
    expect(textoRetraso(0)).toBe('a tiempo');
    expect(textoRetraso(7)).toBe('7 min de retraso');
    expect(textoRetraso(-1)).toBe('1 min de adelanto');
    expect(textoRetraso(null)).toBe('sin dato de retraso');
  });

  it('pone «+N» junto al símbolo solo si hay retraso', () => {
    expect(etiquetaRetraso(7)).toBe('+7');
    expect(etiquetaRetraso(0)).toBe('');
    expect(etiquetaRetraso(null)).toBe('');
  });

  it('dice a qué hora sale un tren en origen, en hora de Madrid', () => {
    const enOrigen = ejemplo.find((t) => t.estado === 'EN_ORIGEN')!;
    expect(textoSalida(enOrigen)).toBe('Sale a las 15:15');
    expect(
      textoSalida({ salida: { programada: '2026-11-20T10:25:00+01:00', estimada: '2026-11-20T10:27:00+01:00' } }),
    ).toBe('Sale a las 10:27 (prevista a las 10:25)');
    expect(textoSalida({ salida: null })).toBeNull();
  });
});

describe('qué se pinta', () => {
  it('pinta los trenes en marcha y en origen, no los cancelados', () => {
    expect(ejemplo.map(seVeEnElMapa)).toEqual([true, true, false]);
  });

  it('ordena las líneas como una persona: R2 antes que R11', () => {
    expect(['R11', 'R2', 'RL4', 'R1', 'R2N'].sort(compararLineas)).toEqual(['R1', 'R2', 'R2N', 'R11', 'RL4']);
  });
});
