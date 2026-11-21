import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import cambios from '../../../docs/api/directo-cambios.json';
import instantanea from '../../../docs/api/directo.json';
import latido from '../../../docs/api/directo-latido.json';
import { FuenteFalsa } from '../pruebas/FuenteFalsa';
import { ConexionDirecto, ESPERA_MAXIMA_MS, esperaReintento, type EstadoConexion, type OyentesDirecto } from './ConexionDirecto';

function preparar(azar = 0.5) {
  const oyentes = {
    instantanea: vi.fn(),
    cambios: vi.fn(),
    latido: vi.fn(),
    estado: vi.fn<(e: EstadoConexion) => void>(),
  } satisfies OyentesDirecto;
  const conexion = new ConexionDirecto('/api/directo?nucleo=51', oyentes, {
    crearFuente: (url) => new FuenteFalsa(url),
    setTimeout: (f, ms) => setTimeout(f, ms),
    clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>),
    ahora: () => Date.now(),
    azar: () => azar,
  });
  return { oyentes, conexion, ultimoEstado: () => oyentes.estado.mock.calls.at(-1)?.[0] };
}

beforeEach(() => {
  vi.useFakeTimers();
  FuenteFalsa.creadas = [];
});

afterEach(() => {
  vi.useRealTimers();
});

describe('esperaReintento', () => {
  it('crece 2, 4, 8, 16… s hasta 60 s', () => {
    expect([0, 1, 2, 3, 4, 5, 9].map((n) => esperaReintento(n, 0.5))).toEqual([
      2000, 4000, 8000, 16_000, 32_000, 60_000, 60_000,
    ]);
  });

  it('reparte los reintentos un ±20 % al azar', () => {
    expect(esperaReintento(0, 0)).toBe(1600);
    expect(esperaReintento(0, 1)).toBe(2400);
    expect(esperaReintento(20, 1)).toBe(ESPERA_MAXIMA_MS * 1.2);
  });
});

describe('ConexionDirecto', () => {
  it('abre el SSE del núcleo y pasa cada evento ya leído', () => {
    const { oyentes, conexion, ultimoEstado } = preparar();
    conexion.abrir();
    const fuente = FuenteFalsa.ultima();
    expect(fuente.url).toBe('/api/directo?nucleo=51');
    expect(ultimoEstado()).toEqual({ tipo: 'conectando' });

    fuente.abrir();
    fuente.enviar('instantanea', instantanea);
    fuente.enviar('cambios', cambios);
    fuente.enviar('latido', latido);
    expect(ultimoEstado()).toEqual({ tipo: 'abierta' });
    expect(oyentes.instantanea).toHaveBeenCalledWith(instantanea);
    expect(oyentes.cambios).toHaveBeenCalledWith(cambios);
    expect(oyentes.latido).toHaveBeenCalledWith(latido);
  });

  it('no se cae con un evento mal formado', () => {
    const { oyentes, conexion } = preparar();
    conexion.abrir();
    FuenteFalsa.ultima().enviar('cambios', '{no es JSON');
    expect(oyentes.cambios).not.toHaveBeenCalled();
  });

  it('tras un 503 (EventSource se queda cerrado), lo vuelve a abrir con espera creciente', () => {
    const { conexion, ultimoEstado } = preparar();
    conexion.abrir();

    FuenteFalsa.ultima().fallar(true);
    expect(FuenteFalsa.ultima().cerrada).toBe(true);
    expect(ultimoEstado()).toEqual({ tipo: 'reintentando', intento: 1, esperaMs: 2000 });
    vi.advanceTimersByTime(1999);
    expect(FuenteFalsa.creadas).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FuenteFalsa.creadas).toHaveLength(2);

    FuenteFalsa.ultima().fallar(true);
    expect(ultimoEstado()).toEqual({ tipo: 'reintentando', intento: 2, esperaMs: 4000 });
    vi.advanceTimersByTime(4000);
    expect(FuenteFalsa.creadas).toHaveLength(3);

    FuenteFalsa.ultima().fallar(true);
    expect(ultimoEstado()).toMatchObject({ esperaMs: 8000 });
  });

  it('vuelve a la espera corta en cuanto llega una instantánea', () => {
    const { conexion, ultimoEstado } = preparar();
    conexion.abrir();
    FuenteFalsa.ultima().fallar(true);
    vi.advanceTimersByTime(2000);
    FuenteFalsa.ultima().fallar(true);
    vi.advanceTimersByTime(4000);
    FuenteFalsa.ultima().abrir();
    FuenteFalsa.ultima().enviar('instantanea', instantanea);
    FuenteFalsa.ultima().fallar(true);
    expect(ultimoEstado()).toMatchObject({ intento: 1, esperaMs: 2000 });
  });

  it('si se corta un flujo que iba bien, deja que el navegador se reconecte solo', () => {
    const { conexion, ultimoEstado } = preparar();
    conexion.abrir();
    FuenteFalsa.ultima().abrir();
    FuenteFalsa.ultima().fallar(false);
    expect(ultimoEstado()).toEqual({ tipo: 'conectando' });
    vi.advanceTimersByTime(10_000);
    expect(FuenteFalsa.creadas).toHaveLength(1);
    expect(FuenteFalsa.ultima().cerrada).toBe(false);
  });

  it('si en 45 s no llega nada (ni el latido), da la conexión por colgada y abre otra', () => {
    const { conexion } = preparar();
    conexion.abrir();
    FuenteFalsa.ultima().abrir();
    vi.advanceTimersByTime(30_000);
    FuenteFalsa.ultima().enviar('latido', latido);
    vi.advanceTimersByTime(40_000);
    expect(FuenteFalsa.creadas).toHaveLength(1);
    vi.advanceTimersByTime(10_000); // 50 s desde el último latido
    expect(FuenteFalsa.creadas[0].cerrada).toBe(true);
    vi.advanceTimersByTime(2000);
    expect(FuenteFalsa.creadas).toHaveLength(2);
  });

  it('al cerrarla, cierra el SSE y no vuelve a intentarlo', () => {
    const { conexion } = preparar();
    conexion.abrir();
    FuenteFalsa.ultima().fallar(true);
    conexion.cerrar();
    vi.advanceTimersByTime(120_000);
    expect(FuenteFalsa.creadas).toHaveLength(1);
    expect(FuenteFalsa.creadas[0].cerrada).toBe(true);
  });

  it('ignora los eventos de una fuente que ya ha cerrado', () => {
    const { oyentes, conexion } = preparar();
    conexion.abrir();
    const vieja = FuenteFalsa.ultima();
    vieja.fallar(true);
    vi.advanceTimersByTime(2000);
    vieja.enviar('instantanea', instantanea);
    expect(oyentes.instantanea).not.toHaveBeenCalled();
  });
});
