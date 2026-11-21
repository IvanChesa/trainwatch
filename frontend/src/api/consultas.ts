import { rutaApi } from './base';
import type { Aviso, FichaEstacion, FichaTren, Linea, Nucleo, TrenMapa } from './tipos';

/** Error de la API con su código HTTP y el `detail` del `ProblemDetail` (RFC 9457), si lo trae. */
export class ErrorApi extends Error {
  readonly status: number;

  constructor(status: number, mensaje: string) {
    super(mensaje);
    this.name = 'ErrorApi';
    this.status = status;
  }
}

/** `GET` de la API con JSON. Lanza `ErrorApi` si no es 2xx (con el texto del `ProblemDetail` si lo hay). */
export async function pedirJson<T>(ruta: string, signal?: AbortSignal): Promise<T> {
  const respuesta = await fetch(rutaApi(ruta), { signal, headers: { Accept: 'application/json' } });
  if (!respuesta.ok) {
    let detalle = `La API respondió ${respuesta.status}`;
    try {
      const problema = (await respuesta.json()) as { detail?: unknown; title?: unknown };
      if (typeof problema.detail === 'string') detalle = problema.detail;
      else if (typeof problema.title === 'string') detalle = problema.title;
    } catch {
      // Sin cuerpo JSON: se queda el texto genérico.
    }
    throw new ErrorApi(respuesta.status, detalle);
  }
  return (await respuesta.json()) as T;
}

export const consultas = {
  nucleos: (signal?: AbortSignal) => pedirJson<Nucleo[]>('/api/nucleos', signal),
  trenesDeNucleo: (nucleo: number, signal?: AbortSignal) =>
    pedirJson<TrenMapa[]>(`/api/nucleos/${nucleo}/trenes`, signal),
  tren: (id: string, signal?: AbortSignal) => pedirJson<FichaTren>(`/api/trenes/${encodeURIComponent(id)}`, signal),
  estacion: (id: string, signal?: AbortSignal) =>
    pedirJson<FichaEstacion>(`/api/estaciones/${encodeURIComponent(id)}`, signal),
  linea: (nucleo: number, linea: string, signal?: AbortSignal) =>
    pedirJson<Linea>(`/api/lineas/${nucleo}/${encodeURIComponent(linea)}`, signal),
  /** Los de un núcleo o, con `'todos'`, los de todos. */
  avisos: (nucleo: number | 'todos', signal?: AbortSignal) =>
    pedirJson<Aviso[]>(nucleo === 'todos' ? '/api/avisos' : `/api/avisos?nucleo=${nucleo}`, signal),
};
