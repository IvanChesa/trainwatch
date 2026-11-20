import { describe, expect, it } from 'vitest';
import { describirDirecto, formatearAntiguedad, type EstadoDirecto } from './directo';

const base: EstadoDirecto = {
  ultimoDato: '2026-11-20T17:30:00Z',
  antiguedadS: 20,
  trenes: 380,
  nucleos: [{ nucleo: 51, nombre: 'Rodalies de Catalunya', estado: 'EN_DIRECTO' }],
};

describe('estado del directo', () => {
  it('formatea la antigüedad en segundos, minutos u horas', () => {
    expect(formatearAntiguedad(7)).toBe('hace 7 s');
    expect(formatearAntiguedad(185)).toBe('hace 3 min');
    expect(formatearAntiguedad(7300)).toBe('hace 2 h');
  });

  it('avisa si los datos tienen más de 5 minutos', () => {
    expect(describirDirecto(base)).toBe('Último dato de Renfe hace 20 s: 380 trenes en 1 núcleos.');
    expect(describirDirecto({ ...base, antiguedadS: 400 })).toContain('Los datos no están al día.');
  });

  it('dice que aún no hay datos si no ha llegado ninguno', () => {
    expect(describirDirecto({ ...base, ultimoDato: null, antiguedadS: null })).toBe(
      'Aún no han llegado datos en directo de Renfe.',
    );
  });
});
