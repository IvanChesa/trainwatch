import { defineConfig, devices } from '@playwright/test';

/**
 * Pruebas e2e de la web contra la build de producción (`vite preview`, con las cabeceras y la CSP de `public/_headers`)
 * y la API simulada (`e2e/api-simulada.mjs`, con los ejemplos de `docs/api/`). Los mapas de OpenFreeMap se sustituyen
 * en cada prueba por un estilo mínimo, para no depender de la red.
 *
 *   npx playwright test
 */
const PUERTO_API = 8091;
const PUERTO_WEB = 4173;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PUERTO_WEB}`,
    trace: 'retain-on-failure',
    // WebGL sin tarjeta gráfica (SwiftShader), como en una máquina de CI.
    launchOptions: { args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] },
  },
  projects: [
    { name: 'escritorio', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'movil', use: { ...devices['Pixel 7'] } },
  ],
  webServer: [
    {
      command: `node e2e/api-simulada.mjs ${PUERTO_API}`,
      url: `http://localhost:${PUERTO_API}/api/nucleos`,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `npx vite build && npx vite preview --port ${PUERTO_WEB} --strictPort`,
      url: `http://localhost:${PUERTO_WEB}`,
      env: { TW_API: `http://localhost:${PUERTO_API}`, VITE_API_URL: '' },
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
