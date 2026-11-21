import { useEffect, useRef, useState } from 'react';
import { Map as MapaLibre, NavigationControl, setWorkerUrl } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// El worker de MapLibre 6 es un módulo aparte: Vite lo publica como un fichero más del mismo origen (CSP `worker-src`).
import urlWorker from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { Linea, TrenMapa } from '../api/tipos';
import { CapaLinea } from './capaLinea';
import { movimientoEncuadre, type Encuadre } from './encuadre';
import { aligerarEstilo } from './estiloBase';
import { CAPA_DESTACADA, CapaTrenes2D, type CapaDeTrenes, type LineaDestacada } from './capaTrenes';

setWorkerUrl(urlWorker);

/** Estilo oscuro gratuito de OpenFreeMap, sin clave (datos de OpenStreetMap, esquema de OpenMapTiles). */
export const ESTILO_MAPA = 'https://tiles.openfreemap.org/styles/dark';

const TEXTOS: Record<string, string> = {
  'Map.Title': 'Mapa',
  'NavigationControl.ZoomIn': 'Acercar',
  'NavigationControl.ZoomOut': 'Alejar',
  'NavigationControl.ResetBearing': 'Orientar al norte',
  'AttributionControl.ToggleAttribution': 'Ver u ocultar las atribuciones',
  'CooperativeGesturesHandler.WindowsHelpText': 'Usa Ctrl + rueda para acercar el mapa',
  'CooperativeGesturesHandler.MacHelpText': 'Usa ⌘ + rueda para acercar el mapa',
  'CooperativeGesturesHandler.MobileHelpText': 'Usa dos dedos para mover el mapa',
};

/** Si OpenFreeMap no responde: un fondo liso, para que los trenes se sigan viendo. */
const ESTILO_RESERVA = {
  version: 8 as const,
  sources: {},
  layers: [{ id: 'fondo', type: 'background' as const, paint: { 'background-color': '#16181c' } }],
};

/** Cuánto del mapa tapa la hoja inferior del móvil (el 50 % de la altura de la ventana, desde abajo). */
function hojaMovil(contenedor: HTMLElement): number {
  const abajo = contenedor.getBoundingClientRect().bottom;
  return Math.round(window.innerHeight * 0.5 - (window.innerHeight - abajo));
}

export interface PropsMapa {
  /** Lo que hay que encuadrar (un núcleo o toda España); `null` mientras no se sabe. */
  encuadre: Encuadre | null;
  trenes: readonly TrenMapa[];
  /** Cuándo llegó el último evento del directo: solo entonces cuenta para el ritmo del movimiento (no al filtrar). */
  recibido: number | null;
  linea: Linea | null;
  seleccionado: string | null;
  /** La línea que se resalta (la RL4 de Rodalies) o ninguna. */
  destacada: LineaDestacada | null;
  reducirMovimiento: boolean;
  /** Texto del mapa para los lectores de pantalla (cuántos trenes hay y dónde está la lista). */
  descripcion: string;
  alPulsarTren(id: string): void;
  alPulsarEstacion(id: string, nombre: string | null): void;
}

/**
 * El mapa: MapLibre es una librería **imperativa** (crea su propio canvas y se cambia con métodos), así que React solo
 * le da un `<div>` y la maneja desde efectos: uno la crea y la destruye, y los demás le pasan cada prop cuando cambia.
 * Las callbacks van en una ref para que el mapa no se rehaga cuando cambian.
 */
export default function Mapa(props: PropsMapa) {
  const contenedor = useRef<HTMLDivElement>(null);
  const mapa = useRef<MapaLibre | null>(null);
  const capaTrenes = useRef<CapaDeTrenes | null>(null);
  const capaLinea = useRef<CapaLinea | null>(null);
  /** Si el mapa ya se ha encuadrado una vez (el primer encuadre va sin animación). */
  const encuadrado = useRef(false);
  const [listo, setListo] = useState(false);
  const [sinFondo, setSinFondo] = useState(false);
  const ultimas = useRef(props);
  ultimas.current = props;

  // Crear el mapa una vez (y destruirlo al desmontar).
  useEffect(() => {
    if (!contenedor.current) return;
    const { encuadre, reducirMovimiento } = ultimas.current;
    const m = new MapaLibre({
      container: contenedor.current,
      // Un primer sitio cualquiera: el efecto del encuadre lo corrige enseguida, sin animación.
      center: encuadre?.centro ? [encuadre.centro.lon, encuadre.centro.lat] : [2.17, 41.39],
      zoom: encuadre?.zoom ?? 8,
      attributionControl: false,
      locale: TEXTOS,
      dragRotate: false,
      pitchWithRotate: false,
      maxPitch: 0,
      fadeDuration: reducirMovimiento ? 0 : 300,
    });
    m.setStyle(ESTILO_MAPA, { transformStyle: aligerarEstilo });
    m.touchZoomRotate.disableRotation();
    // El estilo de OpenFreeMap pide algún icono que no trae su sprite: uno vacío evita el aviso en la consola.
    m.setMissingStyleImageResolver((id) => {
      if (!m.hasImage(id)) m.addImage(id, { width: 1, height: 1, data: new Uint8Array(4) });
    });
    m.addControl(new NavigationControl({ showCompass: false }), 'bottom-right');
    // El texto del lienzo, en vez del «Mapa» genérico, lo pone el efecto de `descripcion`.
    mapa.current = m;
    // Para las pruebas e2e (Playwright marca `navigator.webdriver`): leer posiciones del mapa desde la prueba.
    if (navigator.webdriver) (window as unknown as { __twMapa?: MapaLibre }).__twMapa = m;

    // Si el estilo de OpenFreeMap no carga, MapLibre no llega a `load`: se cambia por un fondo liso.
    let conReserva = false;
    m.on('error', () => {
      if (conReserva || m.isStyleLoaded()) return;
      conReserva = true;
      setSinFondo(true);
      m.setStyle(ESTILO_RESERVA);
    });

    m.on('load', () => {
      capaTrenes.current = new CapaTrenes2D(m, {
        animar: !ultimas.current.reducirMovimiento,
        alPulsarTren: (id) => ultimas.current.alPulsarTren(id),
      });
      capaLinea.current = new CapaLinea(m, CAPA_DESTACADA, (id, nombre) => ultimas.current.alPulsarEstacion(id, nombre));
      setListo(true);
    });
    return () => {
      capaTrenes.current?.destruir();
      capaLinea.current?.destruir();
      capaTrenes.current = null;
      capaLinea.current = null;
      m.remove();
      mapa.current = null;
      encuadrado.current = false;
      setListo(false);
    };
  }, []);

  // Encuadrar el núcleo (o toda España) cuando cambia: el primero, sin animación; los demás, con un vuelo corto.
  const { encuadre, reducirMovimiento } = props;
  useEffect(() => {
    const m = mapa.current;
    if (!m || !encuadre) return;
    const encuadrar = () => {
      const movimiento = movimientoEncuadre(encuadre, m.getContainer().clientWidth, {
        reducirMovimiento: ultimas.current.reducirMovimiento,
        primero: !encuadrado.current,
      });
      encuadrado.current = true;
      if (movimiento?.tipo === 'limites') m.fitBounds(movimiento.limites, movimiento.opciones);
      else if (movimiento?.tipo === 'centro') m.jumpTo({ center: movimiento.centro, zoom: movimiento.zoom });
    };
    // Con el contenedor sin tamaño todavía, MapLibre no sabría calcular el zoom: se espera a que lo tenga.
    const { clientWidth, clientHeight } = m.getContainer();
    if (clientWidth > 0 && clientHeight > 0) {
      encuadrar();
      return;
    }
    m.once('resize', encuadrar);
    return () => {
      m.off('resize', encuadrar);
    };
    // Solo al cambiar lo que hay que encuadrar, no al refrescar los núcleos ni si cambia el movimiento reducido.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [encuadre?.clave]);

  const ultimoRecibido = useRef<number | null>(null);
  useEffect(() => {
    if (!listo) return;
    const nuevo = props.recibido !== ultimoRecibido.current;
    ultimoRecibido.current = props.recibido;
    capaTrenes.current?.actualizar(props.trenes, nuevo);
  }, [listo, props.trenes, props.recibido]);

  useEffect(() => {
    if (listo) capaTrenes.current?.seleccionar(props.seleccionado);
  }, [listo, props.seleccionado]);

  // Al abrir la ficha de un tren, el mapa se acerca a él (sin tapar el panel ni la ficha).
  useEffect(() => {
    const m = mapa.current;
    const tren = ultimas.current.trenes.find((t) => t.id === props.seleccionado);
    if (!listo || !m || !tren) return;
    const ancho = m.getContainer().clientWidth >= 768;
    m.easeTo({
      center: [tren.lon, tren.lat],
      zoom: Math.max(m.getZoom(), 11),
      // En el móvil, la hoja ocupa la mitad de abajo de la pantalla (App.css): el tren va justo encima.
      padding: ancho
        ? { top: 20, bottom: 40, left: 360, right: 410 }
        : { top: 20, bottom: Math.max(20, hojaMovil(m.getContainer()) + 20), left: 20, right: 20 },
      animate: !ultimas.current.reducirMovimiento,
      duration: 700,
    });
  }, [listo, props.seleccionado]);

  const destacadaNucleo = props.destacada?.nucleo ?? null;
  const destacadaLinea = props.destacada?.linea ?? null;
  useEffect(() => {
    if (!listo) return;
    capaTrenes.current?.destacarLinea(
      destacadaNucleo !== null && destacadaLinea !== null ? { nucleo: destacadaNucleo, linea: destacadaLinea } : null,
    );
  }, [listo, destacadaNucleo, destacadaLinea]);

  useEffect(() => {
    if (listo) capaTrenes.current?.animar(!reducirMovimiento);
  }, [listo, reducirMovimiento]);

  useEffect(() => {
    if (listo) capaLinea.current?.mostrar(props.linea);
  }, [listo, props.linea]);

  useEffect(() => {
    mapa.current?.getCanvas().setAttribute('aria-label', props.descripcion);
  }, [props.descripcion, listo]);

  return (
    <>
      <div ref={contenedor} className="mapa" data-listo={listo ? 'si' : 'no'} />
      {sinFondo && (
        <p className="aviso-mapa" role="status">
          No se ha podido cargar el mapa de fondo: los trenes se ven sobre un fondo liso.
        </p>
      )}
    </>
  );
}
