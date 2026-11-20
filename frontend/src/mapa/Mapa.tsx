import { useEffect, useRef, useState } from 'react';
import { Map as MapaLibre, NavigationControl, setWorkerUrl } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// El worker de MapLibre 6 es un módulo aparte: Vite lo publica como un fichero más del mismo origen (CSP `worker-src`).
import urlWorker from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { Linea, Nucleo, TrenMapa } from '../api/tipos';
import { CapaLinea } from './capaLinea';
import { aligerarEstilo } from './estiloBase';
import { CAPA_DESTACADA, CapaTrenes2D, type CapaDeTrenes } from './capaTrenes';

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

export interface PropsMapa {
  nucleo: Nucleo | null;
  trenes: readonly TrenMapa[];
  linea: Linea | null;
  seleccionado: string | null;
  destacada: string | null;
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
  const [listo, setListo] = useState(false);
  const ultimas = useRef(props);
  ultimas.current = props;

  // Crear el mapa una vez (y destruirlo al desmontar).
  useEffect(() => {
    if (!contenedor.current) return;
    const { nucleo, reducirMovimiento } = ultimas.current;
    const m = new MapaLibre({
      container: contenedor.current,
      center: nucleo?.centro ? [nucleo.centro.lon, nucleo.centro.lat] : [2.17, 41.39],
      zoom: nucleo?.zoom ?? 8,
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
      setListo(false);
    };
  }, []);

  // Encuadrar el núcleo cuando cambia.
  const { nucleo, reducirMovimiento } = props;
  useEffect(() => {
    const m = mapa.current;
    if (!m || !nucleo) return;
    if (nucleo.limites) {
      const { lonMin, latMin, lonMax, latMax } = nucleo.limites;
      m.fitBounds(
        [
          [lonMin, latMin],
          [lonMax, latMax],
        ],
        // En pantallas anchas, el panel de control tapa la izquierda del mapa.
        {
          padding: m.getContainer().clientWidth >= 768 ? { top: 30, bottom: 50, left: 370, right: 30 } : 24,
          animate: !reducirMovimiento,
          duration: 800,
        },
      );
    } else if (nucleo.centro) {
      m.jumpTo({ center: [nucleo.centro.lon, nucleo.centro.lat], zoom: nucleo.zoom ?? 9 });
    }
    // Solo al cambiar de núcleo, no si cambia el movimiento reducido.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nucleo?.id]);

  useEffect(() => {
    if (listo) capaTrenes.current?.actualizar(props.trenes);
  }, [listo, props.trenes]);

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
      padding: ancho ? { top: 20, bottom: 40, left: 360, right: 410 } : { top: 20, bottom: 300, left: 20, right: 20 },
      animate: !ultimas.current.reducirMovimiento,
      duration: 700,
    });
  }, [listo, props.seleccionado]);

  useEffect(() => {
    if (listo) capaTrenes.current?.destacarLinea(props.destacada);
  }, [listo, props.destacada]);

  useEffect(() => {
    if (listo) capaTrenes.current?.animar(!reducirMovimiento);
  }, [listo, reducirMovimiento]);

  useEffect(() => {
    if (listo) capaLinea.current?.mostrar(props.linea);
  }, [listo, props.linea]);

  useEffect(() => {
    mapa.current?.getCanvas().setAttribute('aria-label', props.descripcion);
  }, [props.descripcion, listo]);

  return <div ref={contenedor} className="mapa" data-listo={listo ? 'si' : 'no'} />;
}
