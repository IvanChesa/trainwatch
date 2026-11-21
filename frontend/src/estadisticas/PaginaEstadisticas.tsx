import { useEffect } from 'react';
import { pedirJson } from '../api/consultas';
import { useConsulta } from '../api/useConsulta';
import { BarraCelda, Columnas, Reparto } from './Graficos';
import { Bloque, CeldasCifras, Cifrado, Contexto, ElegirPeriodo, SinPublicar } from './Comunes';
import { entero, fechaCorta, frase, franja, minutos, numero, porcentaje, textoInsuficiente } from './formato';
import { rutaApiDe, urlLinea, urlNucleo, urlTodos, type RutaEstadisticas } from './ruta';
import type { Cifras, PuntualidadLinea, PuntualidadNucleo, PuntualidadTodos } from './tipos';
import './estadisticas.css';

/** Las páginas de estadísticas: toda España, un núcleo o una línea, según la ruta (`ruta.ts`). */
export default function PaginaEstadisticas({ ruta }: { ruta: RutaEstadisticas }) {
  const consulta = useConsulta<PuntualidadTodos | PuntualidadNucleo | PuntualidadLinea>(rutaApiDe(ruta), (s) =>
    pedirJson(rutaApiDe(ruta), s),
  );
  const irA = (desde: string, hasta: string) => {
    const url =
      ruta.tipo === 'todos'
        ? urlTodos(desde, hasta)
        : ruta.tipo === 'nucleo'
          ? urlNucleo(ruta.nucleo, desde, hasta)
          : urlLinea(ruta.nucleo, ruta.linea, desde, hasta);
    window.location.assign(url);
  };

  let titulo = 'Puntualidad en toda España';
  if (consulta.tipo === 'lista') {
    const d = consulta.datos;
    if ('lineas' in d) titulo = `Puntualidad en ${d.nombre}`;
    else if ('linea' in d) titulo = `Puntualidad de la ${d.linea} (${d.nombreNucleo})`;
  } else if (ruta.tipo === 'nucleo') titulo = 'Puntualidad del núcleo';
  else if (ruta.tipo === 'linea') titulo = `Puntualidad de la ${ruta.linea}`;

  useEffect(() => {
    document.title = `${titulo} · TrainWatch`;
  }, [titulo]);

  const mapa = ruta.tipo === 'todos' ? '/?nucleo=todos' : `/?nucleo=${ruta.nucleo}`;

  return (
    <div className="estad">
      <a className="saltar" href="#contenido">
        Ir al contenido
      </a>
      <header className="estad__barra">
        <a className="estad__marca" href="/">
          TrainWatch
        </a>
        <nav aria-label="Secciones">
          <a href={mapa}>Mapa en directo</a>
          <a href={urlTodos()} aria-current={ruta.tipo === 'todos' ? 'page' : undefined}>
            Puntualidad
          </a>
        </nav>
        <p className="estad__no-oficial">
          Proyecto personal <strong>no oficial</strong>, sin relación con Renfe ni con Adif.
        </p>
      </header>

      <main id="contenido" className="estad__contenido" tabIndex={-1}>
        <Migas ruta={ruta} datos={consulta.tipo === 'lista' ? consulta.datos : null} />
        <h1>{titulo}</h1>
        {consulta.tipo === 'cargando' && <p className="nota">Cargando las cifras…</p>}
        {consulta.tipo === 'error' && (
          <p className="nota" role="alert">
            {consulta.status === 404
              ? 'No hay datos de esta página: puede que el núcleo o la línea no existan.'
              : `No se han podido cargar las cifras: ${consulta.mensaje}`}
          </p>
        )}
        {consulta.tipo === 'lista' &&
          ('nucleos' in consulta.datos ? (
            <VistaTodos datos={consulta.datos} alCambiarPeriodo={irA} />
          ) : 'lineas' in consulta.datos ? (
            <VistaNucleo datos={consulta.datos} alCambiarPeriodo={irA} />
          ) : (
            <VistaLinea datos={consulta.datos} alCambiarPeriodo={irA} />
          ))}
      </main>

      <footer className="estad__pie">
        <p>
          Datos: Renfe (CC BY 4.0). Cifras propias de TrainWatch, calculadas con los trenes vistos en directo: no son
          las oficiales de Renfe. Proyecto no oficial.
        </p>
      </footer>
    </div>
  );
}

function Migas({
  ruta,
  datos,
}: {
  ruta: RutaEstadisticas;
  datos: PuntualidadTodos | PuntualidadNucleo | PuntualidadLinea | null;
}) {
  if (ruta.tipo === 'todos') return null;
  const nombreNucleo = datos && 'nombreNucleo' in datos ? datos.nombreNucleo : datos && 'nombre' in datos ? datos.nombre : `Núcleo ${ruta.nucleo}`;
  return (
    <nav className="migas" aria-label="Estás en">
      <ol>
        <li>
          <a href={urlTodos(ruta.desde, ruta.hasta)}>Toda España</a>
        </li>
        <li>
          {ruta.tipo === 'linea' ? (
            <a href={urlNucleo(ruta.nucleo, ruta.desde, ruta.hasta)}>{nombreNucleo}</a>
          ) : (
            <span aria-current="page">{nombreNucleo}</span>
          )}
        </li>
        {ruta.tipo === 'linea' && (
          <li>
            <span aria-current="page">{datos && 'linea' in datos ? datos.linea : ruta.linea}</span>
          </li>
        )}
      </ol>
    </nav>
  );
}

/** «Rodalies de Catalunya: el 81,2 % de las paradas, con menos de 3 min»: la primera del ranking y la última. */
function hallazgoRanking(filas: { nombre: string; cifras: Cifras }[]): string | null {
  const con = filas.filter((f) => f.cifras.suficiente && f.cifras.porcentajes);
  if (con.length === 0) return null;
  const primero = con[0];
  const ultimo = con[con.length - 1];
  const p = (f: { cifras: Cifras }) => porcentaje(f.cifras.porcentajes!.menosDe3);
  if (con.length === 1) return `${primero.nombre}: el ${p(primero)} de las paradas, con menos de 3 min.`;
  return `La más puntual, ${primero.nombre} (el ${p(primero)} de las paradas con menos de 3 min); la que menos, ${ultimo.nombre} (${p(ultimo)}).`;
}

// ---------------------------------------------------------------- toda España

function VistaTodos({ datos, alCambiarPeriodo }: { datos: PuntualidadTodos; alCambiarPeriodo(d: string, h: string): void }) {
  if (!datos.publicacion.publicado) return <SinPublicar publicacion={datos.publicacion} />;
  const hallazgo = hallazgoRanking(datos.nucleos);
  return (
    <>
      <p className="hallazgo">{hallazgo ?? 'Aún no hay ningún núcleo con datos suficientes en este periodo.'}</p>
      <Contexto periodo={datos.periodo} cobertura={datos.cobertura} etiquetaCobertura="Cobertura de toda España" muestra={datos.muestra} />
      <ElegirPeriodo periodo={datos.periodo} alCambiar={alCambiarPeriodo} />
      <Bloque
        id="ranking-nucleos"
        titulo="Los núcleos, del más puntual al que menos"
        explicacion="Por el porcentaje de paradas a las que los trenes llegan con menos de 3 minutos de retraso. Las líneas se comparan dentro de cada núcleo: sus nombres se repiten (hay una C1 en Madrid, otra en Valencia…)."
      >
        <table className="tabla">
          <caption className="solo-lector">Ranking de puntualidad de los núcleos</caption>
          <thead>
            <tr>
              <th scope="col">Puesto</th>
              <th scope="col">Núcleo</th>
              <th scope="col">Menos de 3 min</th>
              <th scope="col">Media</th>
              <th scope="col">Viajes</th>
              <th scope="col">Cobertura</th>
            </tr>
          </thead>
          <tbody>
            {datos.nucleos.map((n) => (
              <tr key={n.nucleo}>
                <td className="cifra">{n.puesto ?? '—'}</td>
                <th scope="row">
                  <a href={urlNucleo(n.nucleo, datos.periodo.desde, datos.periodo.hasta)}>{n.nombre}</a>
                </th>
                {n.cifras.suficiente && n.cifras.porcentajes ? (
                  <>
                    <td className="cifra">
                      <BarraCelda valor={n.cifras.porcentajes.menosDe3} />
                      {porcentaje(n.cifras.porcentajes.menosDe3)}
                    </td>
                    <td className="cifra">{minutos(n.cifras.mediaMinutos)}</td>
                  </>
                ) : (
                  <td colSpan={2} className="nota">
                    {textoInsuficiente(n.cifras)}
                  </td>
                )}
                <td className="cifra">{entero(n.cifras.viajes)}</td>
                <td className="cifra">{porcentaje(n.cobertura?.porcentaje)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Bloque>
    </>
  );
}

// ---------------------------------------------------------------- núcleo

function VistaNucleo({ datos, alCambiarPeriodo }: { datos: PuntualidadNucleo; alCambiarPeriodo(d: string, h: string): void }) {
  if (!datos.publicacion.publicado) return <SinPublicar publicacion={datos.publicacion} />;
  const filas = datos.lineas.map((l) => ({ ...l, nombre: l.linea }));
  const hallazgo = hallazgoRanking(filas);
  return (
    <>
      <p className="hallazgo">{hallazgo ?? 'Aún no hay ninguna línea con datos suficientes en este periodo.'}</p>
      <Contexto periodo={datos.periodo} cobertura={datos.cobertura} muestra={datos.muestra} />
      <ElegirPeriodo periodo={datos.periodo} alCambiar={alCambiarPeriodo} />
      <Bloque
        id="ranking-lineas"
        titulo="Las líneas, de la más puntual a la que menos"
        explicacion="Por el porcentaje de paradas a las que llegan con menos de 3 minutos de retraso, y luego por la media. Las que no llegan a 30 viajes van al final, sin puesto."
      >
        <table className="tabla">
          <caption className="solo-lector">Ranking de puntualidad de las líneas de {datos.nombre}</caption>
          <thead>
            <tr>
              <th scope="col">Puesto</th>
              <th scope="col">Línea</th>
              <th scope="col">Menos de 3 min</th>
              <th scope="col">Media</th>
              <th scope="col">Viajes</th>
              <th scope="col">Cancelados</th>
              <th scope="col">Cobertura</th>
            </tr>
          </thead>
          <tbody>
            {datos.lineas.map((l) => (
              <tr key={l.linea}>
                <td className="cifra">{l.puesto ?? '—'}</td>
                <th scope="row">
                  <a href={urlLinea(datos.nucleo, l.linea, datos.periodo.desde, datos.periodo.hasta)}>{l.linea}</a>
                </th>
                {l.cifras.suficiente && l.cifras.porcentajes ? (
                  <>
                    <td className="cifra">
                      <BarraCelda valor={l.cifras.porcentajes.menosDe3} />
                      {porcentaje(l.cifras.porcentajes.menosDe3)}
                    </td>
                    <td className="cifra">{minutos(l.cifras.mediaMinutos)}</td>
                  </>
                ) : (
                  <td colSpan={2} className="nota">
                    {textoInsuficiente(l.cifras)}
                  </td>
                )}
                <td className="cifra">{entero(l.cifras.viajes)}</td>
                <td className="cifra">{entero(l.cancelados)}</td>
                <td className="cifra">{porcentaje(l.cobertura?.porcentaje)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Bloque>
    </>
  );
}

// ---------------------------------------------------------------- línea

const DIAS_CORTOS = ['lun', 'mar', 'mié', 'jue', 'vie', 'sáb', 'dom'];

function media(c: Cifras): number | null {
  return c.suficiente ? c.mediaMinutos : null;
}

function TablaSerie({
  titulo,
  cabecera,
  filas,
}: {
  titulo: string;
  cabecera: string;
  filas: { clave: string; etiqueta: string; cifras: Cifras }[];
}) {
  return (
    <table className="tabla">
      <caption className="solo-lector">{titulo}</caption>
      <thead>
        <tr>
          <th scope="col">{cabecera}</th>
          <th scope="col">Media</th>
          <th scope="col">Menos de 3 min</th>
          <th scope="col">Viajes</th>
        </tr>
      </thead>
      <tbody>
        {filas.map((f) => (
          <tr key={f.clave}>
            <th scope="row">{f.etiqueta}</th>
            <CeldasCifras cifras={f.cifras} />
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function VistaLinea({ datos, alCambiarPeriodo }: { datos: PuntualidadLinea; alCambiarPeriodo(d: string, h: string): void }) {
  if (!datos.publicacion.publicado) return <SinPublicar publicacion={datos.publicacion} />;
  const r = datos.resumen;
  const horas = [...datos.porHora].sort((a, b) => a.hora - b.hora);
  const destino = datos.llegadaDestino;
  return (
    <>
      <p className="hallazgo">{frase(r) ?? (r ? `${textoInsuficiente(r)} en este periodo.` : 'Sin datos.')}</p>
      <Contexto periodo={datos.periodo} cobertura={datos.coberturaLinea} etiquetaCobertura="Cobertura de la línea" muestra={datos.muestra} />
      <ElegirPeriodo periodo={datos.periodo} alCambiar={alCambiarPeriodo} />

      {r && (
        <section className="bloque" aria-labelledby="resumen-titulo">
          <h2 id="resumen-titulo">Todo el periodo</h2>
          <Cifrado cifras={r} />
          {r.suficiente && r.porcentajes && <Reparto porcentajes={r.porcentajes} titulo="Paradas por retraso al llegar" />}
          {destino && (
            <p className="bloque__explicacion">
              {destino.suficiente
                ? `Al llegar a su destino, ${minutos(destino.mediaMinutos)} de media y el ${porcentaje(destino.menosDe3)} con menos de 3 min (${entero(destino.viajesEnDestino)} de ${entero(destino.viajes)} viajes se vieron llegar).`
                : `Llegada a destino: ${textoInsuficiente({ viajes: destino.viajesEnDestino })} vistos llegar, de ${entero(destino.viajes)}.`}
            </p>
          )}
        </section>
      )}

      <Bloque
        id="por-hora"
        titulo="Por hora"
        explicacion="Retraso medio, en minutos, según la hora programada de llegada a la parada. «—»: menos de 30 viajes en esa hora."
        grafico={
          horas.length > 0 ? (
            <Columnas columnas={horas.map((h) => ({ clave: String(h.hora), etiqueta: String(h.hora).padStart(2, '0'), valor: media(h.cifras) }))} />
          ) : undefined
        }
      >
        {horas.length === 0 ? (
          <p className="nota">Sin datos en este periodo.</p>
        ) : (
          <TablaSerie
            titulo={`Puntualidad de la ${datos.linea} por hora`}
            cabecera="Hora"
            filas={horas.map((h) => ({ clave: String(h.hora), etiqueta: franja(h.hora), cifras: h.cifras }))}
          />
        )}
      </Bloque>

      <Bloque
        id="por-dia"
        titulo="Por día de la semana"
        explicacion="Del día de servicio: un tren que sale el viernes a las 23:50 cuenta en viernes."
        grafico={
          datos.porDiaSemana.length > 0 ? (
            <Columnas columnas={datos.porDiaSemana.map((d) => ({ clave: String(d.dia), etiqueta: DIAS_CORTOS[d.dia - 1], valor: media(d.cifras) }))} />
          ) : undefined
        }
      >
        {datos.porDiaSemana.length === 0 ? (
          <p className="nota">Sin datos en este periodo.</p>
        ) : (
          <TablaSerie
            titulo={`Puntualidad de la ${datos.linea} por día de la semana`}
            cabecera="Día"
            filas={datos.porDiaSemana.map((d) => ({
              clave: String(d.dia),
              etiqueta: `${d.nombre} (${d.dias} ${d.dias === 1 ? 'día' : 'días'})`,
              cifras: d.cifras,
            }))}
          />
        )}
      </Bloque>

      <Bloque
        id="por-semana"
        titulo="Semana a semana"
        grafico={
          datos.porSemana.length > 0 ? (
            <Columnas columnas={datos.porSemana.map((s) => ({ clave: s.lunes, etiqueta: fechaCorta(s.lunes), valor: media(s.cifras) }))} />
          ) : undefined
        }
      >
        {datos.porSemana.length === 0 ? (
          <p className="nota">Sin datos en este periodo.</p>
        ) : (
          <TablaSerie
            titulo={`Puntualidad de la ${datos.linea} semana a semana`}
            cabecera="Semana del"
            filas={datos.porSemana.map((s) => ({
              clave: s.lunes,
              etiqueta: `${fechaCorta(s.lunes)}${s.dias < 7 ? ` (${s.dias} ${s.dias === 1 ? 'día' : 'días'})` : ''}`,
              cifras: s.cifras,
            }))}
          />
        )}
      </Bloque>

      <Bloque
        id="tramos"
        titulo="Dónde se pierde el tiempo"
        explicacion="Los tramos entre dos paradas seguidas en los que los trenes llegan con más retraso del que traían, de media. Solo los tramos con 30 pasos o más."
      >
        {datos.tramos.length === 0 ? (
          <p className="nota">Aún no hay ningún tramo con 30 pasos de trenes en este periodo.</p>
        ) : (
          <table className="tabla">
            <caption className="solo-lector">Tramos de la {datos.linea} con más retraso</caption>
            <thead>
              <tr>
                <th scope="col">Tramo</th>
                <th scope="col">Minutos perdidos de media</th>
                <th scope="col">Pierden 2 min o más</th>
                <th scope="col">Pasos</th>
              </tr>
            </thead>
            <tbody>
              {datos.tramos.map((t) => (
                <tr key={`${t.desde.stopId}-${t.hasta.stopId}`}>
                  <th scope="row">
                    {t.desde.nombre ?? t.desde.stopId} → {t.hasta.nombre ?? t.hasta.stopId}
                  </th>
                  <td className="cifra">
                    {t.mediaMinutos !== null && t.mediaMinutos > 0 ? '+' : ''}
                    {numero(t.mediaMinutos)} min
                  </td>
                  <td className="cifra">{porcentaje(t.pierden2OMas)}</td>
                  <td className="cifra">{entero(t.pasos)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Bloque>
    </>
  );
}
