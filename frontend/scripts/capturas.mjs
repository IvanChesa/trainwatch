// Capturas de la web a 1440 y 375 px y medida del movimiento de los trenes con datos reales.
//
// Requisitos: la build servida en http://localhost:5173 contra una API con datos reales: la de producción (CORS admite
// ese origen) o, para lo que aún no está desplegado (como «Toda España»), el backend local leyendo los feeds de Renfe:
//   VITE_API_URL=https://trainwatch-api.ivanchesa.com npx vite build && npx vite preview --port 5173
//   (o, con el backend en el 8080: npx vite build && npx vite preview --port 5173)
//   node scripts/capturas.mjs [carpeta]   (por defecto, ../docs/capturas/fase-5b)
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium, devices } from '@playwright/test';

const WEB = process.env.TW_WEB ?? 'http://localhost:5173';
const carpeta = process.argv[2] ?? fileURLToPath(new URL('../../docs/capturas/fase-5b/', import.meta.url));
mkdirSync(carpeta, { recursive: true });

const navegador = await chromium.launch({ args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
const problemas = [];

async function abrir(contexto, ruta = '/') {
  const page = await contexto.newPage();
  page.on('console', (m) => m.type() === 'error' && problemas.push(`consola: ${m.text()}`));
  page.on('pageerror', (e) => problemas.push(`error: ${e.message}`));
  await page.addInitScript(() =>
    document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP: ${e.violatedDirective} ${e.blockedURI}`)),
  );
  await page.goto(`${WEB}${ruta}`);
  await page.locator('.mapa[data-listo="si"]').waitFor({ timeout: 20_000 });
  await page.getByText(/En directo · dato/).waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.__twMapa?.loaded() && !window.__twMapa.isMoving(), null, { timeout: 20_000 });
  await page.waitForTimeout(2500);
  return page;
}

/** Posiciones pintadas de los trenes en marcha, por id. */
const posiciones = (page) =>
  page.evaluate(() =>
    Object.fromEntries(
      window.__twMapa
        .getSource('tw-trenes')
        .serialize()
        .data.features.filter((f) => f.properties.icono !== 'tw-origen')
        .map((f) => [f.properties.id, f.geometry.coordinates]),
    ),
  );

function metros(a, b) {
  const rad = Math.PI / 180;
  const x = (b[0] - a[0]) * rad * Math.cos(((a[1] + b[1]) / 2) * rad);
  const y = (b[1] - a[1]) * rad;
  return Math.hypot(x, y) * 6_371_000;
}

// ---- Escritorio, 1440 px ----
const escritorio = await navegador.newContext({ viewport: { width: 1440, height: 900 } });
let page = await abrir(escritorio);
await page.screenshot({ path: `${carpeta}/escritorio-mapa.png` });

// Movimiento con datos reales: 20 muestras, una por segundo.
const muestras = [];
for (let i = 0; i < 20; i++) {
  muestras.push(await posiciones(page));
  await page.waitForTimeout(1000);
}
const ids = Object.keys(muestras[0]).filter((id) => muestras.every((m) => m[id]));
let seMueven = 0;
let distancia = 0;
let pasos = 0;
for (const id of ids) {
  const serie = muestras.map((m) => m[id]);
  const distintas = new Set(serie.map((p) => p.join(','))).size;
  if (distintas > 1) seMueven += 1;
  for (let i = 1; i < serie.length; i++) {
    const d = metros(serie[i - 1], serie[i]);
    if (d > 0) pasos += 1;
    distancia += d;
  }
}
console.log(
  JSON.stringify({
    trenesEnMarchaPintados: ids.length,
    seMovieronEn20s: seMueven,
    segundosConMovimientoPorTren: +(pasos / Math.max(1, seMueven)).toFixed(1),
    metrosMediosPorTrenEn20s: Math.round(distancia / Math.max(1, seMueven)),
  }),
);

await page.getByRole('button', { name: 'Lista' }).click();
await page.waitForTimeout(500);
await page.screenshot({ path: `${carpeta}/escritorio-lista.png` });
await page.getByRole('region', { name: /^Trenes de/ }).getByRole('button').first().click();
await page.getByRole('heading', { name: 'Paradas' }).waitFor({ timeout: 10_000 });
await page.getByRole('button', { name: 'Mapa' }).click();
await page.waitForTimeout(1500);
await page.screenshot({ path: `${carpeta}/escritorio-ficha-tren.png` });
await page.close();

page = await abrir(escritorio, '/?nucleo=todos');
await page.screenshot({ path: `${carpeta}/escritorio-toda-espana.png` });
await page.getByRole('button', { name: 'Lista' }).click();
await page.waitForTimeout(500);
await page.screenshot({ path: `${carpeta}/escritorio-toda-espana-lista.png` });
await page.close();
await escritorio.close();

// ---- Móvil, 375 px ----
const movil = await navegador.newContext({ ...devices['iPhone 13 mini'], viewport: { width: 375, height: 812 } });
page = await abrir(movil);
await page.screenshot({ path: `${carpeta}/movil-mapa.png` });
await page.getByRole('button', { name: 'Lista' }).click();
await page.waitForTimeout(500);
await page.screenshot({ path: `${carpeta}/movil-lista.png` });
await page.getByRole('region', { name: /^Trenes de/ }).getByRole('button').first().click();
await page.getByRole('heading', { name: 'Paradas' }).waitFor({ timeout: 10_000 });
await page.getByRole('button', { name: 'Mapa' }).click();
await page.waitForTimeout(1500);
await page.screenshot({ path: `${carpeta}/movil-ficha-tren.png` });
await page.close();
page = await abrir(movil, '/?nucleo=todos');
await page.screenshot({ path: `${carpeta}/movil-toda-espana.png` });
await page.close();
await movil.close();

await navegador.close();
console.log(problemas.length ? `PROBLEMAS:\n${problemas.join('\n')}` : 'Sin errores en la consola ni violaciones de la CSP.');
