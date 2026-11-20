import { rutaApi } from './base';

/** Respuesta de `GET /api/estado` del backend (`EstadoController`). */
export interface EstadoBackend {
  aplicacion: string;
  version: string;
  /** Hora del servidor en ISO 8601 con desplazamiento (Europe/Madrid). */
  hora: string;
}

/**
 * Pide el estado al backend. En desarrollo, `/api` pasa por el proxy de Vite hacia `localhost:8080`; en producción,
 * va a `VITE_API_URL` (`base.ts`).
 * Lanza un error si la respuesta no es 2xx o no tiene la forma esperada.
 */
export async function obtenerEstado(signal?: AbortSignal): Promise<EstadoBackend> {
  const respuesta = await fetch(rutaApi('/api/estado'), { signal, headers: { Accept: 'application/json' } });
  if (!respuesta.ok) {
    throw new Error(`El backend respondió ${respuesta.status}`);
  }
  const datos: unknown = await respuesta.json();
  if (!esEstado(datos)) {
    throw new Error('Respuesta de /api/estado con un formato inesperado');
  }
  return datos;
}

function esEstado(datos: unknown): datos is EstadoBackend {
  if (typeof datos !== 'object' || datos === null) return false;
  const d = datos as Record<string, unknown>;
  return typeof d.aplicacion === 'string' && typeof d.version === 'string' && typeof d.hora === 'string';
}

const formatoHora = new Intl.DateTimeFormat('es-ES', {
  dateStyle: 'short',
  timeStyle: 'medium',
  timeZone: 'Europe/Madrid',
});

/** La hora del backend en hora de Madrid; si no se puede leer, el texto tal cual. */
export function formatearHora(iso: string): string {
  const fecha = new Date(iso);
  return Number.isNaN(fecha.getTime()) ? iso : formatoHora.format(fecha);
}
