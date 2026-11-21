import { describe, expect, it } from 'vitest';
import { DURACION_INICIAL_MS, Interpolador, distanciaM } from './interpolacion';

const tren = (lon: number, lat: number, id = 'A') => ({ id, lon, lat });

describe('Interpolador', () => {
  it('pone un tren nuevo directamente en su sitio', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    expect(i.posicion('A', 0)).toEqual([2, 41]);
    expect(i.enMovimiento(0)).toBe(false);
  });

  it('lleva el tren en línea recta y a velocidad constante hasta la posición nueva', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2.01, 41.01)], 7000);
    // El intervalo medido (7 s) es la duración del movimiento.
    expect(i.duracion).toBe(7000);
    expect(i.posicion('A', 7000)).toEqual([2, 41]);
    const mitad = i.posicion('A', 10_500)!;
    expect(mitad[0]).toBeCloseTo(2.005, 10);
    expect(mitad[1]).toBeCloseTo(41.005, 10);
    expect(i.enMovimiento(10_500)).toBe(true);
    expect(i.posicion('A', 14_000)).toEqual([2.01, 41.01]);
    expect(i.posicion('A', 99_000)).toEqual([2.01, 41.01]);
    expect(i.enMovimiento(14_000)).toBe(false);
  });

  it('si llega otra posición a medio camino, sigue desde donde se le ve, sin saltos', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2.01, 41)], 7000);
    const antes = i.posicion('A', 10_500)!;
    i.sincronizar([tren(2.02, 41)], 10_500);
    expect(i.posicion('A', 10_500)).toEqual(antes);
    expect(i.posicion('A', 10_500 + i.duracion)).toEqual([2.02, 41]);
  });

  it('no reinicia el movimiento de un tren que no ha cambiado (los «cambios» no traen los demás)', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41), tren(1, 40, 'B')], 0);
    i.sincronizar([tren(2.01, 41), tren(1, 40, 'B')], 7000);
    i.sincronizar([tren(2.01, 41), tren(1.001, 40, 'B')], 9000);
    // A sigue el movimiento que empezó a los 7 s.
    expect(i.posicion('A', 7000 + DURACION_INICIAL_MS)).toEqual([2.01, 41]);
  });

  it('teletransporta los saltos de más de 5 km (no son movimiento)', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2.2, 41)], 7000);
    expect(distanciaM([2, 41], [2.2, 41])).toBeGreaterThan(5000);
    expect(i.posicion('A', 7000)).toEqual([2.2, 41]);
    expect(i.enMovimiento(7000)).toBe(false);
  });

  it('sin animación (movimiento reducido), el tren salta a su posición nueva', () => {
    const i = new Interpolador(false);
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2.01, 41)], 7000);
    expect(i.posicion('A', 7000)).toEqual([2.01, 41]);
    expect(i.enMovimiento(7000)).toBe(false);
  });

  it('al activar el movimiento reducido a medio camino, termina el movimiento', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2.01, 41)], 7000);
    i.terminar();
    expect(i.posicion('A', 8000)).toEqual([2.01, 41]);
  });

  it('borra los trenes que ya no están', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41), tren(1, 40, 'B')], 0);
    i.sincronizar([tren(2, 41)], 7000);
    expect(i.posicion('B', 7000)).toBeNull();
  });

  it('ajusta la duración al ritmo de las actualizaciones, sin contar los cortes', () => {
    const i = new Interpolador();
    i.sincronizar([tren(2, 41)], 0);
    i.sincronizar([tren(2, 41)], 5000);
    expect(i.duracion).toBe(6000); // media de 7 s y 5 s
    i.sincronizar([tren(2, 41)], 65_000); // un minuto sin datos: no cuenta
    expect(i.duracion).toBe(6000);
    i.sincronizar([tren(2, 41)], 65_100); // ni tampoco baja de 1 s
    expect(i.duracion).toBeGreaterThanOrEqual(1000);
  });

  it('mide distancias de unos kilómetros con un error pequeño', () => {
    // 0,01° de latitud ≈ 1.112 m.
    expect(distanciaM([2, 41], [2, 41.01])).toBeCloseTo(1112, -1);
  });
});
