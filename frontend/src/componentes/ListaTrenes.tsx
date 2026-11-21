import { useMemo } from 'react';
import type { TrenMapa } from '../api/tipos';
import { hora, textoSalida } from '../mapa/retraso';
import { ordenarTrenes } from './textos';
import { Retraso } from './Retraso';

function situacion(tren: TrenMapa): string {
  if (tren.estado === 'EN_ORIGEN') return textoSalida(tren) ?? 'En origen, aún sin salir';
  if (tren.estado === 'CANCELADO') return 'Cancelado';
  if (tren.estado === 'TERMINADO') return 'Ha llegado a su destino';
  if (tren.proximaParada) {
    const llegada = hora(tren.proximaParada.llegadaEstimada ?? tren.proximaParada.llegadaProgramada);
    return `Próxima: ${tren.proximaParada.nombre}${llegada ? ` (${llegada})` : ''}`;
  }
  return 'En marcha';
}

/**
 * La vista de lista: la alternativa accesible al mapa, con los mismos trenes y las mismas fichas. Cada tren es un botón
 * (se llega con el tabulador y se abre con Intro o Espacio).
 */
export function ListaTrenes({
  trenes,
  titulo,
  seleccionado,
  alAbrir,
  nucleos,
}: {
  trenes: readonly TrenMapa[];
  titulo: string;
  seleccionado: string | null;
  alAbrir(id: string): void;
  /**
   * Con toda España: el nombre de cada núcleo. La lista se agrupa por núcleo, con un encabezado por grupo (para saltar
   * de uno a otro con el lector de pantalla) en vez de repetir el núcleo en cada tren.
   */
  nucleos?: ReadonlyMap<number, string>;
}) {
  // Con toda España son unos 400: se ordenan y agrupan solo cuando cambian, no en cada pintado de la aplicación.
  const grupos = useMemo(() => {
    const ordenados = ordenarTrenes(trenes, nucleos);
    if (!nucleos) return [{ nucleo: null, trenes: ordenados }];
    const porNucleo: { nucleo: number; trenes: TrenMapa[] }[] = [];
    for (const t of ordenados) {
      const ultimo = porNucleo.at(-1);
      if (ultimo?.nucleo === t.nucleo) ultimo.trenes.push(t);
      else porNucleo.push({ nucleo: t.nucleo, trenes: [t] });
    }
    return porNucleo;
  }, [trenes, nucleos]);

  const elementos = (lista: readonly TrenMapa[]) => (
    <ul className="lista__trenes">
      {lista.map((t) => (
        <li key={t.id}>
          <button
            type="button"
            className={`lista__tren${t.id === seleccionado ? ' lista__tren--activo' : ''}`}
            aria-current={t.id === seleccionado ? 'true' : undefined}
            onClick={() => alAbrir(t.id)}
          >
            {/* Las comas ocultas separan las partes al leerlas con un lector de pantalla («R15, hacia Reus, …»). */}
            <span className="lista__linea">{t.linea ?? '¿?'}</span>
            <span className="solo-lector">, </span>
            <span className="lista__texto">
              <span className="lista__destino">{t.destino ? `Hacia ${t.destino}` : 'Destino desconocido'}</span>
              <span className="solo-lector">, </span>
              <span className="lista__detalle">{situacion(t)}</span>
            </span>
            <span className="solo-lector">, </span>
            {t.estado === 'EN_MARCHA' || t.estado === 'EN_ORIGEN' ? (
              <Retraso minutos={t.retrasoMin} />
            ) : (
              <span className="retraso">{t.estado === 'CANCELADO' ? 'cancelado' : 'terminado'}</span>
            )}
          </button>
        </li>
      ))}
    </ul>
  );

  return (
    <section className="lista" aria-labelledby="lista-titulo">
      <h2 id="lista-titulo" tabIndex={-1}>
        {titulo}
      </h2>
      {trenes.length === 0 ? (
        <p className="nota">No hay trenes que enseñar ahora.</p>
      ) : (
        grupos.map((g) =>
          g.nucleo === null ? (
            <div key="todos">{elementos(g.trenes)}</div>
          ) : (
            <section key={g.nucleo} className="lista__grupo" aria-labelledby={`lista-nucleo-${g.nucleo}`}>
              <h3 id={`lista-nucleo-${g.nucleo}`} className="lista__nucleo">
                {nucleos?.get(g.nucleo) ?? 'Otro núcleo'}{' '}
                <span className="lista__cuantos">
                  ({g.trenes.length} {g.trenes.length === 1 ? 'tren' : 'trenes'})
                </span>
              </h3>
              {elementos(g.trenes)}
            </section>
          ),
        )
      )}
    </section>
  );
}
