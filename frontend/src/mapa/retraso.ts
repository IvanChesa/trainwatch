import type { TrenMapa } from '../api/tipos';

/** Las cuatro franjas de retraso del encargo, más «sin dato». */
export type CategoriaRetraso = 'a-tiempo' | 'leve' | 'medio' | 'grave' | 'sin-dato';

export interface FranjaRetraso {
  categoria: CategoriaRetraso;
  /** Texto de la leyenda: el color nunca va solo. */
  texto: string;
  /** Color del símbolo sobre el mapa oscuro (contraste ≥ 3:1 con el fondo, WCAG 1.4.11). */
  color: string;
}

export const FRANJAS: readonly FranjaRetraso[] = [
  { categoria: 'a-tiempo', texto: 'A tiempo', color: '#4ade80' },
  { categoria: 'leve', texto: '1-5 min', color: '#facc15' },
  { categoria: 'medio', texto: '6-15 min', color: '#fb923c' },
  { categoria: 'grave', texto: 'Más de 15 min', color: '#f87171' },
  { categoria: 'sin-dato', texto: 'Sin dato de retraso', color: '#a1a1aa' },
];

const POR_CATEGORIA = new Map(FRANJAS.map((f) => [f.categoria, f]));

export function categoriaRetraso(minutos: number | null | undefined): CategoriaRetraso {
  if (minutos === null || minutos === undefined || Number.isNaN(minutos)) return 'sin-dato';
  // Un adelanto (retraso negativo) cuenta como «a tiempo».
  if (minutos <= 0) return 'a-tiempo';
  if (minutos <= 5) return 'leve';
  if (minutos <= 15) return 'medio';
  return 'grave';
}

export function franja(categoria: CategoriaRetraso): FranjaRetraso {
  return POR_CATEGORIA.get(categoria)!;
}

export function colorRetraso(minutos: number | null | undefined): string {
  return franja(categoriaRetraso(minutos)).color;
}

/** «A tiempo», «5 min de retraso», «1 min de adelanto», «sin dato». */
export function textoRetraso(minutos: number | null | undefined): string {
  if (minutos === null || minutos === undefined) return 'sin dato de retraso';
  if (minutos === 0) return 'a tiempo';
  if (minutos < 0) return `${-minutos} min de adelanto`;
  return `${minutos} min de retraso`;
}

/** La etiqueta corta que acompaña al símbolo en el mapa: «+7». Vacía si va a tiempo o no se sabe. */
export function etiquetaRetraso(minutos: number | null | undefined): string {
  return minutos !== null && minutos !== undefined && minutos > 0 ? `+${minutos}` : '';
}

/** Los trenes que se pintan: en marcha y en origen (los terminados y cancelados solo salen en la lista). */
export function seVeEnElMapa(tren: TrenMapa): boolean {
  return tren.estado === 'EN_MARCHA' || tren.estado === 'EN_ORIGEN';
}

const HORA = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Madrid' });

/** «10:25» en hora de Madrid; `null` si no hay hora o no se entiende. */
export function hora(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const fecha = new Date(iso);
  return Number.isNaN(fecha.getTime()) ? null : HORA.format(fecha);
}

/** «Sale a las 10:25» (o «a las 10:27, 2 min tarde») para los trenes en origen. */
export function textoSalida(tren: Pick<TrenMapa, 'salida'>): string | null {
  const programada = hora(tren.salida?.programada);
  const estimada = hora(tren.salida?.estimada);
  if (!programada && !estimada) return null;
  if (programada && estimada && programada !== estimada) return `Sale a las ${estimada} (prevista a las ${programada})`;
  return `Sale a las ${estimada ?? programada}`;
}

/** Orden «natural» de líneas: R2 antes que R11, C1 antes que C10. */
export function compararLineas(a: string, b: string): number {
  return a.localeCompare(b, 'es', { numeric: true, sensitivity: 'base' });
}
