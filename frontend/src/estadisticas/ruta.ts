/**
 * Las páginas de estadísticas tienen su propia ruta, para compartir un enlace a una línea:
 *
 * - `/estadisticas`: toda España (ranking de los núcleos);
 * - `/estadisticas/51`: un núcleo (ranking de sus líneas);
 * - `/estadisticas/51/R2N`: una línea.
 *
 * Con `?desde=2026-11-21&hasta=2026-12-18` (días de servicio); sin ellos, la API da las últimas 4 semanas.
 * Cloudflare Pages y `vite preview` sirven `index.html` en cualquier ruta (aplicación de una página).
 */

export const RAIZ = '/estadisticas';

export type RutaEstadisticas =
  | { tipo: 'todos'; desde: string | null; hasta: string | null }
  | { tipo: 'nucleo'; nucleo: number; desde: string | null; hasta: string | null }
  | { tipo: 'linea'; nucleo: number; linea: string; desde: string | null; hasta: string | null };

const FECHA = /^\d{4}-\d{2}-\d{2}$/;

/** `true` si la ruta es de las páginas de estadísticas (y no del mapa). */
export function esRutaEstadisticas(ruta: string): boolean {
  return ruta === RAIZ || ruta.startsWith(`${RAIZ}/`);
}

/** La página que pide la URL; `null` si no es una de estadísticas o está mal formada. */
export function leerRuta(ruta: string, busqueda: string): RutaEstadisticas | null {
  if (!esRutaEstadisticas(ruta)) return null;
  const p = new URLSearchParams(busqueda);
  const fecha = (n: string) => {
    const v = p.get(n);
    return v && FECHA.test(v) ? v : null;
  };
  const desde = fecha('desde');
  const hasta = fecha('hasta');
  const partes = ruta
    .slice(RAIZ.length)
    .split('/')
    .filter((x) => x !== '')
    .map((x) => decodeURIComponent(x));
  if (partes.length === 0) return { tipo: 'todos', desde, hasta };
  const nucleo = Number(partes[0]);
  if (!Number.isInteger(nucleo) || nucleo <= 0 || partes.length > 2) return null;
  if (partes.length === 1) return { tipo: 'nucleo', nucleo, desde, hasta };
  return { tipo: 'linea', nucleo, linea: partes[1], desde, hasta };
}

function periodo(desde?: string | null, hasta?: string | null): string {
  const p = new URLSearchParams();
  if (desde) p.set('desde', desde);
  if (hasta) p.set('hasta', hasta);
  const q = p.toString();
  return q ? `?${q}` : '';
}

export function urlTodos(desde?: string | null, hasta?: string | null): string {
  return `${RAIZ}${periodo(desde, hasta)}`;
}

export function urlNucleo(nucleo: number, desde?: string | null, hasta?: string | null): string {
  return `${RAIZ}/${nucleo}${periodo(desde, hasta)}`;
}

export function urlLinea(nucleo: number, linea: string, desde?: string | null, hasta?: string | null): string {
  return `${RAIZ}/${nucleo}/${encodeURIComponent(linea)}${periodo(desde, hasta)}`;
}

/** La ruta de la API que da los datos de la página. */
export function rutaApiDe(r: RutaEstadisticas): string {
  const q = periodo(r.desde, r.hasta);
  if (r.tipo === 'todos') return `/api/estadisticas/nucleos/todos${q}`;
  if (r.tipo === 'nucleo') return `/api/estadisticas/nucleos/${r.nucleo}${q}`;
  return `/api/estadisticas/lineas/${r.nucleo}/${encodeURIComponent(r.linea)}${q}`;
}
