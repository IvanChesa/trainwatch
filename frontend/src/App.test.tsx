import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import App from './App';

function respuestaJson(cuerpo: unknown, status = 200): Response {
  return new Response(JSON.stringify(cuerpo), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('App', () => {
  it('dice que está en construcción, avisa de que no es oficial y cita los datos', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    render(<App />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('TrainWatch · en construcción');
    expect(screen.getByRole('note')).toHaveTextContent('no oficial');
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Datos: Renfe (CC BY 4.0)');
    await screen.findByText('Backend no disponible');
    await screen.findByText('Estado de los datos no disponible.');
  });

  it('enseña el estado de los datos en directo', async () => {
    const fetchSimulado = vi.fn((url: string) =>
      Promise.resolve(
        url.endsWith('/api/directo/estado')
          ? respuestaJson({
              ultimoDato: '2026-11-20T17:30:00Z',
              antiguedadS: 12,
              trenes: 404,
              nucleos: [
                { nucleo: 51, nombre: 'Rodalies de Catalunya', estado: 'EN_DIRECTO' },
                { nucleo: 10, nombre: 'Madrid', estado: 'EN_DIRECTO' },
                { nucleo: 60, nombre: 'Bilbao', estado: 'SIN_DATOS_EN_DIRECTO' },
              ],
            })
          : respuestaJson({ aplicacion: 'TrainWatch', version: '0.0.1', hora: '2026-11-20T18:30:12+01:00' }),
      ),
    );
    vi.stubGlobal('fetch', fetchSimulado);
    render(<App />);

    expect(await screen.findByText('Último dato de Renfe hace 12 s: 404 trenes en 2 núcleos.')).toBeInTheDocument();
    expect(fetchSimulado).toHaveBeenCalledWith('/api/directo/estado', expect.anything());
  });

  it('enseña la versión y la hora del backend', async () => {
    const fetchSimulado = vi.fn().mockResolvedValue(
      respuestaJson({ aplicacion: 'TrainWatch', version: '0.0.1-SNAPSHOT', hora: '2026-11-20T13:30:05+01:00' }),
    );
    vi.stubGlobal('fetch', fetchSimulado);
    render(<App />);

    expect(screen.getByRole('status')).toHaveTextContent('Consultando el backend…');
    const estado = await screen.findByText(/Backend 0\.0\.1-SNAPSHOT/);
    expect(estado).toHaveTextContent('20/11/26, 13:30:05');
    expect(fetchSimulado).toHaveBeenCalledWith('/api/estado', expect.anything());
  });

  it('cancela la petición al desmontarse', () => {
    const fetchSimulado = vi.fn().mockReturnValue(new Promise<Response>(() => {}));
    vi.stubGlobal('fetch', fetchSimulado);
    const { unmount } = render(<App />);

    const { signal } = fetchSimulado.mock.calls[0][1] as RequestInit;
    expect(signal?.aborted).toBe(false);
    unmount();
    expect(signal?.aborted).toBe(true);
  });

  it('dice «backend no disponible» si responde con un error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respuestaJson({ error: 'caído' }, 503)));
    render(<App />);

    expect(await screen.findByRole('status')).toHaveTextContent('Backend no disponible');
  });
});
