// API simulada para las pruebas e2e: sirve los ejemplos de docs/api/ (el contrato de la fase 4) y un directo por SSE
// falso que mueve un tren cada segundo. Sin dependencias: Node y su módulo http.
//
//   node e2e/api-simulada.mjs [puerto]
//
// Control para las pruebas: GET /control/rechazar?n=2 hace que las 2 próximas conexiones al directo reciban un 503
// con Retry-After (como cuando la API llega al máximo de conexiones).
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';

const puerto = Number(process.argv[2] ?? 8091);
const ejemplo = (nombre) => JSON.parse(readFileSync(new URL(`../../docs/api/${nombre}.json`, import.meta.url), 'utf8'));

const nucleos = ejemplo('nucleos');
const instantanea = ejemplo('directo');
const latido = ejemplo('directo-latido');
const fichaTren = ejemplo('fichaTren');
const fichaEstacion = ejemplo('fichaEstacion');
const linea = ejemplo('linea');
const avisos = ejemplo('avisos');
const trenes = ejemplo('trenesDeNucleo');
// Fase 6: las estadísticas (los ejemplos de docs/api, con unas horas de datos reales).
const puntualidadTodos = ejemplo('puntualidadTodos');
const puntualidadNucleo = ejemplo('puntualidadNucleo');
// La línea, con datos inventados pero suficientes (los ejemplos reales son de unas horas y casi todo sale
// «insuficiente»): así el e2e pinta los gráficos de verdad (20 horas, 7 días, 5 semanas, reparto y tramos).
const puntualidadLinea = JSON.parse(readFileSync(new URL('./puntualidad-linea.json', import.meta.url), 'utf8'));
const puntualidadEstacion = ejemplo('puntualidadEstacion');

/** Con `nucleo=todos`, además de los de Rodalies: un tren en Madrid, otro en Sevilla y otro en Bilbao. */
const DE_OTROS_NUCLEOS = [
  { id: '1099C5', linea: 'C5', nucleo: 10, destino: 'Humanes', lat: 40.4065, lon: -3.6913 },
  { id: '3099C1', linea: 'C1', nucleo: 30, destino: 'Lora del Río', lat: 37.3917, lon: -5.9751 },
  { id: '6099C3', linea: 'C3', nucleo: 60, destino: 'Muskiz', lat: 43.2591, lon: -2.9264 },
];

/** El tren que se mueve: el R15 en marcha hacia Torredembarra (rumbo 251°, hacia el suroeste). */
export const TREN_QUE_SE_MUEVE = '5123V15033R15';
let rechazar = 0;

function json(res, cuerpo, status = 200, cabeceras = {}) {
  res.writeHead(status, { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...cabeceras });
  res.end(JSON.stringify(cuerpo));
}

function directo(req, res, nucleo) {
  if (rechazar > 0) {
    rechazar -= 1;
    return json(res, { title: 'Directo completo', status: 503, detail: 'Vuelve a intentarlo más tarde.' }, 503, {
      'Retry-After': '1',
    });
  }
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
  });
  let secuencia = 1;
  const ahora = () => new Date().toISOString();
  const enviar = (evento, datos) => res.write(`id: ${secuencia++}\nevent: ${evento}\ndata: ${JSON.stringify(datos)}\n\n`);

  // El directo de todos (nucleo=todos) lleva `nucleo: null` en sus eventos y cada tren con el suyo.
  const todos = nucleo === 'todos';
  const base = structuredClone(instantanea.trenes).map((t) => ({ ...t, nucleo: todos ? 51 : nucleo }));
  if (todos) {
    const modelo = instantanea.trenes.find((t) => t.estado === 'EN_MARCHA');
    for (const otro of DE_OTROS_NUCLEOS) base.push({ ...structuredClone(modelo), ...otro, proximaParada: null });
    nucleo = null;
  }
  enviar('instantanea', { ...instantanea, nucleo, secuencia, generado: ahora(), ultimoDato: ahora(), trenes: base });

  let paso = 0;
  const moverse = setInterval(() => {
    paso += 1;
    const tren = base.find((t) => t.id === TREN_QUE_SE_MUEVE);
    // ~150 m por segundo hacia el suroeste.
    tren.lon = Math.round((tren.lon - 0.0016) * 1e5) / 1e5;
    tren.lat = Math.round((tren.lat - 0.0005) * 1e5) / 1e5;
    tren.retrasoMin = 5 + Math.floor(paso / 10);
    tren.actualizado = ahora();
    enviar('cambios', { secuencia, nucleo, generado: ahora(), ultimoDato: ahora(), altas: [], cambios: [tren], bajas: [] });
  }, 1000);
  const latir = setInterval(() => enviar('latido', { ...latido, instante: ahora(), ultimoDato: ahora(), antiguedadS: 1 }), 3000);
  req.on('close', () => {
    clearInterval(moverse);
    clearInterval(latir);
  });
}

createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${puerto}`);
  const ruta = url.pathname;
  if (ruta === '/control/rechazar') {
    rechazar = Number(url.searchParams.get('n') ?? 1);
    return json(res, { rechazar });
  }
  if (ruta === '/api/nucleos') return json(res, nucleos);
  if (ruta === '/api/directo') {
    const nucleo = url.searchParams.get('nucleo') ?? '51';
    return directo(req, res, nucleo === 'todos' ? 'todos' : Number(nucleo));
  }
  if (ruta === '/api/avisos') return json(res, avisos);
  if (/^\/api\/nucleos\/\d+\/trenes$/.test(ruta)) return json(res, trenes);
  if (ruta.startsWith('/api/trenes/')) return json(res, { ...fichaTren, id: decodeURIComponent(ruta.slice(12)) });
  if (ruta.startsWith('/api/estaciones/')) return json(res, fichaEstacion);
  if (ruta.startsWith('/api/lineas/')) return json(res, linea);
  if (ruta === '/api/estadisticas/nucleos/todos') return json(res, puntualidadTodos);
  if (/^\/api\/estadisticas\/nucleos\/\d+$/.test(ruta)) return json(res, puntualidadNucleo);
  if (ruta.startsWith('/api/estadisticas/lineas/')) return json(res, puntualidadLinea);
  if (ruta.startsWith('/api/estadisticas/estaciones/')) return json(res, puntualidadEstacion);
  return json(res, { title: 'No encontrado', status: 404, detail: `No existe ${ruta}` }, 404);
}).listen(puerto, () => console.log(`API simulada en http://localhost:${puerto}`));
