import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import avisos from '../../docs/api/avisos.json';
import instantanea from '../../docs/api/directo.json';
import fichaEstacion from '../../docs/api/fichaEstacion.json';
import fichaTren from '../../docs/api/fichaTren.json';
import nucleosEjemplo from '../../docs/api/nucleos.json';
import type { PropsMapa } from './mapa/Mapa';
import { FuenteFalsa } from './pruebas/FuenteFalsa';
import App from './App';

// MapLibre necesita WebGL, que jsdom no tiene: el mapa se sustituye por botones (uno por tren pintado).
vi.mock('./mapa/Mapa', () => ({
  default: (props: PropsMapa) => (
    <div
      data-testid="mapa"
      aria-label={props.descripcion}
      data-encuadre={props.encuadre?.clave ?? ''}
    >
      {props.trenes.map((t) => (
        <button key={t.id} type="button" onClick={() => props.alPulsarTren(t.id)}>
          símbolo {t.id}
        </button>
      ))}
    </div>
  ),
}));

// Bilbao, sin datos en directo (como en la fase 0), para probar su aviso.
const nucleos = nucleosEjemplo.map((n) =>
  n.id === 60 ? { ...n, estado: 'SIN_DATOS_EN_DIRECTO', estadoTexto: 'sin datos en directo' } : n,
);

function respuesta(cuerpo: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(cuerpo), { status, headers: { 'Content-Type': 'application/json' } }),
  );
}

function prepararApi() {
  const fetch = vi.fn((url: string) => {
    if (url === '/api/nucleos') return respuesta(nucleos);
    if (url.startsWith('/api/avisos')) return respuesta(avisos);
    if (url.startsWith('/api/trenes/NO_EXISTE')) return respuesta({ title: 'No encontrado', status: 404 }, 404);
    if (url.startsWith('/api/trenes/')) return respuesta(fichaTren);
    if (url.startsWith('/api/estaciones/')) return respuesta(fichaEstacion);
    return respuesta({ title: 'No encontrado' }, 404);
  });
  vi.stubGlobal('fetch', fetch);
  vi.stubGlobal('EventSource', FuenteFalsa);
  return fetch;
}

async function conDirecto(foto: unknown = instantanea) {
  render(<App />);
  await waitFor(() => expect(FuenteFalsa.creadas.length).toBeGreaterThan(0));
  act(() => {
    FuenteFalsa.ultima().abrir();
    FuenteFalsa.ultima().enviar('instantanea', foto);
  });
}

/** La instantánea de toda España: los trenes de Rodalies del ejemplo y un C5 de Madrid. */
const instantaneaDeTodos = {
  ...instantanea,
  nucleo: null,
  trenes: [...instantanea.trenes, { ...instantanea.trenes[0], id: '1099C5', linea: 'C5', nucleo: 10, destino: 'Humanes' }],
};

beforeEach(() => {
  FuenteFalsa.creadas = [];
  window.history.replaceState(null, '', '/');
});

describe('App', () => {
  it('avisa de que no es oficial y cita los datos y el mapa', async () => {
    prepararApi();
    render(<App />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('TrainWatch');
    expect(screen.getByRole('note')).toHaveTextContent('no oficial');
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Datos: Renfe (CC BY 4.0)');
    expect(screen.getByRole('link', { name: '© OpenStreetMap' })).toHaveAttribute('href', 'https://www.openstreetmap.org/copyright');
    expect(screen.getByRole('link', { name: 'OpenFreeMap' })).toBeInTheDocument();
    await screen.findByRole('option', { name: 'Madrid' });
  });

  it('abre el directo de Rodalies por defecto, sin ninguna línea destacada', async () => {
    prepararApi();
    await conDirecto();
    expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=51');
    expect(screen.getByRole('combobox', { name: 'Núcleo' })).toHaveValue('51');
    // La RL4 ya no se destaca (Iván, 21-11): ni estrella, ni botón, ni sale en el filtro si no hay trenes suyos.
    expect(screen.queryByText(/★/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /RL4/ })).not.toBeInTheDocument();
    expect(within(screen.getByRole('combobox', { name: 'Línea' })).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Todas las líneas',
      'R13',
      'R15',
    ]);
    expect(screen.getByText('2 trenes en marcha y 1 en origen')).toBeInTheDocument();
    expect(screen.getByText(/^En directo · dato de Renfe hace 16 s$/)).toBeInTheDocument();
    // Los tres trenes de la instantánea, en el mapa.
    expect(within(screen.getByTestId('mapa')).getAllByRole('button')).toHaveLength(3);
  });

  it('enseña el estado del núcleo: «sin datos en directo» en Bilbao', async () => {
    prepararApi();
    await conDirecto();
    const selector = screen.getByRole('combobox', { name: 'Núcleo' });
    expect(within(selector).getByRole('option', { name: 'Bilbao (sin datos en directo)' })).toBeInTheDocument();
    fireEvent.change(selector, { target: { value: '60' } });
    expect(await screen.findByText(/Bilbao: sin datos en directo/)).toBeInTheDocument();
    await waitFor(() => expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=60'));
    expect(FuenteFalsa.creadas[0].cerrada).toBe(true);
    expect(window.location.search).toBe('?nucleo=60');
  });

  it('filtra por línea en el mapa y en la lista', async () => {
    prepararApi();
    await conDirecto();
    fireEvent.change(screen.getByRole('combobox', { name: 'Línea' }), { target: { value: 'R13' } });
    expect(within(screen.getByTestId('mapa')).getAllByRole('button')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Lista' }));
    const lista = screen.getByRole('region', { name: /^Trenes de/ });
    expect(within(lista).getAllByRole('button')).toHaveLength(1);
    expect(lista).toHaveTextContent('Hacia La Plana-Picamoixons');
  });

  it('la lista tiene los mismos trenes, como botones, y abre la misma ficha; Escape la cierra y devuelve el foco', async () => {
    prepararApi();
    await conDirecto();
    fireEvent.click(screen.getByRole('button', { name: 'Lista' }));
    expect(screen.getByRole('button', { name: 'Lista' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'Trenes de Rodalies de Catalunya' })).toBeInTheDocument();
    const botones = within(screen.getByRole('region', { name: /^Trenes de/ })).getAllByRole('button');
    expect(botones).toHaveLength(3);
    // En marcha primero; el de origen, con su hora de salida.
    expect(botones[2]).toHaveTextContent('Sale a las 15:15');
    expect(botones[0]).toHaveTextContent('R13');
    expect(botones[1]).toHaveTextContent('5 min de retraso');

    botones[1].focus();
    fireEvent.click(botones[1]);
    const ficha = await screen.findByRole('complementary', { name: 'R15 hacia Reus' });
    expect(within(ficha).getByRole('heading', { name: 'R15 hacia Reus' })).toHaveFocus();
    expect(within(ficha).getByText('Torredembarra')).toBeInTheDocument();
    // Las paradas, de la ficha de la API.
    expect(await within(ficha).findByText("L'Hospitalet de Llobregat → Blanes")).toBeInTheDocument();
    expect(within(ficha).getByRole('heading', { name: 'Paradas' })).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
    await waitFor(() => expect(botones[1]).toHaveFocus());
  });

  it('pasa de la ficha del tren a la de una estación, con sus próximos trenes y avisos', async () => {
    prepararApi();
    await conDirecto();
    fireEvent.click(screen.getByRole('button', { name: 'símbolo 5123V15033R15' }));
    const ficha = await screen.findByRole('complementary');
    fireEvent.click(await within(ficha).findByRole('button', { name: 'Barcelona-Sants' }));
    const estacion = await screen.findByRole('complementary', { name: 'Barcelona-Sants' });
    expect(within(estacion).getByRole('table', { name: 'Próximos trenes en Barcelona-Sants' })).toBeInTheDocument();
    expect(within(estacion).getByRole('button', { name: 'R17 hacia Barcelona Estació de França' })).toBeInTheDocument();
    expect(within(estacion).getByRole('heading', { name: 'Avisos' })).toBeInTheDocument();
  });

  it('enseña los avisos del núcleo', async () => {
    prepararApi();
    await conDirecto();
    const boton = await screen.findByRole('button', { name: 'Avisos (3)' });
    fireEvent.click(boton);
    const hoja = await screen.findByRole('complementary', { name: 'Avisos de Rodalies de Catalunya' });
    expect(within(hoja).getAllByRole('listitem')).toHaveLength(3);
  });

  it('cambiar de línea con los avisos abiertos cambia su título sin quitarle el foco al selector', async () => {
    prepararApi();
    await conDirecto();
    fireEvent.click(await screen.findByRole('button', { name: 'Avisos (3)' }));
    await screen.findByRole('complementary', { name: 'Avisos de Rodalies de Catalunya' });
    const selector = screen.getByRole('combobox', { name: 'Línea' });
    selector.focus();
    fireEvent.change(selector, { target: { value: 'R13' } });
    expect(await screen.findByRole('complementary', { name: 'Avisos de la R13' })).toBeInTheDocument();
    expect(selector).toHaveFocus();
  });

  it('la RL4 de la URL es una línea más: sale en el filtro, en su orden y sin estrella', async () => {
    prepararApi();
    window.history.replaceState(null, '', '/?linea=RL4');
    await conDirecto();
    const lineas = screen.getByRole('combobox', { name: 'Línea' });
    expect(lineas).toHaveValue('RL4');
    expect(within(lineas).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Todas las líneas',
      'R13',
      'R15',
      'RL4',
    ]);
  });

  it('con «Toda España» (por URL) abre el directo de todos, sin filtro de línea y con el núcleo en la lista', async () => {
    const fetch = prepararApi();
    window.history.replaceState(null, '', '/?nucleo=todos&linea=R13');
    await conDirecto(instantaneaDeTodos);
    expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=todos');
    expect(screen.getByRole('combobox', { name: 'Núcleo' })).toHaveValue('todos');
    // Sin filtro de línea (los nombres se repiten entre núcleos), y dice por qué.
    const lineas = screen.getByRole('combobox', { name: 'Línea' });
    expect(lineas).toBeDisabled();
    expect(lineas).toHaveAccessibleDescription(/elige un núcleo/);
    expect(window.location.search).toBe('?nucleo=todos');
    // Los cuatro trenes en el mapa, encuadrando toda España.
    const mapa = await screen.findByTestId('mapa');
    expect(within(mapa).getAllByRole('button')).toHaveLength(4);
    await waitFor(() => expect(mapa).toHaveAttribute('data-encuadre', expect.stringMatching(/^todos:/)));
    expect(mapa).toHaveAccessibleName(/^Mapa de toda España: 2 trenes en marcha y 2 en origen/);
    // Los avisos de todos los núcleos.
    expect(fetch).toHaveBeenCalledWith('/api/avisos', expect.anything());

    // La lista: el núcleo de cada tren, ordenada por núcleo (Madrid antes que Rodalies).
    fireEvent.click(screen.getByRole('button', { name: 'Lista' }));
    const lista = screen.getByRole('region', { name: 'Trenes de toda España' });
    const botones = within(lista).getAllByRole('button');
    expect(botones).toHaveLength(4);
    expect(botones[0]).toHaveTextContent(/^C5Madrid/);
    expect(botones[1]).toHaveTextContent('Rodalies de Catalunya');
  });

  it('«Toda España» se elige en el selector y se vuelve a un núcleo', async () => {
    prepararApi();
    await conDirecto();
    expect(screen.getByTestId('mapa')).toHaveAttribute('data-encuadre', '51');
    const selector = screen.getByRole('combobox', { name: 'Núcleo' });
    expect(within(selector).getAllByRole('option')[0]).toHaveTextContent('Toda España');
    fireEvent.change(selector, { target: { value: 'todos' } });
    await waitFor(() => expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=todos'));
    expect(window.location.search).toBe('?nucleo=todos');

    expect(screen.getByRole('combobox', { name: 'Línea' })).toBeDisabled();

    fireEvent.change(selector, { target: { value: '10' } });
    await waitFor(() => expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=10'));
    expect(window.location.search).toBe('?nucleo=10');
    expect(screen.getByRole('combobox', { name: 'Línea' })).toBeEnabled();
    expect(screen.getByTestId('mapa')).toHaveAttribute('data-encuadre', '10');
  });

  it('un núcleo de la URL que no existe vuelve a Rodalies', async () => {
    prepararApi();
    window.history.replaceState(null, '', '/?nucleo=999');
    render(<App />);
    await screen.findByRole('option', { name: 'Madrid' });
    expect(screen.getByRole('combobox', { name: 'Núcleo' })).toHaveValue('51');
    await waitFor(() => expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=51'));
  });

  it('avisa con «datos de hace X min» si el directo se para', async () => {
    prepararApi();
    await conDirecto();
    act(() => FuenteFalsa.ultima().enviar('latido', { instante: '', ultimoDato: '', antiguedadS: 400 }));
    expect(screen.getByText('Datos de hace 6 min: el directo está parado')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Los datos en directo están parados.');
  });

  it('dice que reintenta si el directo responde con un error (503)', async () => {
    prepararApi();
    await conDirecto();
    act(() => FuenteFalsa.ultima().fallar(true));
    expect(screen.getByText(/Sin conexión con el directo: se reintenta en \d s/)).toBeInTheDocument();
  });
});
