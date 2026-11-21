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

/** «15 min», o «60 min o más» en el tope del histograma (la mediana y el p90 no pasan de ahí, ADR 0026). */
export function minutosTope(x: number | null | undefined): string {
  return x !== null && x !== undefined && x >= 60 ? `60${NBSP}min o más` : minutos(x);
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
export const TRAMOS: readonly { clave: Exclude<keyof Porcentajes, 'hasta5'>; texto: string; corto: string; color: string }[] = [
  { clave: 'menosDe3', texto: 'Menos de 3 min', corto: '< 3', color: FRANJAS[0].color },
  { clave: 'de3a5', texto: 'De 3 a 5 min', corto: '3-5', color: FRANJAS[1].color },
  { clave: 'de6a15', texto: 'De 6 a 15 min', corto: '6-15', color: FRANJAS[2].color },
  { clave: 'de16OMas', texto: '16 min o más', corto: '≥ 16', color: FRANJAS[3].color },
];

/**
 * La frase que resume una cifra: «2,4 min de retraso medio por parada; el 78,4 % de las paradas, con menos de 3 min, y
 * el 90,5 %, con 5 o menos».
 */
export function frase(c: Cifras | null | undefined): string | null {
  if (!c || !c.suficiente || c.mediaMinutos === null || !c.porcentajes) return null;
  const cinco = c.porcentajes.hasta5 === null ? '' : `, y el ${porcentaje(c.porcentajes.hasta5)}, con 5 o menos`;
  return `${minutos(c.mediaMinutos)} de retraso medio por parada; el ${porcentaje(c.porcentajes.menosDe3)} de las paradas, con menos de 3 min${cinco}`;
}

/** Los cancelados, siempre como lo que son: una cota inferior. */
export const NOTA_CANCELADOS =
  'Son una cota inferior: solo los trenes que Renfe marca como cancelados (CANCELED) en sus posiciones; uno que solo se anuncia en los retrasos no se cuenta, y un tren que no aparece no cuenta como cancelado.';

/**
 * Diferencia en puntos por debajo de la cual dos puestos de un ranking no se distinguen: con ~30 viajes, el error típico
 * de un porcentaje ronda los 7 puntos (ADR 0025), así que el titular no corona a nadie por menos.
 */
export const MARGEN_RANKING = 5;

/**
 * El titular de un ranking: quién va primero y quién último, si la diferencia con el siguiente lo permite; si no, que
 * van igualadas. Y la cobertura, si la del primero es baja.
 */
export function hallazgoRanking(
  filas: readonly { nombre: string; cifras: Cifras; cobertura?: Cobertura | null }[],
): string | null {
  const con = filas.filter((f) => f.cifras.suficiente && f.cifras.porcentajes?.menosDe3 != null);
  if (con.length === 0) return null;
  const p = (f: { cifras: Cifras }) => f.cifras.porcentajes!.menosDe3!;
  const primero = con[0];
  const cob = primero.cobertura?.porcentaje;
  const aviso = cob != null && cob < COBERTURA_BAJA ? ` (solo se ve el ${porcentaje(cob)} de sus trenes)` : '';
  if (con.length === 1) return `${primero.nombre}: el ${porcentaje(p(primero))} de las paradas, con menos de 3 min${aviso}.`;
  const ultimo = con[con.length - 1];
  if (p(primero) - p(ultimo) < MARGEN_RANKING) {
    return `Todas muy igualadas: entre el ${porcentaje(p(ultimo))} y el ${porcentaje(p(primero))} de las paradas con menos de 3 min, una diferencia que estas muestras no permiten separar.`;
  }
  const empatadas = con.filter((f) => p(primero) - p(f) < MARGEN_RANKING);
  const cabeza =
    empatadas.length > 1
      ? `En cabeza, igualadas: ${empatadas.map((f) => `${f.nombre} (${porcentaje(p(f))})`).join(', ')}`
      : `La más puntual, ${primero.nombre} (el ${porcentaje(p(primero))} de las paradas con menos de 3 min${aviso})`;
  return `${cabeza}; la que menos, ${ultimo.nombre} (${porcentaje(p(ultimo))}).`;
}

/** Las horas en el orden del día de servicio: de las 4 a las 3 (las de pasada la medianoche, al final). */
export function ordenHora(hora: number): number {
  return (hora + 24 - 4) % 24;
}

/** «08:00» para una franja horaria. */
export function franja(hora: number): string {
  return `${String(hora).padStart(2, '0')}:00`;
}
