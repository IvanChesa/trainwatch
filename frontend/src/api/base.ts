/**
 * Dónde está la API. En desarrollo, vacío: `/api` pasa por el proxy de Vite. En producción (Cloudflare Pages), la CI
 * construye con `VITE_API_URL=https://trainwatch-api.ivanchesa.com` (fase 7).
 */
export const URL_API: string = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '');

export function rutaApi(ruta: string): string {
  return `${URL_API}${ruta}`;
}
