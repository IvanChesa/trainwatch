import { useState, type ReactNode } from 'react';
import { COBERTURA_BAJA, entero, fecha, minutos, porcentaje, textoCobertura, textoInsuficiente, textoPeriodo } from './formato';
import type { Cifras, Cobertura, Muestra, Periodo, Publicacion } from './tipos';

/** Las piezas que comparten las tres páginas de estadísticas. */

/**
 * Lo que va siempre a la vista con una cifra (encargo de la fase 6, punto 4): el periodo, «solo trenes vistos en
 * directo», la cobertura y lo que se ha quitado. La cita de Renfe y el aviso de no oficial van en la cabecera y el pie.
 */
export function Contexto({
  periodo,
  cobertura,
  etiquetaCobertura = 'Cobertura del núcleo',
  muestra,
}: {
  periodo: Periodo;
  cobertura: Cobertura | null;
  etiquetaCobertura?: string;
  muestra: Muestra | null;
}) {
  const cob = textoCobertura(cobertura);
  const baja = cobertura?.porcentaje !== null && cobertura?.porcentaje !== undefined && cobertura.porcentaje < COBERTURA_BAJA;
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
      <div className={baja ? 'contexto--aviso' : undefined}>
        <dt>{etiquetaCobertura}</dt>
        <dd>
          {cob ?? 'sin datos del horario'}
          {baja && '. Con tan poca cobertura, la cifra es solo de los trenes que se ven'}
        </dd>
      </div>
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
        Una media de pocos días bailaría de una semana a otra. Las cifras se publican cuando hay cuatro semanas de datos.
      </p>
    </section>
  );
}

/** Las cifras principales en una sola franja: la media primero y más grande, que es la respuesta. */
export function Cifrado({ cifras, minimo }: { cifras: Cifras; minimo?: number }) {
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
        <dd>{minutos(cifras.medianaMinutos)}</dd>
      </div>
      <div>
        <dt>9 de cada 10 paradas, hasta</dt>
        <dd>{minutos(cifras.p90Minutos)}</dd>
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

/** Una sección con su título y, a la derecha en pantallas anchas, el gráfico; la tabla, siempre visible. */
export function Bloque({
  id,
  titulo,
  explicacion,
  grafico,
  children,
}: {
  id: string;
  titulo: string;
  explicacion?: ReactNode;
  grafico?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="bloque" aria-labelledby={`${id}-titulo`}>
      <h2 id={`${id}-titulo`}>{titulo}</h2>
      {explicacion && <p className="bloque__explicacion">{explicacion}</p>}
      <div className={grafico ? 'bloque__cuerpo bloque__cuerpo--con-grafico' : 'bloque__cuerpo'}>
        {grafico && <div className="bloque__grafico">{grafico}</div>}
        <div className="bloque__tabla">{children}</div>
      </div>
    </section>
  );
}

/** Las celdas de una fila con cifras (media, % de menos de 3, viajes), o «datos insuficientes» si no llega. */
export function CeldasCifras({ cifras, minimo }: { cifras: Cifras; minimo?: number }) {
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

/** Cambiar el periodo: dos fechas y un botón. Navega (la URL es la página); no envía ningún formulario. */
export function ElegirPeriodo({ periodo, alCambiar }: { periodo: Periodo; alCambiar(desde: string, hasta: string): void }) {
  const [desde, setDesde] = useState(periodo.desde);
  const [hasta, setHasta] = useState(periodo.hasta);
  const malo = desde > hasta;
  return (
    <form
      className="elegir-periodo"
      aria-label="Periodo"
      onSubmit={(e) => {
        e.preventDefault();
        if (!malo) alCambiar(desde, hasta);
      }}
    >
      <label>
        <span>Desde</span>
        <input type="date" value={desde} max={hasta} onChange={(e) => setDesde(e.target.value)} required />
      </label>
      <label>
        <span>Hasta</span>
        <input type="date" value={hasta} min={desde} onChange={(e) => setHasta(e.target.value)} required />
      </label>
      <button type="submit" className="boton" disabled={malo}>
        Ver
      </button>
      {malo && (
        <p className="nota" role="alert">
          «Desde» tiene que ser anterior a «hasta».
        </p>
      )}
    </form>
  );
}
