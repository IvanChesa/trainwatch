import { describe, expect, it } from 'vitest';
import type { Directo } from '../directo/useDirecto';
import { anuncio, textoEstadoDatos } from './textos';

const T0 = 1_000_000;

function directo(cambios: Partial<Directo> = {}): Directo {
  return {
    trenes: new Map(),
    recibido: T0,
    antiguedadS: 10,
    antiguedadEn: T0,
    conexion: { tipo: 'abierta' },
    reintentoHasta: null,
    cargado: true,
    ...cambios,
  };
}

describe('estado de los datos', () => {
  it('si no llega nada, la antigüedad crece con el reloj y a los 2 min avisa', () => {
    const d = directo();
    expect(textoEstadoDatos(d, T0)).toEqual({ texto: 'En directo · dato de Renfe hace 10 s', aviso: false });
    expect(textoEstadoDatos(d, T0 + 100_000)).toEqual({ texto: 'En directo · dato de Renfe hace 1 min', aviso: false });
    // 10 s + 3 min sin ningún evento: el directo está parado.
    expect(textoEstadoDatos(d, T0 + 180_000)).toEqual({
      texto: 'Datos de hace 3 min: el directo está parado',
      aviso: true,
    });
    expect(anuncio(d, T0 + 180_000)).toBe('Los datos en directo están parados.');
  });

  it('cuenta hacia atrás hasta el próximo intento', () => {
    const d = directo({
      conexion: { tipo: 'reintentando', intento: 3, esperaMs: 8000 },
      reintentoHasta: T0 + 8000,
    });
    expect(textoEstadoDatos(d, T0).texto).toBe('Sin conexión con el directo: se reintenta en 8 s. Datos de hace 10 s');
    expect(textoEstadoDatos(d, T0 + 5500).texto).toMatch(/^Sin conexión con el directo: se reintenta en 3 s/);
    expect(anuncio(d, T0)).toBe('Sin conexión con el directo.');
  });

  it('anuncia «Reconectando» y no «En directo» mientras se vuelve a conectar', () => {
    const d = directo({ conexion: { tipo: 'conectando' } });
    expect(anuncio(d, T0)).toBe('Reconectando con el directo.');
    expect(textoEstadoDatos(d, T0).texto).toBe('Reconectando… · dato de Renfe hace 10 s');
  });

  it('antes de la primera instantánea dice que está conectando', () => {
    const d = directo({ cargado: false, antiguedadS: null, antiguedadEn: null, conexion: { tipo: 'conectando' } });
    expect(textoEstadoDatos(d, T0)).toEqual({ texto: 'Conectando con el directo…', aviso: false });
    expect(anuncio(d, T0)).toBe('Conectando con el directo.');
  });
});
