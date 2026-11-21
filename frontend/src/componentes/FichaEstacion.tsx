import { useEffect } from 'react';
import { consultas } from '../api/consultas';
import type { FichaEstacion as Ficha } from '../api/tipos';
import { useConsulta } from '../api/useConsulta';
import { hora } from '../mapa/retraso';
import { PuntualidadEstacion } from '../estadisticas/PuntualidadEstacion';
import { ListaAvisos } from './Avisos';
import { Retraso } from './Retraso';

/** Ficha de la estación: sus líneas, los próximos trenes (de 30 min atrás a 60 adelante) y sus avisos. */
export function FichaEstacion({
  id,
  alAbrirTren,
  alConocerNombre,
}: {
  id: string;
  alAbrirTren(id: string): void;
  /** Para poner el nombre en el título de la hoja si se abrió sin saberlo. */
  alConocerNombre?(nombre: string): void;
}) {
  const consulta = useConsulta<Ficha>(`estacion:${id}`, (signal) => consultas.estacion(id, signal), 30_000);
  const nombre = consulta.tipo === 'lista' ? consulta.datos.nombre : null;
  useEffect(() => {
    if (nombre) alConocerNombre?.(nombre);
    // Solo cuando llega el nombre.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nombre]);
  if (consulta.tipo === 'cargando') return <p className="nota">Cargando la estación…</p>;
  if (consulta.tipo === 'error')
    return (
      <p className="nota">
        {consulta.status === 404 ? 'No se encuentra la estación.' : `No se ha podido cargar: ${consulta.mensaje}`}
      </p>
    );
  const f = consulta.datos;
  return (
    <div className="ficha">
      {f.lineas.length > 0 && (
        <ul className="lineas" aria-label="Líneas que paran aquí">
          {f.lineas.map((l) => (
            <li key={`${l.nucleo}-${l.linea}`} className="chip-linea">
              {l.linea}
            </li>
          ))}
        </ul>
      )}
      <h3>Próximos trenes</h3>
      {f.proximos.length === 0 ? (
        <p className="nota">No hay trenes previstos en la próxima hora.</p>
      ) : (
        <table className="proximos">
          <caption className="solo-lector">Próximos trenes en {f.nombre}</caption>
          <thead>
            <tr>
              <th scope="col">Sale</th>
              <th scope="col">Línea y destino</th>
              <th scope="col">Retraso</th>
            </tr>
          </thead>
          <tbody>
            {f.proximos.map((p) => {
              const programada = hora(p.salidaProgramada);
              const estimada = hora(p.salidaEstimada);
              const destino = `${p.linea ?? ''} ${p.destino ? `hacia ${p.destino}` : ''}`.trim();
              return (
                <tr key={p.id}>
                  <td>
                    {programada ?? '--:--'}
                    {estimada && estimada !== programada && <span className="parada__estimada"> → {estimada}</span>}
                  </td>
                  <td>
                    {p.enDirecto ? (
                      <button type="button" className="enlace" onClick={() => alAbrirTren(p.id)}>
                        {destino}
                      </button>
                    ) : (
                      destino
                    )}
                  </td>
                  <td>{p.enDirecto ? <Retraso minutos={p.retrasoMin} /> : <span className="nota">sin directo</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <h3>Avisos</h3>
      <ListaAvisos avisos={f.avisos} vacio="Sin avisos para esta estación." />
      <PuntualidadEstacion id={id} />
    </div>
  );
}
