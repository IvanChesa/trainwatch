import { FRANJAS } from '../mapa/retraso';
import type { Cifras, Cobertura, Periodo, Porcentajes } from './tipos';

/** Textos y números de las páginas de estadísticas, fuera de los componentes para probarlos solos. */

/** Espacio de no separación: «32,7 %» no se parte entre dos líneas. */
const NBSP = '\u00a0';
const NUMERO = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 1 });
const ENTERO = new Intl.NumberFormat('es-ES');
const FECHA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
const FECHA_CORTA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short', timeZone: 'UTC' });

/** «2,4» (un decimal, coma española); «—» si no hay dato. */
export function numero(x: number | null | undefined): string {
  return x === null || x === undefined ? '—' : NUMERO.format(x);
}

/** «1.843». */
export function entero(x: number | null | undefined): string {
  return x === null || x === undefined ? '—' : ENTERO.format(x);
}

/** «2,4 min»; «—». */
export function minutos(x: number | null | undefined): string {
  return x === null || x === undefined ? '—' : `${NUMERO.format(x)}${NBSP}min`;
}

/** «78,4 %»; «—». */
export function porcentaje(x: number | null | undefined): string {
  return x === null || x === undefined ? '—' : `${NUMERO.format(x)}${NBSP}%`;
}

/** «21 de noviembre de 2026», de una fecha ISO (día de servicio, sin hora). */
export function fecha(iso: string | null | undefined): string {
  if (!iso) return '—';
  return FECHA.format(new Date(`${iso}T00:00:00Z`));
}

/** «21 nov». */
export function fechaCorta(iso: string): string {
  return FECHA_CORTA.format(new Date(`${iso}T00:00:00Z`));
}

/** «Del 21 de noviembre al 18 de diciembre de 2026 (28 días con datos)». */
export function textoPeriodo(p: Periodo): string {
  const dias = `${entero(p.diasConDatos)} ${p.diasConDatos === 1 ? 'día' : 'días'} con datos`;
  if (p.desde === p.hasta) return `El ${fecha(p.desde)} (${dias})`;
  return `Del ${fecha(p.desde)} al ${fecha(p.hasta)} (${dias})`;
}

/** «el 89,2 % de los trenes programados (y el 81,6 % de sus paradas)»; `null` si no se sabe. */
export function textoCobertura(c: Cobertura | null | undefined): string | null {
  if (!c || c.porcentaje === null) return null;
  const paradas = c.porcentajeParadas === null ? '' : ` y el ${porcentaje(c.porcentajeParadas)} de sus paradas`;
  return `${porcentaje(c.porcentaje)} de los trenes programados${paradas}`;
}

/** Por debajo de este %, la cobertura se resalta: la cifra es solo de una parte de los trenes. */
export const COBERTURA_BAJA = 60;

/** «Datos insuficientes: 12 viajes (hacen falta 30)». */
export function textoInsuficiente(c: Pick<Cifras, 'viajes'>, minimo = 30): string {
  return `Datos insuficientes: ${entero(c.viajes)} ${c.viajes === 1 ? 'viaje' : 'viajes'} (hacen falta ${minimo})`;
}

/**
 * Los cuatro tramos de las estadísticas (ADR 0025) con el color de la franja del mapa más cercana. No coinciden del
 * todo con los del mapa (allí 1-2 min ya es amarillo; aquí cuenta como puntual) y por eso cada uno lleva su texto.
 */
export const TRAMOS: readonly { clave: keyof Porcentajes; texto: string; corto: string; color: string }[] = [
  { clave: 'menosDe3', texto: 'Menos de 3 min', corto: '< 3', color: FRANJAS[0].color },
  { clave: 'de3a5', texto: 'De 3 a 5 min', corto: '3-5', color: FRANJAS[1].color },
  { clave: 'de6a15', texto: 'De 6 a 15 min', corto: '6-15', color: FRANJAS[2].color },
  { clave: 'de16OMas', texto: '16 min o más', corto: '≥ 16', color: FRANJAS[3].color },
];

/** La frase que resume una cifra: «Llega con 2,4 min de media; el 78,4 % de las paradas, con menos de 3 min». */
export function frase(c: Cifras | null | undefined): string | null {
  if (!c || !c.suficiente || c.mediaMinutos === null || !c.porcentajes) return null;
  return `${minutos(c.mediaMinutos)} de retraso medio por parada; el ${porcentaje(c.porcentajes.menosDe3)} de las paradas, con menos de 3 min`;
}

/** «08:00» para una franja horaria. */
export function franja(hora: number): string {
  return `${String(hora).padStart(2, '0')}:00`;
}
