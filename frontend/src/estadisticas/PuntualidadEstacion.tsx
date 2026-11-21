import { pedirJson } from '../api/consultas';
import { useConsulta } from '../api/useConsulta';
import { compararLineas } from '../mapa/retraso';
import { CeldasCifras, Cifrado } from './Comunes';
import { porcentaje, textoPeriodo } from './formato';
import { urlLinea } from './ruta';
import type { PuntualidadEstacion as Datos } from './tipos';
import './estadisticas.css';

/** La puntualidad de la estación en su ficha (las últimas 4 semanas), con enlaces a la página de cada línea. */
export function PuntualidadEstacion({ id }: { id: string }) {
  const ruta = `/api/estadisticas/estaciones/${encodeURIComponent(id)}`;
  const consulta = useConsulta<Datos>(`puntualidad:${id}`, (s) => pedirJson<Datos>(ruta, s));
  return (
    <section className="puntualidad-estacion" aria-labelledby={`puntualidad-${id}`}>
      <h3 id={`puntualidad-${id}`}>Puntualidad</h3>
      <div aria-live="polite" aria-busy={consulta.tipo === 'cargando'}>
        {consulta.tipo === 'cargando' && <p className="nota">Cargando la puntualidad…</p>}
        {consulta.tipo === 'error' && (
          <p className="nota">
            {consulta.status === 404 ? 'Sin datos de puntualidad.' : 'No se ha podido cargar la puntualidad.'}
          </p>
        )}
        {consulta.tipo === 'lista' && <Contenido datos={consulta.datos} />}
      </div>
    </section>
  );
}

function Contenido({ datos }: { datos: Datos }) {
  if (!datos.publicacion.publicado) return <p className="nota">{datos.publicacion.texto}</p>;
  const lineas = [...datos.porLinea].sort((a, b) => compararLineas(a.linea, b.linea));
  return (
    <>
      <p className="nota">
        {textoPeriodo(datos.periodo)}. Solo trenes vistos en directo.
        {datos.coberturaNucleo?.porcentaje != null &&
          ` Cobertura del núcleo: ${porcentaje(datos.coberturaNucleo.porcentaje)}.`}
      </p>
      {datos.resumen && <Cifrado cifras={datos.resumen} minimo={datos.publicacion.muestraMinima} />}
      {lineas.length > 0 && (
        <table className="tabla">
          <caption className="solo-lector">Puntualidad por línea en {datos.nombre ?? 'esta estación'}</caption>
          <thead>
            <tr>
              <th scope="col">Línea</th>
              <th scope="col" className="cifra">
                Media
              </th>
              <th scope="col" className="cifra">
                Menos de 3 min
              </th>
              <th scope="col" className="cifra">
                5 min o menos
              </th>
              <th scope="col" className="cifra">
                Viajes
              </th>
            </tr>
          </thead>
          <tbody>
            {lineas.map((l) => (
              <tr key={`${l.nucleo}-${l.linea}`}>
                <th scope="row">
                  <a href={urlLinea(l.nucleo, l.linea)} translate="no">
                    {l.linea}
                  </a>
                </th>
                <CeldasCifras cifras={l.cifras} minimo={datos.publicacion.muestraMinima} />
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
