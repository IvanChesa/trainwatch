import { expect, test, type Page } from '@playwright/test';

const TREN = '5123V15033R15';

/** Estilo mínimo en lugar del de OpenFreeMap: fondo y letras (vacías), sin teselas. */
const ESTILO = {
  version: 8,
  name: 'prueba',
  glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
  sources: {},
  layers: [{ id: 'fondo', type: 'background', paint: { 'background-color': '#0c0c0c' } }],
};

interface Vigilancia {
  errores: string[];
}

/** Sustituye los mapas de OpenFreeMap y apunta los errores de la consola y las violaciones de la CSP. */
async function preparar(page: Page): Promise<Vigilancia> {
  const vigilancia: Vigilancia = { errores: [] };
  await page.route('https://tiles.openfreemap.org/styles/**', (r) => r.fulfill({ json: ESTILO }));
  await page.route('https://tiles.openfreemap.org/fonts/**', (r) =>
    r.fulfill({ body: Buffer.alloc(0), contentType: 'application/x-protobuf' }),
  );
  page.on('console', (m) => {
    if (m.type() === 'error') vigilancia.errores.push(m.text());
  });
  page.on('pageerror', (e) => vigilancia.errores.push(e.message));
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) =>
      console.error(`CSP: ${e.violatedDirective} ${e.blockedURI}`),
    );
  });
  return vigilancia;
}

/** Posición del tren en la fuente GeoJSON del mapa (la que se pinta en este fotograma). */
async function posicion(page: Page, id: string): Promise<[number, number] | null> {
  return page.evaluate((id) => {
    const mapa = (window as unknown as { __twMapa?: { getSource(id: string): unknown } }).__twMapa;
    const fuente = mapa?.getSource('tw-trenes') as
      | { serialize(): { data: { features: { geometry: { coordinates: [number, number] }; properties: { id: string } }[] } } }
      | undefined;
    const f = fuente?.serialize().data.features.find((x) => x.properties.id === id);
    return f ? f.geometry.coordinates : null;
  }, id);
}

test('el mapa carga con los trenes en directo, sin errores ni violaciones de la CSP', async ({ page }) => {
  const vigilancia = await preparar(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('TrainWatch');
  await expect(page.getByText('Datos: Renfe (CC BY 4.0)')).toBeVisible();
  await expect(page.getByText('no oficial').first()).toBeVisible();
  await expect(page.locator('.mapa[data-listo="si"]')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('canvas.maplibregl-canvas')).toHaveAttribute('aria-label', /2 trenes en marcha y 1 en origen/);
  await expect.poll(() => posicion(page, TREN)).not.toBeNull();
  await expect(page.getByText(/En directo · dato de Renfe/)).toBeVisible();
  expect(vigilancia.errores).toEqual([]);
});

test('los trenes se mueven de forma continua entre actualizaciones', async ({ page }) => {
  await preparar(page);
  await page.goto('/');
  await expect.poll(() => posicion(page, TREN), { timeout: 15_000 }).not.toBeNull();
  // Se toman varias posiciones en 1,5 s: el tren avanza hacia el suroeste en pasos pequeños, no a saltos de 1 s.
  const muestras: number[] = [];
  for (let i = 0; i < 8; i++) {
    muestras.push((await posicion(page, TREN))![0]);
    await page.waitForTimeout(200);
  }
  expect(muestras.at(-1)!).toBeLessThan(muestras[0]);
  const distintas = new Set(muestras.map((x) => x.toFixed(6))).size;
  expect(distintas).toBeGreaterThanOrEqual(5);
});

test('con movimiento reducido, los trenes saltan sin animación', async ({ page }) => {
  await preparar(page);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect.poll(() => posicion(page, TREN), { timeout: 15_000 }).not.toBeNull();
  const muestras: number[] = [];
  for (let i = 0; i < 8; i++) {
    muestras.push((await posicion(page, TREN))![0]);
    await page.waitForTimeout(200);
  }
  // Un cambio por actualización (cada segundo), no por fotograma.
  expect(new Set(muestras.map((x) => x.toFixed(6))).size).toBeLessThanOrEqual(3);
});

test('al pulsar un tren en el mapa se abre su ficha, y de ahí la de una estación', async ({ page }) => {
  await preparar(page);
  await page.goto('/');
  // El R15 en marcha, que está solo en su zona del mapa (el R13 y el R15 en origen están a pocos píxeles).
  await expect.poll(() => posicion(page, TREN), { timeout: 15_000 }).not.toBeNull();
  // Que haya terminado el encuadre animado del núcleo.
  await page.waitForFunction(() => !(window as unknown as { __twMapa: { isMoving(): boolean } }).__twMapa.isMoving());
  await page.waitForTimeout(300);
  const punto = await page.evaluate((id) => {
    const mapa = (window as unknown as { __twMapa: { project(c: [number, number]): { x: number; y: number }; getSource(id: string): { serialize(): { data: { features: { geometry: { coordinates: [number, number] }; properties: { id: string } }[] } } } } }).__twMapa;
    const f = mapa.getSource('tw-trenes').serialize().data.features.find((x) => x.properties.id === id)!;
    return mapa.project(f.geometry.coordinates);
  }, TREN);
  const caja = await page.locator('canvas.maplibregl-canvas').boundingBox();
  await page.mouse.click(caja!.x + punto.x, caja!.y + punto.y);
  const ficha = page.getByRole('complementary', { name: 'R15 hacia Reus' });
  await expect(ficha).toBeVisible();
  await expect(ficha.getByRole('heading', { name: 'Paradas' })).toBeVisible();
  await ficha.getByRole('button', { name: 'Barcelona-Sants' }).click();
  const estacion = page.getByRole('complementary', { name: 'Barcelona-Sants' });
  await expect(estacion.getByRole('table')).toContainText('R17 hacia Barcelona Estació de França');
  await estacion.getByRole('button', { name: /^Cerrar/ }).click();
  await expect(page.getByRole('complementary')).toHaveCount(0);
});

test('la vista de lista funciona entera con el teclado', async ({ page }) => {
  await preparar(page);
  await page.goto('/');
  await expect(page.getByText(/En directo · dato/)).toBeVisible({ timeout: 15_000 });
  // El primer Tab lleva al enlace para saltar a la lista.
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Ir a la lista de trenes' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Lista' })).toHaveAttribute('aria-pressed', 'true');
  const lista = page.getByRole('region', { name: /^Trenes de Rodalies/ });
  await expect(lista.getByRole('button')).toHaveCount(3);
  await page.keyboard.press('Tab');
  await expect(lista.getByRole('button').first()).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(lista.getByRole('button').nth(1)).toBeFocused();
  await expect(lista.getByRole('button').nth(1)).toContainText('R15');
  await page.keyboard.press('Enter');
  const ficha = page.getByRole('complementary', { name: 'R15 hacia Reus' });
  await expect(ficha.getByRole('heading', { name: 'R15 hacia Reus' })).toBeFocused();
  await expect(ficha).toContainText('Torredembarra');
  await page.keyboard.press('Escape');
  await expect(ficha).toHaveCount(0);
  await expect(lista.getByRole('button').nth(1)).toBeFocused();
  // El foco se ve (WCAG 2.4.7).
  const contorno = await lista.getByRole('button').nth(1).evaluate((b) => getComputedStyle(b).outlineStyle);
  expect(contorno).toBe('solid');
});

test('filtra por línea y destaca la RL4 en Rodalies', async ({ page }) => {
  await preparar(page);
  await page.goto('/?vista=lista');
  const lista = page.getByRole('region', { name: /^Trenes de Rodalies/ });
  await expect(lista.getByRole('button')).toHaveCount(3, { timeout: 15_000 });
  await page.getByRole('combobox', { name: 'Línea' }).selectOption('R15');
  await expect(lista.getByRole('button')).toHaveCount(2);
  await expect(page).toHaveURL(/linea=R15/);
  await page.getByRole('button', { name: /RL4 Lleida/ }).click();
  await expect(page.getByRole('combobox', { name: 'Línea' })).toHaveValue('RL4');
  await expect(lista).toContainText('No hay trenes');
});

test('si el directo está lleno (503), vuelve a intentarlo solo y se recupera', async ({ page, request }) => {
  await preparar(page);
  await request.get('http://localhost:8091/control/rechazar?n=1');
  await page.goto('/');
  await expect(page.getByText(/Sin conexión con el directo: se reintenta/)).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(/En directo · dato/)).toBeVisible({ timeout: 10_000 });
});
