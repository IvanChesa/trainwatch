import { formatearAntiguedad } from '../api/directo';
import type { TrenMapa } from '../api/tipos';
import { antiguedadAhora, type Directo } from '../directo/useDirecto';
import { compararLineas } from '../mapa/retraso';

/** Textos y orden de la interfaz (fuera de los componentes, para poder probarlos solos). */

const ORDEN_ESTADO: Record<string, number> = { EN_MARCHA: 0, EN_ORIGEN: 1, CANCELADO: 2, TERMINADO: 3 };
/** Uno para todas las comparaciones: `localeCompare` con idioma crea uno en cada llamada. */
const COLADOR = new Intl.Collator('es');

/** Con `nucleos` (toda España), primero por el nombre del núcleo. */
export function ordenarTrenes(trenes: readonly TrenMapa[], nucleos?: ReadonlyMap<number, string>): TrenMapa[] {
  const nombre = (t: TrenMapa) => nucleos?.get(t.nucleo) ?? String(t.nucleo);
  return [...trenes].sort(
    (a, b) =>
      (nucleos ? COLADOR.compare(nombre(a), nombre(b)) : 0) ||
      (ORDEN_ESTADO[a.estado] ?? 9) - (ORDEN_ESTADO[b.estado] ?? 9) ||
      compararLineas(a.linea ?? '', b.linea ?? '') ||
      (b.retrasoMin ?? -1) - (a.retrasoMin ?? -1) ||
      a.id.localeCompare(b.id),
  );
}

/** El título de la hoja: «R4 hacia Manresa». */
export function tituloTren(tren: Pick<TrenMapa, 'linea' | 'destino'> | undefined): string {
  if (!tren) return 'Tren';
  const linea = tren.linea ?? 'Tren';
  return tren.destino ? `${linea} hacia ${tren.destino}` : linea;
}

/** A partir de 2 minutos sin datos nuevos de Renfe (llegan cada ~20 s), se avisa: «datos de hace 3 min». */
export const ANTIGUEDAD_AVISO_S = 120;

export function textoEstadoDatos(directo: Directo, ahora: number): { texto: string; aviso: boolean } {
  const antiguedad = antiguedadAhora(directo, ahora);
  const viejo = antiguedad !== null && antiguedad >= ANTIGUEDAD_AVISO_S;
  const sufijo = antiguedad === null ? '' : ` · dato de Renfe ${formatearAntiguedad(antiguedad)}`;
  if (directo.conexion.tipo === 'reintentando') {
    const restante = directo.reintentoHasta !== null ? directo.reintentoHasta - ahora : directo.conexion.esperaMs;
    const segundos = Math.max(1, Math.ceil(restante / 1000));
    const base = `Sin conexión con el directo: se reintenta en ${segundos} s`;
    return { texto: antiguedad === null ? base : `${base}. Datos de ${formatearAntiguedad(antiguedad)}`, aviso: true };
  }
  if (!directo.cargado) return { texto: 'Conectando con el directo…', aviso: false };
  if (viejo) return { texto: `Datos de ${formatearAntiguedad(antiguedad!)}: el directo está parado`, aviso: true };
  if (directo.conexion.tipo === 'conectando') return { texto: `Reconectando…${sufijo}`, aviso: false };
  return { texto: `En directo${sufijo}`, aviso: false };
}

/** Lo que se anuncia a los lectores de pantalla: solo cuando cambia el estado, no cada segundo. */
export function anuncio(directo: Directo, ahora: number): string {
  const antiguedad = antiguedadAhora(directo, ahora);
  if (directo.conexion.tipo === 'reintentando') return 'Sin conexión con el directo.';
  if (!directo.cargado) return 'Conectando con el directo.';
  if (antiguedad !== null && antiguedad >= ANTIGUEDAD_AVISO_S) return 'Los datos en directo están parados.';
  if (directo.conexion.tipo === 'conectando') return 'Reconectando con el directo.';
  return 'En directo.';
}

