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
  /** Con toda España: el nombre de cada núcleo, para enseñarlo en cada tren y ordenar por él. */
  nucleos?: ReadonlyMap<number, string>;
}) {
  const ordenados = ordenarTrenes(trenes, nucleos);
  return (
    <section className="lista" aria-labelledby="lista-titulo">
      <h2 id="lista-titulo" tabIndex={-1}>
        {titulo}
      </h2>
      {ordenados.length === 0 ? (
        <p className="nota">No hay trenes que enseñar ahora.</p>
      ) : (
        <ul className="lista__trenes">
          {ordenados.map((t) => (
            <li key={t.id}>
              <button
                type="button"
                className={`lista__tren${t.id === seleccionado ? ' lista__tren--activo' : ''}`}
                aria-current={t.id === seleccionado ? 'true' : undefined}
                onClick={() => alAbrir(t.id)}
              >
                <span className="lista__linea">{t.linea ?? '¿?'}</span>
                <span className="lista__texto">
                  {nucleos && <span className="lista__nucleo">{nucleos.get(t.nucleo) ?? `Núcleo ${t.nucleo}`}</span>}
                  <span className="lista__destino">{t.destino ? `Hacia ${t.destino}` : 'Destino desconocido'}</span>
                  <span className="lista__detalle">{situacion(t)}</span>
                </span>
                {t.estado === 'EN_MARCHA' || t.estado === 'EN_ORIGEN' ? (
                  <Retraso minutos={t.retrasoMin} />
                ) : (
                  <span className="retraso">{t.estado === 'CANCELADO' ? 'cancelado' : 'terminado'}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
