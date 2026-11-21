import { useId, useState, type ReactNode } from 'react';
import {
  COBERTURA_BAJA,
  entero,
  fecha,
  minutos,
  minutosTope,
  porcentaje,
  textoCobertura,
  textoInsuficiente,
  textoPeriodo,
} from './formato';
import type { Cifras, Cobertura, Muestra, Periodo, Publicacion } from './tipos';

/** Las piezas que comparten las tres páginas de estadísticas. */

/**
 * Lo que va siempre a la vista con una cifra (encargo de la fase 6, punto 4): el periodo, «solo trenes vistos en
 * directo», la cobertura (del núcleo y, en la página de una línea, también la suya) y lo que se ha quitado. La cita de
 * Renfe y el aviso de no oficial van en la cabecera y el pie.
 */
export function Contexto({
  periodo,
  coberturas,
  muestra,
}: {
  periodo: Periodo;
  coberturas: { etiqueta: string; cobertura: Cobertura | null }[];
  muestra: Muestra | null;
}) {
  return (
    <dl className="contexto">
      <div>
        <dt>Periodo</dt>
        <dd>{textoPeriodo(periodo)}</dd>
      </div>
      <div>
        <dt>Qué cuenta</dt>
        <dd>
          Solo trenes vistos en directo, en la hora programada de cada parada (hora de Madrid)
          {muestra && muestra.paradasInferidasQuitadas > 0
            ? `; se han quitado ${entero(muestra.paradasInferidasQuitadas)} paradas que no se vieron`
            : ''}
          .
        </dd>
      </div>
      {coberturas.map(({ etiqueta, cobertura }) => {
        const baja = cobertura?.porcentaje != null && cobertura.porcentaje < COBERTURA_BAJA;
        return (
          <div key={etiqueta} className={baja ? 'contexto--aviso' : undefined}>
            <dt>{etiqueta}</dt>
            <dd>
              {textoCobertura(cobertura) ?? 'sin datos del horario'}
              {baja && '. Con tan poca cobertura, la cifra es solo de los trenes que se ven'}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

/** Antes de la fecha de publicación: no hay cifras, solo cuándo las habrá. */
export function SinPublicar({ publicacion }: { publicacion: Publicacion }) {
  return (
    <section className="sin-publicar" aria-labelledby="sin-publicar-titulo">
      <h2 id="sin-publicar-titulo">Aún no hay cifras</h2>
      <p>
        {publicacion.recogiendoDesde
          ? `Recogiendo datos desde el ${fecha(publicacion.recogiendoDesde)}`
          : 'Aún no se han recogido datos'}
        {publicacion.cifrasDesde ? `; cifras a partir del ${fecha(publicacion.cifrasDesde)}.` : '.'}
      </p>
      <p className="nota">
        Una media de pocos días bailaría de una semana a otra: por eso se espera a tener varias semanas de datos.
      </p>
    </section>
  );
}

/** Las cifras principales en una sola franja: la media primero y más grande, que es la respuesta. */
export function Cifrado({ cifras, minimo }: { cifras: Cifras; minimo: number }) {
  if (!cifras.suficiente || !cifras.porcentajes) {
    return <p className="insuficiente">{textoInsuficiente(cifras, minimo)}.</p>;
  }
  return (
    <dl className="cifrado">
      <div className="cifrado__lider">
        <dt>Retraso medio por parada</dt>
        <dd>{minutos(cifras.mediaMinutos)}</dd>
      </div>
      <div>
        <dt>Paradas con menos de 3 min</dt>
        <dd>{porcentaje(cifras.porcentajes.menosDe3)}</dd>
      </div>
      <div>
        <dt>Mediana</dt>
        <dd>{minutosTope(cifras.medianaMinutos)}</dd>
      </div>
      <div>
        <dt>9 de cada 10 paradas, hasta</dt>
        <dd>{minutosTope(cifras.p90Minutos)}</dd>
      </div>
      <div>
        <dt>Muestra</dt>
        <dd>
          {entero(cifras.viajes)} viajes, {entero(cifras.paradas)} paradas
        </dd>
      </div>
    </dl>
  );
}

/**
 * Una sección con su título y, a la izquierda en pantallas anchas, el gráfico; la tabla, siempre visible. Con
 * `ancho`, el gráfico va encima de la tabla a todo lo ancho (24 horas no caben en una columna). Si el gráfico o la tabla
 * no caben, se desplazan dentro de su caja, que se puede enfocar y tiene nombre.
 */
export function Bloque({
  id,
  titulo,
  explicacion,
  grafico,
  ancho = false,
  children,
}: {
  id: string;
  titulo: string;
  explicacion?: ReactNode;
  grafico?: ReactNode;
  ancho?: boolean;
  children: ReactNode;
}) {
  const clase = grafico
    ? `bloque__cuerpo bloque__cuerpo--con-grafico${ancho ? ' bloque__cuerpo--ancho' : ''}`
    : 'bloque__cuerpo';
  return (
    <section className="bloque" aria-labelledby={`${id}-titulo`}>
      <h2 id={`${id}-titulo`}>{titulo}</h2>
      {explicacion && <p className="bloque__explicacion">{explicacion}</p>}
      <div className={clase}>
        {grafico && <div className="bloque__grafico">{grafico}</div>}
        <div className="bloque__tabla" role="region" aria-labelledby={`${id}-titulo`} tabIndex={0}>
          {children}
        </div>
      </div>
    </section>
  );
}

/** Las celdas de una fila con cifras (media, % de menos de 3, viajes), o «datos insuficientes» si no llega. */
export function CeldasCifras({ cifras, minimo }: { cifras: Cifras; minimo: number }) {
  if (!cifras.suficiente || !cifras.porcentajes) {
    return (
      <td colSpan={3} className="nota">
        {textoInsuficiente(cifras, minimo)}
      </td>
    );
  }
  return (
    <>
      <td className="cifra">{minutos(cifras.mediaMinutos)}</td>
      <td className="cifra">{porcentaje(cifras.porcentajes.menosDe3)}</td>
      <td className="cifra">{entero(cifras.viajes)}</td>
    </>
  );
}

/**
 * Cambiar el periodo: dos fechas y un botón. Navega (la URL es la página); no envía ningún formulario (la CSP tiene
 * `form-action 'none'`). Un periodo al revés no se desactiva: se avisa junto a los campos.
 */
export function ElegirPeriodo({ periodo, alCambiar }: { periodo: Periodo; alCambiar(desde: string, hasta: string): void }) {
  const [desde, setDesde] = useState(periodo.desde);
  const [hasta, setHasta] = useState(periodo.hasta);
  const [error, setError] = useState(false);
  const idError = useId();
  return (
    <form
      className="elegir-periodo"
      aria-label="Periodo"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        const malo = !desde || !hasta || desde > hasta;
        setError(malo);
        if (!malo) alCambiar(desde, hasta);
      }}
    >
      <label>
        <span>Desde</span>
        <input
          type="date"
          name="desde"
          value={desde}
          onChange={(e) => setDesde(e.target.value)}
          aria-invalid={error || undefined}
          aria-describedby={error ? idError : undefined}
        />
      </label>
      <label>
        <span>Hasta</span>
        <input
          type="date"
          name="hasta"
          value={hasta}
          onChange={(e) => setHasta(e.target.value)}
          aria-invalid={error || undefined}
          aria-describedby={error ? idError : undefined}
        />
      </label>
      <button type="submit" className="boton">
        Ver el periodo
      </button>
      {error && (
        <p className="elegir-periodo__error" id={idError} role="alert">
          Elige las dos fechas, con «desde» antes de «hasta».
        </p>
      )}
    </form>
  );
}
