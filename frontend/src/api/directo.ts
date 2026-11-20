import { rutaApi } from './base';

/** Lo que usa la página provisional de `GET /api/directo/estado` (`ResumenDirecto` en el backend). */
export interface EstadoDirecto {
  /** Hora de la última muestra de Renfe, en ISO 8601; `null` si aún no ha llegado ninguna. */
  ultimoDato: string | null;
  /** Segundos desde el último dato; `null` si no hay. */
  antiguedadS: number | null;
  trenes: number;
  nucleos: { nucleo: number; nombre: string; estado: string }[];
}

/** Más de 5 minutos sin datos: la ingesta o Renfe tienen un problema (la vigilancia de la fase 7 usa lo mismo). */
export const ANTIGUEDAD_MAXIMA_S = 300;

export async function obtenerEstadoDirecto(signal?: AbortSignal): Promise<EstadoDirecto> {
  const respuesta = await fetch(rutaApi('/api/directo/estado'), { signal, headers: { Accept: 'application/json' } });
  if (!respuesta.ok) {
    throw new Error(`El backend respondió ${respuesta.status}`);
  }
  const datos: unknown = await respuesta.json();
  if (!esEstadoDirecto(datos)) {
    throw new Error('Respuesta de /api/directo/estado con un formato inesperado');
  }
  return datos;
}

function esEstadoDirecto(datos: unknown): datos is EstadoDirecto {
  if (typeof datos !== 'object' || datos === null) return false;
  const d = datos as Record<string, unknown>;
  return (
    (d.ultimoDato === null || typeof d.ultimoDato === 'string') &&
    (d.antiguedadS === null || typeof d.antiguedadS === 'number') &&
    typeof d.trenes === 'number' &&
    Array.isArray(d.nucleos)
  );
}

/** «hace 12 s», «hace 3 min», «hace 2 h». */
export function formatearAntiguedad(segundos: number): string {
  const s = Math.max(0, Math.round(segundos));
  if (s < 60) return `hace ${s} s`;
  if (s < 3600) return `hace ${Math.floor(s / 60)} min`;
  return `hace ${Math.floor(s / 3600)} h`;
}

/** El texto del estado de los datos para la página provisional. */
export function describirDirecto(estado: EstadoDirecto): string {
  if (estado.antiguedadS === null) return 'Aún no han llegado datos en directo de Renfe.';
  const enDirecto = estado.nucleos.filter((n) => n.estado === 'EN_DIRECTO').length;
  const base = `Último dato de Renfe ${formatearAntiguedad(estado.antiguedadS)}: ${estado.trenes} trenes en ${enDirecto} núcleos.`;
  return estado.antiguedadS > ANTIGUEDAD_MAXIMA_S ? `${base} Los datos no están al día.` : base;
}
