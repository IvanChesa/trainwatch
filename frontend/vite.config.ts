import { readFileSync } from 'node:fs';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vitest/config';

/**
 * Lee `public/_headers` (las cabeceras que pone Cloudflare Pages en producción) y las aplica también en `vite preview`.
 * Así la CSP de verdad se prueba en local y en las pruebas e2e, antes de publicar. Solo entiende la regla `/*`.
 */
function cabecerasDePages(): Plugin {
  const cabeceras: [string, string][] = [];
  let dentro = false;
  for (const linea of readFileSync(new URL('./public/_headers', import.meta.url), 'utf8').split(/\r?\n/)) {
    if (linea.trim() === '' || linea.trim().startsWith('#')) continue;
    if (!/^\s/.test(linea)) {
      dentro = linea.trim() === '/*';
      continue;
    }
    const separador = linea.indexOf(':');
    if (dentro && separador > 0) cabeceras.push([linea.slice(0, separador).trim(), linea.slice(separador + 1).trim()]);
  }
  return {
    name: 'trainwatch-cabeceras-de-pages',
    configurePreviewServer(servidor) {
      servidor.middlewares.use((_peticion, respuesta, siguiente) => {
        // HSTS no tiene sentido en http://localhost.
        for (const [nombre, valor] of cabeceras) if (nombre !== 'Strict-Transport-Security') respuesta.setHeader(nombre, valor);
        siguiente();
      });
    },
  };
}

// En desarrollo y en `vite preview`, /api va al backend de Spring Boot (o, en las pruebas e2e, a la API simulada).
const destinoApi = process.env.TW_API ?? 'http://localhost:8080';

// https://vite.dev/config/ · https://vitest.dev/config/
export default defineConfig({
  plugins: [react(), cabecerasDePages()],
  server: {
    port: 5173,
    strictPort: true,
    // El navegador ve un solo origen y no hace falta CORS.
    proxy: {
      '/api': destinoApi,
    },
  },
  build: {
    // MapLibre (~1 MB sin comprimir, ~290 KB con gzip) va en su propio trozo y se carga después de la interfaz.
    chunkSizeWarningLimit: 1200,
  },
  preview: {
    proxy: {
      '/api': destinoApi,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
