import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { consultas } from './api/consultas';
import type { Aviso, Linea, Nucleo, TrenMapa } from './api/tipos';
import { useConsulta } from './api/useConsulta';
import { ListaAvisos } from './componentes/Avisos';
import { EstadoDatos } from './componentes/EstadoDatos';
import { FichaEstacion } from './componentes/FichaEstacion';
import { FichaTren } from './componentes/FichaTren';
import { Hoja } from './componentes/Hoja';
import { Leyenda } from './componentes/Leyenda';
import { SinMapa } from './componentes/SinMapa';
import { ListaTrenes } from './componentes/ListaTrenes';
import { useAhora, useReducirMovimiento } from './componentes/ganchos';
import { tituloTren } from './componentes/textos';
import { useDirecto } from './directo/useDirecto';
import { encuadreDeNucleo } from './mapa/encuadre';
import { compararLineas, seVeEnElMapa } from './mapa/retraso';
import './App.css';

// MapLibre pesa (~1 MB): va en su propio trozo y se carga después de pintar la interfaz.
const Mapa = lazy(() => import('./mapa/Mapa'));

/** Rodalies de Catalunya, el núcleo por defecto; y su RL4 (Lleida), destacada. */
export const NUCLEO_POR_DEFECTO = 51;
export const LINEA_DESTACADA = 'RL4';

type Vista = 'mapa' | 'lista';
type Seleccion =
  | { tipo: 'tren'; id: string }
  | { tipo: 'estacion'; id: string; nombre: string | null }
  | { tipo: 'avisos' }
  | null;

function leerUrl(): { nucleo: number; linea: string; vista: Vista } {
  const p = new URLSearchParams(window.location.search);
  const nucleo = Number(p.get('nucleo'));
  return {
    nucleo: Number.isInteger(nucleo) && nucleo > 0 ? nucleo : NUCLEO_POR_DEFECTO,
    linea: p.get('linea') ?? '',
    vista: p.get('vista') === 'lista' ? 'lista' : 'mapa',
  };
}

function nombreDe(nucleo: Nucleo | undefined, id: number): string {
  return nucleo?.nombre ?? (id === NUCLEO_POR_DEFECTO ? 'Rodalies de Catalunya' : 'este núcleo');
}

export default function App() {
  const [inicial] = useState(leerUrl);
  const [nucleoPedido, setNucleoId] = useState(inicial.nucleo);
  const [linea, setLinea] = useState(inicial.linea);
  const [vista, setVista] = useState<Vista>(inicial.vista);
  const [seleccion, setSeleccion] = useState<Seleccion>(null);
  const reducirMovimiento = useReducirMovimiento();
  const ahora = useAhora(1000);
  /** Dónde estaba el foco al abrir una ficha, para devolverlo al cerrarla. */
  const focoPrevio = useRef<HTMLElement | null>(null);

  const nucleos = useConsulta<Nucleo[]>('nucleos', (s) => consultas.nucleos(s), 60_000);
  const listaNucleos = useMemo(
    () => (nucleos.tipo === 'lista' ? [...nucleos.datos].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')) : []),
    [nucleos],
  );
  // Un núcleo de la URL que no existe: en cuanto se sabe, Rodalies.
  const nucleoId =
    listaNucleos.length > 0 && !listaNucleos.some((n) => n.id === nucleoPedido) ? NUCLEO_POR_DEFECTO : nucleoPedido;
  const nucleo = listaNucleos.find((n) => n.id === nucleoId);
  const directo = useDirecto(nucleoId);
  const avisos = useConsulta<Aviso[]>(`avisos:${nucleoId}`, (s) => consultas.avisos(nucleoId, s), 60_000);
  const datosLinea = useConsulta<Linea>(linea ? `linea:${nucleoId}:${linea}` : null, (s) =>
    consultas.linea(nucleoId, linea, s),
  );
  const esRodalies = nucleoId === NUCLEO_POR_DEFECTO;

  // La URL guarda el núcleo, la línea y la vista (para compartir un enlace; sin cookies).
  useEffect(() => {
    const p = new URLSearchParams();
    if (nucleoId !== NUCLEO_POR_DEFECTO) p.set('nucleo', String(nucleoId));
    if (linea) p.set('linea', linea);
    if (vista === 'lista') p.set('vista', 'lista');
    const consulta = p.toString();
    window.history.replaceState(null, '', `${window.location.pathname}${consulta ? `?${consulta}` : ''}`);
  }, [nucleoId, linea, vista]);

  const todos = useMemo(() => [...directo.trenes.values()], [directo.trenes]);
  const lineas = useMemo(() => {
    const conjunto = new Set(todos.map((t) => t.linea).filter((l): l is string => !!l));
    if (linea) conjunto.add(linea);
    if (esRodalies) conjunto.add(LINEA_DESTACADA);
    return [...conjunto].sort((a, b) => {
      if (esRodalies && a === LINEA_DESTACADA) return -1;
      if (esRodalies && b === LINEA_DESTACADA) return 1;
      return compararLineas(a, b);
    });
  }, [todos, linea, esRodalies]);
  const filtrados = useMemo(() => (linea ? todos.filter((t) => t.linea === linea) : todos), [todos, linea]);
  const enMapa = useMemo(() => filtrados.filter(seVeEnElMapa), [filtrados]);
  const enLista = useMemo(() => filtrados.filter((t) => t.estado !== 'TERMINADO'), [filtrados]);
  const enMarcha = enMapa.filter((t) => t.estado === 'EN_MARCHA').length;
  const enOrigen = enMapa.length - enMarcha;

  const abrir = useCallback((nueva: Seleccion) => {
    // Solo se guarda el foco al abrir la primera ficha (al pasar de un tren a una estación, se conserva el de antes).
    setSeleccion((previa) => {
      if (previa === null && document.activeElement instanceof HTMLElement) focoPrevio.current = document.activeElement;
      return nueva;
    });
  }, []);
  const cerrar = useCallback(() => {
    setSeleccion(null);
    const foco = focoPrevio.current;
    focoPrevio.current = null;
    // Si el elemento ya no está (la lista cambió), el foco va al principio del contenido.
    window.setTimeout(() => {
      if (foco && document.body.contains(foco)) foco.focus();
      else document.getElementById('contenido')?.focus();
    });
  }, []);
  const abrirTren = useCallback((id: string) => abrir({ tipo: 'tren', id }), [abrir]);
  const abrirEstacion = useCallback(
    (id: string, nombre: string | null = null) => abrir({ tipo: 'estacion', id, nombre }),
    [abrir],
  );

  const cambiarNucleo = (id: number) => {
    setNucleoId(id);
    setLinea('');
    setSeleccion(null);
  };

  const trenSeleccionado: TrenMapa | undefined =
    seleccion?.tipo === 'tren' ? directo.trenes.get(seleccion.id) : undefined;
  const avisosVisibles =
    avisos.tipo === 'lista' ? avisos.datos.filter((a) => !linea || a.lineas.length === 0 || a.lineas.includes(linea)) : [];

  const nombreNucleo = nombreDe(nucleo, nucleoId);
  const resumen = !directo.cargado
    ? `Cargando los trenes de ${nombreNucleo}…`
    : `${enMarcha} ${enMarcha === 1 ? 'tren' : 'trenes'} en marcha y ${enOrigen} en origen${linea ? ` en la ${linea}` : ''}`;
  const descripcionMapa = `Mapa de ${nombreNucleo}: ${resumen}. Para consultarlos con el teclado, usa la vista de lista.`;

  let tituloHoja = '';
  if (seleccion?.tipo === 'tren') tituloHoja = tituloTren(trenSeleccionado ?? undefined);
  else if (seleccion?.tipo === 'estacion') tituloHoja = seleccion.nombre ?? 'Estación';
  else if (seleccion?.tipo === 'avisos') tituloHoja = linea ? `Avisos de la ${linea}` : `Avisos de ${nombreNucleo}`;

  return (
    <div className={`app app--${vista}${seleccion ? ' app--con-hoja' : ''}`}>
      <a className="saltar" href="#contenido" onClick={() => setVista('lista')}>
        Ir a la lista de trenes
      </a>
      <header className="panel">
        <div className="marca">
          <h1>
            TrainWatch <span className="marca__sub">Cercanías y Rodalies en directo</span>
          </h1>
          <p className="no-oficial" role="note">
            Proyecto personal <strong>no oficial</strong>, sin relación con Renfe ni con Adif.
          </p>
        </div>
        <EstadoDatos directo={directo} ahora={ahora} />

        <div className="controles">
          <label className="campo">
            <span>Núcleo</span>
            <select value={nucleoId} onChange={(e) => cambiarNucleo(Number(e.target.value))}>
              {listaNucleos.length === 0 && <option value={nucleoId}>Cargando los núcleos…</option>}
              {listaNucleos.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.nombre}
                  {n.estado === 'EN_DIRECTO' ? '' : ` (${n.estadoTexto})`}
                </option>
              ))}
            </select>
          </label>
          <label className="campo">
            <span>Línea</span>
            <select
              value={linea}
              onChange={(e) => {
                setLinea(e.target.value);
                if (seleccion?.tipo === 'avisos') return;
                setSeleccion(null);
              }}
            >
              <option value="">Todas las líneas</option>
              {lineas.map((l) => (
                <option key={l} value={l}>
                  {esRodalies && l === LINEA_DESTACADA ? `${l} (Lleida) ★` : l}
                </option>
              ))}
            </select>
          </label>
        </div>

        {nucleo && nucleo.estado !== 'EN_DIRECTO' && (
          <p className="aviso-nucleo">
            {nucleo.estado === 'SIN_DATOS_EN_DIRECTO'
              ? `${nucleo.nombre}: sin datos en directo. El horario tiene trenes en marcha, pero Renfe no envía sus posiciones ahora.`
              : `${nucleo.nombre}: sin servicio ahora según el horario.`}
          </p>
        )}
        {nucleo?.cobertura?.porcentaje != null && nucleo.cobertura.porcentaje < 80 && (
          <p className="nota cobertura">
            Cobertura del directo: el {nucleo.cobertura.porcentaje.toLocaleString('es-ES')} % de los trenes en marcha
            según el horario.
          </p>
        )}

        <div className="acciones">
          <div className="conmutador" role="group" aria-label="Vista">
            <button type="button" aria-pressed={vista === 'mapa'} onClick={() => setVista('mapa')}>
              Mapa
            </button>
            <button type="button" aria-pressed={vista === 'lista'} onClick={() => setVista('lista')}>
              Lista
            </button>
          </div>
          <button type="button" className="boton" onClick={() => abrir({ tipo: 'avisos' })}>
            Avisos{avisos.tipo === 'lista' ? ` (${avisosVisibles.length})` : ''}
          </button>
          {esRodalies && (
            // Siempre está (si desapareciera al pulsarlo, el foco se perdería): pulsado, filtra la RL4.
            <button
              type="button"
              className="destacada"
              aria-pressed={linea === LINEA_DESTACADA}
              onClick={() => setLinea(linea === LINEA_DESTACADA ? '' : LINEA_DESTACADA)}
            >
              <span aria-hidden="true">★</span> RL4 Lleida
            </button>
          )}
        </div>
        <p className="resumen" aria-live="off">
          {resumen}
        </p>
      </header>

      <main id="contenido" className="contenido" tabIndex={-1}>
        {/* Oculto con `visibility` y no con `hidden`: así el mapa conserva su tamaño y encuadra bien aunque no se vea. */}
        <div className={`mapa-marco${vista === 'mapa' ? '' : ' mapa-marco--oculto'}`}>
          <SinMapa alFallar={() => setVista('lista')}>
            <Suspense fallback={<p className="nota mapa-cargando">Cargando el mapa…</p>}>
            <Mapa
              encuadre={nucleo ? encuadreDeNucleo(nucleo) : null}
              trenes={enMapa}
              recibido={directo.recibido}
              linea={datosLinea.tipo === 'lista' ? datosLinea.datos : null}
              seleccionado={seleccion?.tipo === 'tren' ? seleccion.id : null}
              destacada={esRodalies ? LINEA_DESTACADA : null}
              reducirMovimiento={reducirMovimiento}
              descripcion={descripcionMapa}
              alPulsarTren={abrirTren}
              alPulsarEstacion={abrirEstacion}
            />
            </Suspense>
          </SinMapa>
          <Leyenda />
        </div>
        {vista === 'lista' && (
          <ListaTrenes
            trenes={enLista}
            titulo={`Trenes de ${nombreNucleo}${linea ? `, ${linea}` : ''}`}
            seleccionado={seleccion?.tipo === 'tren' ? seleccion.id : null}
            alAbrir={abrirTren}
          />
        )}
      </main>

      {seleccion && (
        <Hoja
          clave={seleccion.tipo === 'avisos' ? 'avisos' : `${seleccion.tipo}:${seleccion.id}`}
          titulo={tituloHoja}
          alCerrar={cerrar}
        >
          {seleccion.tipo === 'tren' && (
            <FichaTren id={seleccion.id} enDirecto={trenSeleccionado} alAbrirEstacion={abrirEstacion} />
          )}
          {seleccion.tipo === 'estacion' && (
            <FichaEstacion
              id={seleccion.id}
              alAbrirTren={abrirTren}
              alConocerNombre={(nombre) =>
                setSeleccion((s) => (s?.tipo === 'estacion' && s.id === seleccion.id ? { ...s, nombre } : s))
              }
            />
          )}
          {seleccion.tipo === 'avisos' &&
            (avisos.tipo === 'cargando' ? (
              <p className="nota">Cargando los avisos…</p>
            ) : avisos.tipo === 'error' ? (
              <p className="nota">No se han podido cargar los avisos.</p>
            ) : (
              <ListaAvisos avisos={avisosVisibles} vacio="No hay avisos ahora." />
            ))}
        </Hoja>
      )}

      <footer className="creditos">
        <a href="https://openfreemap.org" target="_blank" rel="noopener noreferrer">
          OpenFreeMap
        </a>{' '}
        ·{' '}
        <a href="https://www.openmaptiles.org/" target="_blank" rel="noopener noreferrer">
          © OpenMapTiles
        </a>{' '}
        ·{' '}
        <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">
          © OpenStreetMap
        </a>{' '}
        · Datos: Renfe (CC BY 4.0) · Proyecto no oficial
      </footer>
    </div>
  );
}
