import { consultas } from '../api/consultas';
import type { FichaTren as Ficha, TrenMapa } from '../api/tipos';
import { useConsulta } from '../api/useConsulta';
import { urlLinea } from '../estadisticas/ruta';
import { hora, textoSalida } from '../mapa/retraso';
import { ListaAvisos } from './Avisos';
import { Retraso } from './Retraso';

const ESTADOS: Record<string, string> = {
  EN_MARCHA: 'En marcha',
  EN_ORIGEN: 'En su estación de origen, aún sin salir',
  CANCELADO: 'Cancelado',
  TERMINADO: 'Ha llegado a su destino',
};

const SITUACIONES: Record<string, string> = {
  PASADA: 'pasada',
  ACTUAL: 'está aquí o acaba de salir',
  PROXIMA: 'próxima parada',
  PENDIENTE: 'pendiente',
};

/**
 * Ficha del tren: lo que cambia a cada momento (retraso, próxima parada) sale del directo; las paradas y el resto, de
 * `GET /api/trenes/{id}`, que se vuelve a pedir cada 30 s.
 */
export function FichaTren({
  id,
  enDirecto,
  alAbrirEstacion,
}: {
  id: string;
  enDirecto: TrenMapa | undefined;
  alAbrirEstacion(id: string): void;
}) {
  const consulta = useConsulta<Ficha>(`tren:${id}`, (signal) => consultas.tren(id, signal), 30_000);
  const ficha = consulta.tipo === 'lista' ? consulta.datos : null;
  const estado = enDirecto?.estado ?? ficha?.estado;
  const retraso = enDirecto ? enDirecto.retrasoMin : ficha?.retrasoMin;
  const proxima = enDirecto?.proximaParada ?? null;
  const llegadaEstimada = hora(proxima?.llegadaEstimada);
  const llegadaProgramada = hora(proxima?.llegadaProgramada);

  return (
    <div className="ficha">
      <dl className="datos">
        {ficha && (ficha.origen || ficha.destino) && (
          <>
            <dt>Recorrido</dt>
            <dd>
              {ficha.origen ?? '¿?'} → {ficha.destino ?? '¿?'}
            </dd>
          </>
        )}
        {estado && (
          <>
            <dt>Estado</dt>
            <dd>{estado === 'EN_ORIGEN' && enDirecto ? (textoSalida(enDirecto) ?? ESTADOS[estado]) : ESTADOS[estado]}</dd>
          </>
        )}
        {(estado === 'EN_MARCHA' || estado === 'EN_ORIGEN') && (
          <>
            <dt>Retraso</dt>
            <dd>
              <Retraso minutos={retraso} />
            </dd>
          </>
        )}
        {proxima && (
          <>
            <dt>Próxima parada</dt>
            <dd>
              <button type="button" className="enlace" onClick={() => alAbrirEstacion(proxima.id)}>
                {proxima.nombre}
              </button>
              {llegadaEstimada && <> a las {llegadaEstimada}</>}
              {llegadaProgramada && llegadaProgramada !== llegadaEstimada && <> (prevista a las {llegadaProgramada})</>}
            </dd>
          </>
        )}
      </dl>

      {consulta.tipo === 'cargando' && <p className="nota">Cargando la ficha…</p>}
      {consulta.tipo === 'error' && (
        <p className="nota">
          {consulta.status === 404
            ? 'Este tren ya no está en directo.'
            : `No se ha podido cargar la ficha: ${consulta.mensaje}`}
        </p>
      )}
      {ficha?.linea && !ficha.sinHorario && (
        <p>
          <a href={urlLinea(ficha.nucleo, ficha.linea)}>Puntualidad de la {ficha.linea}</a>
        </p>
      )}
      {ficha?.sinHorario && (
        <p className="nota">Este tren no está en el horario publicado: se enseña solo lo que da el directo.</p>
      )}

      {ficha && ficha.avisos.length > 0 && (
        <>
          <h3>Avisos de la línea</h3>
          <ListaAvisos avisos={ficha.avisos} vacio="" />
        </>
      )}

      {ficha && ficha.paradas.length > 0 && (
        <>
          <h3>Paradas</h3>
          <ol className="paradas">
            {ficha.paradas.map((p) => {
              const programada = hora(p.llegadaProgramada ?? p.salidaProgramada);
              const estimada = hora(p.llegadaEstimada);
              const situacion = SITUACIONES[p.situacion];
              return (
                <li
                  key={`${p.secuencia}-${p.id}`}
                  className={`parada parada--${p.situacion.toLowerCase()}`}
                  aria-current={p.situacion === 'PROXIMA' ? 'step' : undefined}
                >
                  <span className="parada__hora">
                    {programada ?? '--:--'}
                    {estimada && estimada !== programada && <span className="parada__estimada"> → {estimada}</span>}
                  </span>
                  <span className="parada__nombre">
                    <button type="button" className="enlace" onClick={() => alAbrirEstacion(p.id)}>
                      {p.nombre}
                    </button>
                    {situacion && p.situacion !== 'PENDIENTE' && (
                      <span className="parada__situacion">
                        {' · '}
                        {situacion}
                        {p.situacion === 'PASADA' && p.retrasoFinalMin !== null && `, con ${p.retrasoFinalMin} min`}
                      </span>
                    )}
                  </span>
                </li>
              );
            })}
          </ol>
        </>
      )}
    </div>
  );
}
