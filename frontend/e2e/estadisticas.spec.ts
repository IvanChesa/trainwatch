import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

/**
 * Fase 6: las páginas de puntualidad contra la build de producción (`vite preview`, con la CSP de `public/_headers`) y
 * la API simulada, que sirve los ejemplos de `docs/api/` (unas horas de datos reales del 21-11-2026).
 */
const linea = JSON.parse(readFileSync(new URL('../../docs/api/puntualidadLinea.json', import.meta.url), 'utf8')) as {
  linea: string;
  nucleo: number;
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
  // Por hora: su tabla, con una fila por hora.
  const porHora = page.getByRole('table', { name: `Puntualidad de la ${linea.linea} por hora` });
  await expect(porHora).toBeVisible();
  expect(await porHora.getByRole('row').count()).toBeGreaterThan(1);
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
  await page.getByRole('button', { name: 'Ver' }).click();
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
  // La página se desplaza dentro de .estad (el cuerpo no se mueve, como en el mapa): ni ella ni el documento se salen.
  const sobra = await page.evaluate(() => {
    const pagina = document.querySelector('.estad')!;
    return Math.max(document.documentElement.scrollWidth - window.innerWidth, pagina.scrollWidth - pagina.clientWidth);
  });
  expect(sobra).toBeLessThanOrEqual(0);
});
