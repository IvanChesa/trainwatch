import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ejemploEstacion from '../../../docs/api/puntualidadEstacion.json';
import ejemploLinea from '../../../docs/api/puntualidadLinea.json';
import ejemploNucleo from '../../../docs/api/puntualidadNucleo.json';
import ejemploTodos from '../../../docs/api/puntualidadTodos.json';
import { INSUFICIENTE, LINEA, NUCLEO, SIN_PUBLICAR, TODOS, cifras } from '../pruebas/estadisticas';
import { escala } from './escala';
import {
  fecha,
  frase,
  hallazgoRanking,
  minutosTope,
  numero,
  ordenHora,
  porcentaje,
  textoCobertura,
  textoPeriodo,
} from './formato';
import PaginaEstadisticas from './PaginaEstadisticas';
import { PuntualidadEstacion } from './PuntualidadEstacion';
import { leerRuta, rutaApiDe, urlLinea, urlNucleo, urlTodos } from './ruta';

function api(respuestas: Record<string, unknown>) {
  const fetch = vi.fn((url: string) => {
    const ruta = url.split('?')[0];
    const cuerpo = respuestas[ruta];
    return Promise.resolve(
      cuerpo === undefined
        ? new Response(JSON.stringify({ title: 'No encontrado', status: 404, detail: 'No existe' }), { status: 404 })
        : new Response(JSON.stringify(cuerpo), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('rutas', () => {
  it('lee las tres páginas, con el periodo, y descarta lo demás', () => {
    expect(leerRuta('/estadisticas', '')).toEqual({ tipo: 'todos', desde: null, hasta: null });
    expect(leerRuta('/estadisticas/', '?desde=2026-11-21&hasta=2026-12-18')).toEqual({
      tipo: 'todos',
      desde: '2026-11-21',
      hasta: '2026-12-18',
    });
    expect(leerRuta('/estadisticas/51', '')).toMatchObject({ tipo: 'nucleo', nucleo: 51 });
    expect(leerRuta('/estadisticas/51/R2%20Sud', '')).toMatchObject({ tipo: 'linea', nucleo: 51, linea: 'R2 Sud' });
    expect(leerRuta('/estadisticas/51', '?desde=ayer')).toMatchObject({ desde: null });
    expect(leerRuta('/', '')).toBeNull();
    expect(leerRuta('/estadisticasX', '')).toBeNull();
    expect(leerRuta('/estadisticas/madrid', '')).toBeNull();
    expect(leerRuta('/estadisticas/51/R1/mas', '')).toBeNull();
    // Un «%» mal formado no rompe la página: se trata como una ruta que no existe.
    expect(leerRuta('/estadisticas/51/R%E0', '')).toBeNull();
  });

  it('construye los enlaces y la ruta de la API', () => {
    expect(urlTodos()).toBe('/estadisticas');
    expect(urlNucleo(51, '2026-11-21', '2026-12-18')).toBe('/estadisticas/51?desde=2026-11-21&hasta=2026-12-18');
    expect(urlLinea(51, 'R2 Sud')).toBe('/estadisticas/51/R2%20Sud');
    expect(rutaApiDe({ tipo: 'todos', desde: null, hasta: null })).toBe('/api/estadisticas/nucleos/todos');
    expect(rutaApiDe({ tipo: 'linea', nucleo: 51, linea: 'R2 Sud', desde: '2026-11-21', hasta: null })).toBe(
      '/api/estadisticas/lineas/51/R2%20Sud?desde=2026-11-21',
    );
  });
});

describe('textos', () => {
  it('números en español y «—» si no hay dato', () => {
    expect(numero(2.4)).toBe('2,4');
    expect(porcentaje(78.4)).toBe('78,4\u00a0%');
    expect(porcentaje(null)).toBe('—');
    expect(fecha('2026-11-21')).toBe('21 de noviembre de 2026');
    expect(textoPeriodo({ desde: '2026-11-21', hasta: '2026-12-18', diasConDatos: 1 })).toBe(
      'Del 21 de noviembre de 2026 al 18 de diciembre de 2026 (1 día con datos)',
    );
    expect(textoCobertura({ ...LINEA.coberturaNucleo! })).toBe(
      '89,2\u00a0% de los trenes programados y el 81,6\u00a0% de sus paradas',
    );
    expect(frase(INSUFICIENTE)).toBeNull();
    expect(minutosTope(15)).toBe('15\u00a0min');
    expect(minutosTope(60)).toBe('60\u00a0min o más');
  });

  it('las horas, en el orden del día de servicio: de las 4 a las 3', () => {
    expect([0, 23, 5, 4, 3].sort((a, b) => ordenHora(a) - ordenHora(b))).toEqual([4, 5, 23, 0, 3]);
  });

  it('el titular del ranking no corona diferencias que la muestra no permite separar', () => {
    const fila = (nombre: string, p: number, cob = 90) => ({
      nombre,
      cifras: cifras(2, p),
      cobertura: { ...LINEA.coberturaNucleo!, porcentaje: cob },
    });
    expect(hallazgoRanking([fila('R3', 90), fila('R1', 80), fila('R2', 70)])).toBe(
      'La más puntual, R3 (el 90\u00a0% de las paradas con menos de 3 min); la que menos, R2 (70\u00a0%).',
    );
    // A menos de 5 puntos: igualadas.
    expect(hallazgoRanking([fila('R3', 82), fila('R1', 79), fila('R2', 70)])).toMatch(/^En cabeza, igualadas: R3 .*, R1 /);
    expect(hallazgoRanking([fila('R3', 82), fila('R1', 80)])).toMatch(/^Todas muy igualadas/);
    // Con poca cobertura, se dice.
    expect(hallazgoRanking([fila('Asturias', 90, 11.1), fila('Madrid', 70)])).toContain('solo se ve el 11,1\u00a0% de sus trenes');
    expect(hallazgoRanking([{ nombre: 'R1', cifras: INSUFICIENTE }])).toBeNull();
  });

  it('la escala del eje da pasos redondos por encima del máximo', () => {
    expect(escala(4.1)).toEqual({ tope: 6, paso: 2 });
    const { tope, paso } = escala(13);
    expect(tope).toBeGreaterThanOrEqual(13);
    expect([1, 2, 5, 10].includes(paso / 10 ** Math.floor(Math.log10(paso)))).toBe(true);
    expect(escala(0)).toEqual({ tope: 1, paso: 1 });
  });
});

describe('página de una línea', () => {
  it('enseña la respuesta, el contexto obligatorio y cada gráfico con su tabla', async () => {
    api({ '/api/estadisticas/lineas/51/R2N': LINEA });
    render(<PaginaEstadisticas ruta={{ tipo: 'linea', nucleo: 51, linea: 'R2N', desde: null, hasta: null }} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Puntualidad de la R2N (Rodalies de Catalunya)' }));
    expect(screen.getByText(/2,4 min de retraso medio por parada; el 78,4 %/)).toBeInTheDocument();
    // Siempre a la vista: el periodo, «solo trenes vistos en directo», la cobertura, la fuente y el aviso.
    expect(screen.getByText(/Del 21 de noviembre de 2026 al 18 de diciembre de 2026/)).toBeInTheDocument();
    expect(screen.getByText(/Solo trenes vistos en directo/)).toBeInTheDocument();
    expect(screen.getByText(/se han quitado 412 paradas que no se vieron/)).toBeInTheDocument();
    // Cobertura de la línea por debajo del 60 %: se resalta y se explica. Y la del núcleo, también a la vista.
    expect(screen.getByText(/45,5 % de los trenes programados/)).toBeInTheDocument();
    expect(screen.getByText('Cobertura de Rodalies de Catalunya')).toBeInTheDocument();
    expect(screen.getByText(/89,2 % de los trenes programados/)).toBeInTheDocument();
    expect(screen.getByText(/la cifra es solo de los trenes que se ven/)).toBeInTheDocument();
    expect(screen.getByText(/Datos: Renfe \(CC BY 4\.0\)/)).toBeInTheDocument();
    expect(screen.getByText('no oficial')).toBeInTheDocument();

    // Por hora: ordenadas, con la de las 23 como «datos insuficientes».
    const porHora = screen.getByRole('table', { name: 'Puntualidad de la R2N por hora' });
    const filas = within(porHora).getAllByRole('row').slice(1);
    expect(filas.map((f) => within(f).getByRole('rowheader').textContent)).toEqual(['07:00', '08:00', '23:00']);
    expect(within(filas[1]).getByText('4,1 min')).toBeInTheDocument();
    expect(within(filas[2]).getByText(/Datos insuficientes: 12 viajes \(hacen falta 30\)/)).toBeInTheDocument();
    // Su gráfico, al lado, con el valor escrito en cada columna y «—» en la que no llega.
    const grafico = porHora.closest('section')!.querySelector('svg')!;
    expect(grafico.getAttribute('aria-hidden')).toBe('true');
    expect([...grafico.querySelectorAll('.grafico__valor')].map((t) => t.textContent)).toEqual(['2,2', '4,1', '—']);

    expect(screen.getByRole('table', { name: 'Puntualidad de la R2N por día de la semana' })).toBeInTheDocument();
    expect(screen.getByText('domingo (4 días)')).toBeInTheDocument();
    expect(screen.getByText('16 nov (2 días)')).toBeInTheDocument();
    // El reparto: los cuatro tramos con su texto (no solo su color).
    const reparto = screen.getByRole('table', { name: 'Paradas por retraso al llegar' });
    expect(within(reparto).getByText('Menos de 3 min')).toBeInTheDocument();
    expect(within(reparto).getByText('78,4 %')).toBeInTheDocument();
    // La llegada a destino dice cuántos viajes se vieron llegar.
    expect(screen.getByText(/1520 de 1843 viajes se vieron llegar/)).toBeInTheDocument();
    // Tramos.
    const tramos = screen.getByRole('table', { name: 'Tramos de la R2N con más retraso' });
    // La flecha no se lee: el lector de pantalla oye «Granollers Centre a Les Franqueses».
    expect(within(tramos).getByRole('rowheader', { name: /^Granollers Centre\s+a\s+Les Franqueses$/ })).toBeInTheDocument();
    expect(within(tramos).getByText('+1,3 min')).toBeInTheDocument();
    // Migas: vuelta al núcleo y a toda España.
    expect(screen.getByRole('link', { name: 'Rodalies de Catalunya' })).toHaveAttribute('href', '/estadisticas/51');
    expect(document.title).toBe('Puntualidad de la R2N (Rodalies de Catalunya) · TrainWatch');
  });

  it('el periodo de la URL llega a la petición, y el formulario no deja un periodo al revés', async () => {
    const fetch = api({ '/api/estadisticas/lineas/51/R2N': LINEA });
    render(
      <PaginaEstadisticas ruta={{ tipo: 'linea', nucleo: 51, linea: 'R2N', desde: '2026-11-21', hasta: '2026-12-18' }} />,
    );
    await screen.findByRole('table', { name: 'Puntualidad de la R2N por hora' });
    expect(fetch).toHaveBeenCalledWith('/api/estadisticas/lineas/51/R2N?desde=2026-11-21&hasta=2026-12-18', expect.anything());
    const desde = screen.getByLabelText('Desde');
    fireEvent.change(desde, { target: { value: '2026-12-30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ver el periodo' }));
    expect(screen.getByRole('alert')).toHaveTextContent('con «desde» antes de «hasta»');
    expect(desde).toHaveAttribute('aria-invalid', 'true');
    expect(desde).toHaveAccessibleDescription(/con «desde» antes de «hasta»/);
  });

  it('antes de la fecha de publicación no enseña ninguna cifra', async () => {
    api({
      '/api/estadisticas/lineas/51/R2N': {
        ...LINEA,
        publicacion: SIN_PUBLICAR,
        muestra: null,
        resumen: null,
        llegadaDestino: null,
        porHora: [],
        porDiaSemana: [],
        porSemana: [],
        tramos: [],
      },
    });
    render(<PaginaEstadisticas ruta={{ tipo: 'linea', nucleo: 51, linea: 'R2N', desde: null, hasta: null }} />);
    expect(await screen.findByRole('heading', { name: 'Aún no hay cifras' })).toBeInTheDocument();
    expect(
      screen.getByText('Recogiendo datos desde el 21 de noviembre de 2026; cifras a partir del 19 de diciembre de 2026.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('una línea que no existe da un mensaje, no una página rota', async () => {
    api({});
    render(<PaginaEstadisticas ruta={{ tipo: 'linea', nucleo: 51, linea: 'R99', desde: null, hasta: null }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('puede que el núcleo o la línea no existan');
  });
});

describe('ranking del núcleo y de toda España', () => {
  it('el núcleo: líneas en orden, con su enlace, y las insuficientes al final sin puesto', async () => {
    api({ '/api/estadisticas/nucleos/51': NUCLEO });
    render(<PaginaEstadisticas ruta={{ tipo: 'nucleo', nucleo: 51, desde: null, hasta: null }} />);
    const tabla = await screen.findByRole('table', { name: 'Ranking de puntualidad de las líneas de Rodalies de Catalunya' });
    const filas = within(tabla).getAllByRole('row').slice(1);
    expect(filas.map((f) => within(f).getByRole('rowheader').textContent)).toEqual(['R3', 'R2N', 'RL4']);
    expect(within(filas[0]).getByRole('link', { name: 'R3' })).toHaveAttribute(
      'href',
      '/estadisticas/51/R3?desde=2026-11-21&hasta=2026-12-18',
    );
    expect(within(filas[2]).getAllByRole('cell')[0]).toHaveTextContent('—');
    expect(within(filas[2]).getByText(/Datos insuficientes/)).toBeInTheDocument();
    // La RL4 es una más: nada la destaca.
    expect(filas[2].className).toBe('');
    expect(screen.getByText(/La más puntual, R3 \(el 90,5 %/)).toBeInTheDocument();
    // Cobertura del 52 %: aviso.
    expect(screen.getByText(/la cifra es solo de los trenes que se ven/)).toBeInTheDocument();
  });

  it('toda España: el ranking de los núcleos, que enlaza con cada uno', async () => {
    api({ '/api/estadisticas/nucleos/todos': TODOS });
    render(<PaginaEstadisticas ruta={{ tipo: 'todos', desde: null, hasta: null }} />);
    const tabla = await screen.findByRole('table', { name: 'Ranking de puntualidad de los núcleos' });
    expect(within(tabla).getByRole('link', { name: 'Valencia' })).toHaveAttribute(
      'href',
      '/estadisticas/40?desde=2026-11-21&hasta=2026-12-18',
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Puntualidad en toda España' })).toBeInTheDocument();
    expect(screen.getByText(/las líneas se comparan dentro de cada núcleo/i)).toBeInTheDocument();
  });

  it('los ejemplos de docs/api (datos reales de unas horas) se pintan sin romperse', async () => {
    for (const [ruta, datos, tipo] of [
      ['/api/estadisticas/nucleos/todos', ejemploTodos, { tipo: 'todos' as const, desde: null, hasta: null }],
      ['/api/estadisticas/nucleos/51', ejemploNucleo, { tipo: 'nucleo' as const, nucleo: 51, desde: null, hasta: null }],
      [
        `/api/estadisticas/lineas/51/${ejemploLinea.linea}`,
        ejemploLinea,
        { tipo: 'linea' as const, nucleo: 51, linea: ejemploLinea.linea, desde: null, hasta: null },
      ],
    ] as const) {
      api({ [ruta]: datos });
      const { unmount } = render(<PaginaEstadisticas ruta={tipo} />);
      expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText('Cargando las cifras…')).toBeNull());
      expect(screen.queryByRole('alert')).toBeNull();
      unmount();
    }
  });
});

describe('puntualidad en la ficha de la estación', () => {
  it('cifras por línea con enlace a la página de cada una', async () => {
    api({ '/api/estadisticas/estaciones/71801': ejemploEstacion });
    render(<PuntualidadEstacion id="71801" />);
    expect(await screen.findByRole('heading', { name: 'Puntualidad' })).toBeInTheDocument();
    const tabla = await screen.findByRole('table', { name: /Puntualidad por línea en Barcelona-Sants/ });
    const primera = within(tabla).getAllByRole('link')[0];
    expect(primera.getAttribute('href')).toMatch(/^\/estadisticas\/51\//);
    expect(screen.getByText(/Solo trenes vistos en directo/)).toBeInTheDocument();
  });

  it('antes de publicar, solo el texto de cuándo', async () => {
    api({ '/api/estadisticas/estaciones/71801': { ...ejemploEstacion, publicacion: SIN_PUBLICAR, resumen: null, porLinea: [] } });
    render(<PuntualidadEstacion id="71801" />);
    expect(await screen.findByText(SIN_PUBLICAR.texto)).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
