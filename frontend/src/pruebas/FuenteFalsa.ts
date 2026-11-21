import type { FuenteEventos } from '../directo/ConexionDirecto';

/** Un `EventSource` de mentira para las pruebas: se le «envían» eventos y errores a mano. */
export class FuenteFalsa implements FuenteEventos {
  static creadas: FuenteFalsa[] = [];
  static ultima(): FuenteFalsa {
    const f = FuenteFalsa.creadas.at(-1);
    if (!f) throw new Error('No se ha creado ninguna fuente');
    return f;
  }

  readonly url: string;
  readyState = 0;
  cerrada = false;
  onopen: ((e: Event) => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  private readonly oyentes = new Map<string, ((e: MessageEvent) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FuenteFalsa.creadas.push(this);
  }

  addEventListener(tipo: string, oyente: (e: MessageEvent) => void): void {
    this.oyentes.set(tipo, [...(this.oyentes.get(tipo) ?? []), oyente]);
  }

  removeEventListener(): void {}

  close(): void {
    this.cerrada = true;
    this.readyState = 2;
  }

  abrir(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  enviar(tipo: string, datos: unknown): void {
    const evento = new MessageEvent(tipo, { data: typeof datos === 'string' ? datos : JSON.stringify(datos) });
    for (const o of this.oyentes.get(tipo) ?? []) o(evento);
  }

  /** Un error: `cerrada` como tras un 503 (EventSource no reintenta); si no, como un corte (el navegador reintenta). */
  fallar(cerrada: boolean): void {
    this.readyState = cerrada ? 2 : 0;
    this.onerror?.(new Event('error'));
  }
}
