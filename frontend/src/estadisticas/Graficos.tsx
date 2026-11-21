import { escala } from './escala';
import { TRAMOS, numero, porcentaje } from './formato';
import type { Porcentajes } from './tipos';

/**
 * Gráficos en SVG propio, sin librería (ADR 0027): columnas de una sola serie y la barra del reparto por tramos. Son
 * decorativos para los lectores de pantalla (`aria-hidden`): al lado de cada uno va su tabla con los mismos datos, y
 * cada columna lleva su valor escrito encima, así que nada depende solo del color ni de la altura.
 */

export interface Columna {
  clave: string;
  /** Debajo de la columna: «08», «lun», «23 nov». */
  etiqueta: string;
  /** `null`: datos insuficientes (sin columna, con «—»). */
  valor: number | null;
}

/** Columnas de una serie: la media de minutos por hora, por día o por semana. */
export function Columnas({ columnas, unidad = 'min' }: { columnas: readonly Columna[]; unidad?: string }) {
  const ancho = 36;
  const hueco = 8;
  const alto = 160;
  const izquierda = 34;
  const arriba = 18;
  const abajo = 22;
  const valores = columnas.map((c) => c.valor).filter((v): v is number => v !== null);
  const { tope, paso } = escala(Math.max(0, ...valores));
  const y = (v: number) => arriba + alto - (Math.max(0, v) / tope) * alto;
  const total = izquierda + columnas.length * (ancho + hueco);
  const lineas: number[] = [];
  for (let v = 0; v <= tope + 1e-9; v += paso) lineas.push(Math.round(v * 10) / 10);
  return (
    <svg
      className="grafico"
      viewBox={`0 0 ${total} ${arriba + alto + abajo}`}
      aria-hidden="true"
      focusable="false"
      preserveAspectRatio="xMinYMid meet"
    >
      {lineas.map((v) => (
        <g key={v}>
          <line className="grafico__rejilla" x1={izquierda} x2={total} y1={y(v)} y2={y(v)} />
          <text className="grafico__eje" x={izquierda - 6} y={y(v) + 4} textAnchor="end">
            {numero(v)}
          </text>
        </g>
      ))}
      <text className="grafico__eje" x={0} y={10}>
        {unidad}
      </text>
      {columnas.map((c, i) => {
        const x = izquierda + i * (ancho + hueco) + hueco / 2;
        return (
          <g key={c.clave}>
            {c.valor !== null && c.valor > 0 && (
              <rect className="grafico__columna" x={x} y={y(c.valor)} width={ancho} height={arriba + alto - y(c.valor)} />
            )}
            <text
              className={c.valor === null ? 'grafico__valor grafico__valor--sin' : 'grafico__valor'}
              x={x + ancho / 2}
              y={(c.valor === null ? y(0) : y(c.valor)) - 5}
              textAnchor="middle"
            >
              {c.valor === null ? '—' : numero(c.valor)}
            </text>
            <text className="grafico__eje" x={x + ancho / 2} y={arriba + alto + 16} textAnchor="middle">
              {c.etiqueta}
            </text>
          </g>
        );
      })}
      <line className="grafico__base" x1={izquierda} x2={total} y1={y(0)} y2={y(0)} />
    </svg>
  );
}

/** El reparto de las paradas por tramos de retraso: una barra al 100 %, con su leyenda, que es también su tabla. */
export function Reparto({ porcentajes, titulo }: { porcentajes: Porcentajes; titulo: string }) {
  return (
    <figure className="reparto">
      <div className="reparto__barra" aria-hidden="true">
        {TRAMOS.map((t) => {
          const v = porcentajes[t.clave] ?? 0;
          return v > 0 ? (
            <span key={t.clave} className="reparto__trozo" style={{ width: `${v}%`, background: t.color }} />
          ) : null;
        })}
      </div>
      <table className="tabla tabla--leyenda">
        <caption className="solo-lector">{titulo}</caption>
        <thead className="solo-lector">
          <tr>
            <th scope="col">Retraso al llegar</th>
            <th scope="col">Paradas</th>
          </tr>
        </thead>
        <tbody>
          {TRAMOS.map((t) => (
            <tr key={t.clave}>
              <th scope="row">
                <span className="muestra-color" style={{ background: t.color }} aria-hidden="true" />
                {t.texto}
              </th>
              <td className="cifra">{porcentaje(porcentajes[t.clave])}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/** Una barra dentro de una celda de tabla (la cifra va escrita al lado): el % de puntuales en un ranking. */
export function BarraCelda({ valor, maximo = 100 }: { valor: number | null; maximo?: number }) {
  if (valor === null) return null;
  return (
    <span className="barra-celda" aria-hidden="true">
      <span style={{ width: `${Math.max(0, Math.min(100, (valor / maximo) * 100))}%` }} />
    </span>
  );
}
