import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

/**
 * Fase 6: las páginas de puntualidad contra la build de producción (`vite preview`, con la CSP de `public/_headers`) y
 * la API simulada, que sirve los ejemplos de `docs/api/` (unas horas de datos reales del 21-11-2026).
 */
// La API simulada sirve para la línea un JSON con datos suficientes (e2e/puntualidad-linea.json), con gráficos que pintar.
const linea = JSON.parse(readFileSync(new URL('./puntualidad-linea.json', import.meta.url), 'utf8')) as {
  linea: string;
  nucleo: number;
  porHora: unknown[];
};

/** Apunta los errores de la consola y las violaciones de la CSP. */
async function vigilar(page: Page): Promise<string[]> {
  const errores: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errores.push(m.text());
  });
  page.on('pageerror', (e) => errores.push(e.message));
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) =>
      console.error(`CSP: ${e.violatedDirective} ${e.blockedURI}`),
    );
  });
  // El mapa no se usa aquí, pero si alguna prueba lo abre, que no salga a la red.
  await page.route('https://tiles.openfreemap.org/**', (r) => r.fulfill({ json: { version: 8, sources: {}, layers: [] } }));
  return errores;
}

test('la página de una línea: la cifra con su contexto, cada gráfico con su tabla, y sin errores ni CSP', async ({
  page,
}) => {
  const errores = await vigilar(page);
  await page.goto(`/estadisticas/${linea.nucleo}/${encodeURIComponent(linea.linea)}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Puntualidad de la ${linea.linea} (Rodalies de Catalunya)`);
  await expect(page).toHaveTitle(`Puntualidad de la ${linea.linea} (Rodalies de Catalunya) · TrainWatch`);
  // Siempre a la vista: el periodo, «solo trenes vistos en directo», la cobertura, la fuente y el aviso de no oficial.
  await expect(page.getByText(/^(El|Del) \d+ de \w+/)).toBeVisible();
  await expect(page.getByText(/Solo trenes vistos en directo/)).toBeVisible();
  await expect(page.getByText('Cobertura de la línea')).toBeVisible();
  await expect(page.getByText(/Datos: Renfe \(CC BY 4\.0\)/)).toBeVisible();
  await expect(page.getByRole('banner').getByText('no oficial')).toBeVisible();
  // Por hora: su tabla, con una fila por hora, en el orden del día de servicio (las 00:00, al final).
  const porHora = page.getByRole('table', { name: `Puntualidad de la ${linea.linea} por hora` });
  await expect(porHora).toBeVisible();
  await expect(porHora.getByRole('row')).toHaveCount(linea.porHora.length + 1);
  await expect(porHora.getByRole('rowheader').last()).toHaveText('00:00');
  // Y su gráfico: una columna por hora con muestra (18 de 20), a su tamaño natural, sin encogerse.
  const grafico = page.locator('#por-hora-titulo ~ .bloque__cuerpo svg.grafico');
  await expect(grafico.locator('rect.grafico__columna')).toHaveCount(18);
  const ancho = Number(await grafico.getAttribute('width'));
  expect((await grafico.boundingBox())!.width).toBeCloseTo(ancho, 0);
  // El reparto con su texto, y los tramos.
  await expect(page.getByRole('table', { name: 'Paradas por retraso al llegar' })).toContainText('Menos de 3 min');
  await expect(page.getByRole('table', { name: /Tramos de la .* con más retraso/ })).toContainText('Granollers Centre');
  // La cobertura de la línea y la de su núcleo.
  await expect(page.getByText('Cobertura de Rodalies de Catalunya')).toBeVisible();
  // Las migas llevan al núcleo, y de ahí a toda España.
  await page.getByRole('navigation', { name: 'Estás en' }).getByRole('link', { name: 'Rodalies de Catalunya' }).click();
  await expect(page).toHaveURL(/\/estadisticas\/51$/);
  await expect(page.getByRole('table', { name: /Ranking de puntualidad de las líneas/ })).toBeVisible();
  await page.getByRole('navigation', { name: 'Estás en' }).getByRole('link', { name: 'Toda España' }).click();
  await expect(page.getByRole('table', { name: 'Ranking de puntualidad de los núcleos' })).toBeVisible();
  expect(errores).toEqual([]);
});

test('se cambia el periodo con las fechas y la URL lo guarda', async ({ page }) => {
  await vigilar(page);
  await page.goto('/estadisticas/51');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Puntualidad en Rodalies de Catalunya');
  await page.getByLabel('Desde').fill('2026-11-14');
  await page.getByLabel('Hasta').fill('2026-11-20');
  await page.getByRole('button', { name: 'Ver el periodo' }).click();
  await expect(page).toHaveURL(/\/estadisticas\/51\?desde=2026-11-14&hasta=2026-11-20$/);
});

test('desde el mapa se llega a la puntualidad del núcleo', async ({ page }) => {
  await vigilar(page);
  await page.goto('/');
  await page.getByRole('link', { name: 'Puntualidad' }).click();
  await expect(page).toHaveURL(/\/estadisticas\/51$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Puntualidad en Rodalies de Catalunya');
});

test('sin desplazamiento horizontal en el móvil', async ({ page }) => {
  await vigilar(page);
  await page.goto(`/estadisticas/${linea.nucleo}/${encodeURIComponent(linea.linea)}`);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  // El gráfico de 20 horas no cabe en un móvil: se desplaza dentro de su caja, y la página no se sale de lado.
  await expect(page.locator('svg.grafico').first()).toBeVisible();
  const sobra = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(sobra).toBeLessThanOrEqual(0);
  // La página se desplaza en el documento (no dentro de un div): AvPág funciona desde el principio.
  await page.keyboard.press('PageDown');
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
});
