import type { Cambios, Instantanea, Latido } from '../api/tipos';

/**
 * El directo por SSE (`GET /api/directo?nucleo=`) con reconexión.
 *
 * `EventSource` ya se reconecta solo cuando se corta un flujo que iba bien (la API cierra cada conexión a los 30 min).
 * Lo que no hace es reintentar cuando la respuesta es un error HTTP: con el **503 de «directo completo»** (o un 502 de
 * Cloudflare) pasa a `CLOSED` y se queda ahí. Entonces esta clase lo vuelve a abrir con **espera creciente** (2, 4, 8…
 * hasta 60 s, con un ±20 % al azar para que no vuelvan todos los clientes a la vez).
 *
 * Además, un **vigilante**: si en 45 s no llega nada (la API manda un latido cada 15 s), la conexión está colgada
 * aunque el navegador no lo sepa (un proxy que no la cierra): se cierra y se abre otra.
 */

export type EstadoConexion =
  | { tipo: 'conectando' }
  | { tipo: 'abierta' }
  | { tipo: 'reintentando'; intento: number; esperaMs: number };

export interface OyentesDirecto {
  instantanea(datos: Instantanea): void;
  cambios(datos: Cambios): void;
  latido(datos: Latido): void;
  estado(estado: EstadoConexion): void;
}

/** Lo que se usa de `EventSource` (para poder sustituirlo en las pruebas). */
export interface FuenteEventos {
  readonly readyState: number;
  onopen: ((e: Event) => void) | null;
  onerror: ((e: Event) => void) | null;
  addEventListener(tipo: string, oyente: (e: MessageEvent) => void): void;
  close(): void;
}

export interface Entorno {
  crearFuente(url: string): FuenteEventos;
  setTimeout(f: () => void, ms: number): unknown;
  clearTimeout(id: unknown): void;
  ahora(): number;
  azar(): number;
}

const CERRADA = 2;
export const ESPERA_BASE_MS = 2000;
export const ESPERA_MAXIMA_MS = 60_000;
export const SILENCIO_MAXIMO_MS = 45_000;
const REVISION_MS = 5000;

/** Espera antes del intento `n` (0, 1, 2…): 2 s, 4 s, 8 s… hasta 60 s, ±20 %. */
export function esperaReintento(intento: number, azar: number): number {
  const base = Math.min(ESPERA_MAXIMA_MS, ESPERA_BASE_MS * 2 ** Math.max(0, intento));
  return Math.round(base * (0.8 + 0.4 * azar));
}

const ENTORNO_NAVEGADOR: Entorno = {
  crearFuente: (url) => new EventSource(url),
  setTimeout: (f, ms) => window.setTimeout(f, ms),
  clearTimeout: (id) => window.clearTimeout(id as number),
  ahora: () => Date.now(),
  azar: () => Math.random(),
};

export class ConexionDirecto {
  private fuente: FuenteEventos | null = null;
  private reintento: unknown = null;
  private vigilante: unknown = null;
  private intento = 0;
  private ultimaActividad = 0;
  private cerrada = true;
  private readonly url: string;
  private readonly oyentes: OyentesDirecto;
  private readonly entorno: Entorno;

  constructor(url: string, oyentes: OyentesDirecto, entorno: Entorno = ENTORNO_NAVEGADOR) {
    this.url = url;
    this.oyentes = oyentes;
    this.entorno = entorno;
  }

  abrir(): void {
    this.cerrada = false;
    this.conectar();
  }

  cerrar(): void {
    this.cerrada = true;
    this.soltarFuente();
    if (this.reintento !== null) this.entorno.clearTimeout(this.reintento);
    if (this.vigilante !== null) this.entorno.clearTimeout(this.vigilante);
    this.reintento = null;
    this.vigilante = null;
  }

  private conectar(): void {
    this.reintento = null;
    this.soltarFuente();
    this.oyentes.estado({ tipo: 'conectando' });
    const fuente = this.entorno.crearFuente(this.url);
    this.fuente = fuente;
    this.ultimaActividad = this.entorno.ahora();

    fuente.onopen = () => {
      if (this.fuente !== fuente) return;
      this.ultimaActividad = this.entorno.ahora();
      this.oyentes.estado({ tipo: 'abierta' });
    };
    fuente.onerror = () => {
      if (this.fuente !== fuente) return;
      if (fuente.readyState === CERRADA) {
        // Un 503 (directo lleno) u otro error HTTP: EventSource no vuelve a intentarlo.
        this.programarReintento();
      } else {
        // Se cortó un flujo que iba bien: el navegador se reconecta solo (y llegará otra instantánea).
        this.oyentes.estado({ tipo: 'conectando' });
      }
    };
    this.escuchar<Instantanea>(fuente, 'instantanea', (datos) => {
      // Una instantánea demuestra que la conexión funciona: la próxima vez se empieza a esperar desde abajo.
      this.intento = 0;
      this.oyentes.estado({ tipo: 'abierta' });
      this.oyentes.instantanea(datos);
    });
    this.escuchar<Cambios>(fuente, 'cambios', (datos) => this.oyentes.cambios(datos));
    this.escuchar<Latido>(fuente, 'latido', (datos) => this.oyentes.latido(datos));
    this.vigilar();
  }

  private escuchar<T>(fuente: FuenteEventos, tipo: string, aplicar: (datos: T) => void): void {
    fuente.addEventListener(tipo, (evento) => {
      if (this.fuente !== fuente) return;
      this.ultimaActividad = this.entorno.ahora();
      let datos: T;
      try {
        datos = JSON.parse(String(evento.data)) as T;
      } catch {
        return; // Un evento mal formado no tumba el directo.
      }
      aplicar(datos);
    });
  }

  private programarReintento(): void {
    this.soltarFuente();
    if (this.cerrada || this.reintento !== null) return;
    const esperaMs = esperaReintento(this.intento, this.entorno.azar());
    this.intento += 1;
    this.oyentes.estado({ tipo: 'reintentando', intento: this.intento, esperaMs });
    this.reintento = this.entorno.setTimeout(() => {
      if (!this.cerrada) this.conectar();
    }, esperaMs);
  }

  private vigilar(): void {
    if (this.vigilante !== null) this.entorno.clearTimeout(this.vigilante);
    this.vigilante = this.entorno.setTimeout(() => {
      this.vigilante = null;
      if (this.cerrada) return;
      if (this.fuente !== null && this.entorno.ahora() - this.ultimaActividad > SILENCIO_MAXIMO_MS) {
        this.programarReintento();
      }
      if (this.fuente !== null) this.vigilar();
    }, REVISION_MS);
  }

  private soltarFuente(): void {
    if (this.fuente === null) return;
    const fuente = this.fuente;
    this.fuente = null;
    fuente.onopen = null;
    fuente.onerror = null;
    fuente.close();
  }
}
