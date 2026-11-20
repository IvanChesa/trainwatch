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
    <div data-testid="mapa" aria-label={props.descripcion}>
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

async function conDirecto() {
  render(<App />);
  await waitFor(() => expect(FuenteFalsa.creadas.length).toBeGreaterThan(0));
  act(() => {
    FuenteFalsa.ultima().abrir();
    FuenteFalsa.ultima().enviar('instantanea', instantanea);
  });
}

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

  it('abre el directo de Rodalies por defecto, con la RL4 destacada', async () => {
    prepararApi();
    await conDirecto();
    expect(FuenteFalsa.ultima().url).toBe('/api/directo?nucleo=51');
    expect(screen.getByRole('combobox', { name: 'Núcleo' })).toHaveValue('51');
    expect(screen.getByRole('option', { name: 'RL4 (Lleida) ★' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Ver la RL4 de Lleida/ })).toBeInTheDocument();
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
    const lista = screen.getByRole('list');
    expect(within(lista).getAllByRole('button')).toHaveLength(1);
    expect(lista).toHaveTextContent('Hacia La Plana-Picamoixons');
  });

  it('la lista tiene los mismos trenes, como botones, y abre la misma ficha; Escape la cierra y devuelve el foco', async () => {
    prepararApi();
    await conDirecto();
    fireEvent.click(screen.getByRole('button', { name: 'Lista' }));
    expect(screen.getByRole('button', { name: 'Lista' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'Trenes de Rodalies de Catalunya' })).toBeInTheDocument();
    const botones = within(screen.getByRole('list')).getAllByRole('button');
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

  it('avisa con «datos de hace X min» si el directo se para', async () => {
    prepararApi();
    await conDirecto();
    act(() => FuenteFalsa.ultima().enviar('latido', { instante: '', ultimoDato: '', antiguedadS: 400 }));
    expect(screen.getByText('Datos hace 6 min: el directo está parado')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Los datos en directo están parados.');
  });

  it('dice que reintenta si el directo responde con un error (503)', async () => {
    prepararApi();
    await conDirecto();
    act(() => FuenteFalsa.ultima().fallar(true));
    expect(screen.getByText(/Sin conexión con el directo: se reintenta en \d s/)).toBeInTheDocument();
  });
});
