import { describe, expect, it, vi } from 'vitest';
import { formatearHora, obtenerEstado } from './estado';

function respuestaJson(cuerpo: unknown, status = 200): Response {
  return new Response(JSON.stringify(cuerpo), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('obtenerEstado', () => {
  it('pide /api/estado y devuelve la respuesta', async () => {
    const cuerpo = { aplicacion: 'TrainWatch', version: '0.0.1-SNAPSHOT', hora: '2026-11-20T13:30:05+01:00' };
    const fetchSimulado = vi.fn().mockResolvedValue(respuestaJson(cuerpo));
    vi.stubGlobal('fetch', fetchSimulado);

    await expect(obtenerEstado()).resolves.toEqual(cuerpo);
    expect(fetchSimulado).toHaveBeenCalledWith('/api/estado', expect.objectContaining({ signal: undefined }));
  });

  it('falla si el backend no responde 2xx', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respuestaJson({}, 500)));
    await expect(obtenerEstado()).rejects.toThrow('500');
  });

  it('falla si la respuesta no tiene la forma esperada', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respuestaJson({ version: 1 })));
    await expect(obtenerEstado()).rejects.toThrow('formato inesperado');
  });
});

describe('formatearHora', () => {
  it('pasa la hora a la de Madrid', () => {
    expect(formatearHora('2026-11-20T12:30:05Z')).toBe('20/11/26, 13:30:05');
  });

  it('deja el texto tal cual si no es una fecha', () => {
    expect(formatearHora('ayer')).toBe('ayer');
  });
});
