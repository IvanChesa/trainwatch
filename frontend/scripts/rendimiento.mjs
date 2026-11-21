// Fluidez del mapa con un núcleo y con toda España (fase 5b): fotogramas por segundo, intervalos entre fotogramas,
// tareas largas y tiempo de JavaScript del hilo principal durante 15 s de movimiento con datos reales.
//
// Requisitos: la build servida en http://localhost:5173 contra una API con el directo de todos los núcleos (en local,
// `vite preview` con el backend en el 8080). Chromium sin interfaz y WebGL por software (SwiftShader), como en la CI:
// las cifras son pesimistas frente a un navegador con tarjeta gráfica; sirven para comparar los dos casos.
//   node scripts/rendimiento.mjs [segundos]
import { chromium } from '@playwright/test';

const WEB = process.env.TW_WEB ?? 'http://localhost:5173';
const segundos = Number(process.argv[2] ?? 15);
const navegador = await chromium.launch({ args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });

async function medir(ruta) {
  const pagina = await navegador.newPage({ viewport: { width: 1440, height: 900 } });
  await pagina.goto(`${WEB}${ruta}`);
  await pagina.locator('.mapa[data-listo="si"]').waitFor({ timeout: 20_000 });
  await pagina.getByText(/En directo · dato/).waitFor({ timeout: 30_000 });
  await pagina.waitForFunction(() => window.__twMapa?.loaded() && !window.__twMapa.isMoving(), null, { timeout: 20_000 });
  await pagina.waitForTimeout(2000);
  const cdp = await pagina.context().newCDPSession(pagina);
  await cdp.send('Performance.enable');
  const antes = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
  const r = await pagina.evaluate(async (ms) => {
    const largas = [];
    const observador = new PerformanceObserver((l) => l.getEntries().forEach((e) => largas.push(e.duration)));
    observador.observe({ type: 'longtask' });
    const marcos = [];
    let ultimo = performance.now();
    const fin = ultimo + ms;
    await new Promise((listo) => {
      const paso = (t) => {
        marcos.push(t - ultimo);
        ultimo = t;
        if (t < fin) requestAnimationFrame(paso);
        else listo();
      };
      requestAnimationFrame(paso);
    });
    observador.disconnect();
    marcos.sort((a, b) => a - b);
    const p = (q) => Math.round(marcos[Math.floor(q * (marcos.length - 1))]);
    return {
      fps: Math.round((marcos.length / (ms / 1000)) * 10) / 10,
      intervaloMs: { p50: p(0.5), p95: p(0.95), max: Math.round(marcos.at(-1)) },
      tareasLargas: largas.length,
      tareaLargaMaxMs: Math.round(Math.max(0, ...largas)),
      trenes: document.querySelector('canvas.maplibregl-canvas').getAttribute('aria-label').replace(/\. Para.*/, ''),
    };
  }, segundos * 1000);
  const despues = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
  r.jsPorSegundoMs = Math.round(((despues.ScriptDuration - antes.ScriptDuration) * 1000) / segundos);
  r.hiloPrincipalPorSegundoMs = Math.round(((despues.TaskDuration - antes.TaskDuration) * 1000) / segundos);
  r.heapMb = Math.round(despues.JSHeapUsedSize / 1e6);
  await pagina.close();
  return r;
}

for (const [nombre, ruta] of [
  ['Rodalies', '/'],
  ['Toda España', '/?nucleo=todos'],
]) {
  console.log(nombre, JSON.stringify(await medir(ruta)));
}
await navegador.close();
