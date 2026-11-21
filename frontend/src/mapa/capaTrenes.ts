import type { FilterSpecification, GeoJSONSource, Map as MapaLibre, MapLayerMouseEvent } from 'maplibre-gl';
import type { Feature, FeatureCollection, Point } from 'geojson';
import type { TrenMapa } from '../api/tipos';
import { Interpolador } from './interpolacion';
import { FRANJAS, categoriaRetraso, etiquetaRetraso, type CategoriaRetraso } from './retraso';

/**
 * La capa de los trenes, separada del mapa base y del resto de capas (plan, §6: «el frontend separa la capa de trenes
 * del resto»). El mapa solo la conoce por esta interfaz: la vista 3D (Three.js dentro de MapLibre) será otra
 * implementación de lo mismo.
 */
export interface CapaDeTrenes {
  /**
   * Los trenes que hay que pintar ahora (ya filtrados). `nuevoDato` es `true` si viene de un evento del directo y
   * `false` si solo ha cambiado el filtro (entonces no cuenta para medir el ritmo de las actualizaciones).
   */
  actualizar(trenes: readonly TrenMapa[], nuevoDato: boolean): void;
  /** Resalta un tren (el de la ficha abierta) o ninguno. */
  seleccionar(id: string | null): void;
  /** Resalta una línea de un núcleo (la RL4 de Rodalies, también con toda España) o ninguna. */
  destacarLinea(linea: LineaDestacada | null): void;
  /** `false` con `prefers-reduced-motion`: los trenes saltan a su posición nueva. */
  animar(si: boolean): void;
  destruir(): void;
}

/** Una línea de un núcleo: los nombres se repiten entre núcleos (hay una C1 en Madrid y otra en Valencia). */
export interface LineaDestacada {
  nucleo: number;
  linea: string;
}

/** Un filtro que no deja pasar ningún tren (ninguno tiene el id vacío). */
const NINGUNO = ['==', ['get', 'id'], ''] as FilterSpecification;

const FUENTE = 'tw-trenes';
export const CAPA_SIMBOLOS = 'tw-trenes-simbolos';
const CAPA_HALO = 'tw-trenes-halo';
/** La primera capa de los trenes: lo demás (la línea, las estaciones) va debajo. */
export const CAPA_DESTACADA = 'tw-trenes-destacada';
/** 30 fotogramas por segundo bastan para que se vea fluido y dejan respirar al móvil. */
const MS_POR_FOTOGRAMA = 33;
const ESCALA = 2;

/** Un lienzo 2D: `OffscreenCanvas` si lo hay (Safari lo tiene desde la 16.4); si no, un `<canvas>` normal. */
function lienzo(lado: number): CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D {
  if (typeof OffscreenCanvas !== 'undefined') {
    const c = new OffscreenCanvas(lado, lado).getContext('2d');
    if (c) return c;
  }
  const canvas = document.createElement('canvas');
  canvas.width = lado;
  canvas.height = lado;
  return canvas.getContext('2d')!;
}

/** Icono de un tren en marcha: un círculo con una punta que señala hacia donde va (se gira con el rumbo). */
function dibujarEnMarcha(color: string, conRumbo: boolean): ImageData {
  const lado = 26 * ESCALA;
  const c = lienzo(lado);
  const m = lado / 2;
  const r = 7 * ESCALA;
  c.lineWidth = 2 * ESCALA;
  c.strokeStyle = '#101114';
  c.fillStyle = color;
  c.beginPath();
  if (conRumbo) {
    // Gota: la punta arriba (rumbo 0 = norte; MapLibre la gira).
    c.moveTo(m, m - r - 5 * ESCALA);
    c.arc(m, m, r, -Math.PI / 2 + 0.75, -Math.PI / 2 - 0.75 + 2 * Math.PI);
    c.closePath();
  } else {
    c.arc(m, m, r, 0, 2 * Math.PI);
  }
  c.fill();
  c.stroke();
  return c.getImageData(0, 0, lado, lado);
}

/** Icono de un tren en origen («sale a las 10:25»): un cuadrado hueco, distinto de los que están en marcha. */
function dibujarEnOrigen(): ImageData {
  const lado = 18 * ESCALA;
  const c = lienzo(lado);
  c.fillStyle = '#101114';
  c.fillRect(2 * ESCALA, 2 * ESCALA, lado - 4 * ESCALA, lado - 4 * ESCALA);
  c.lineWidth = 2.5 * ESCALA;
  c.strokeStyle = '#c7d2fe';
  c.strokeRect(3 * ESCALA, 3 * ESCALA, lado - 6 * ESCALA, lado - 6 * ESCALA);
  return c.getImageData(0, 0, lado, lado);
}

function icono(tren: TrenMapa, categoria: CategoriaRetraso): string {
  if (tren.estado === 'EN_ORIGEN') return 'tw-origen';
  return tren.rumbo === null ? `tw-${categoria}-punto` : `tw-${categoria}`;
}

function registrarIconos(mapa: MapaLibre): void {
  for (const f of FRANJAS) {
    if (!mapa.hasImage(`tw-${f.categoria}`)) mapa.addImage(`tw-${f.categoria}`, dibujarEnMarcha(f.color, true), { pixelRatio: ESCALA });
    if (!mapa.hasImage(`tw-${f.categoria}-punto`))
      mapa.addImage(`tw-${f.categoria}-punto`, dibujarEnMarcha(f.color, false), { pixelRatio: ESCALA });
  }
  if (!mapa.hasImage('tw-origen')) mapa.addImage('tw-origen', dibujarEnOrigen(), { pixelRatio: ESCALA });
}

const VACIA: FeatureCollection<Point> = { type: 'FeatureCollection', features: [] };

/** La capa 2D con MapLibre: una fuente GeoJSON, un símbolo por tren y la animación con `requestAnimationFrame`. */
export class CapaTrenes2D implements CapaDeTrenes {
  private readonly mapa: MapaLibre;
  private readonly interpolador: Interpolador;
  private trenes: readonly TrenMapa[] = [];
  private fotograma: number | null = null;
  private ultimoPintado = 0;
  private readonly alPulsar: (e: MapLayerMouseEvent) => void;
  private readonly alEntrar = () => {
    this.mapa.getCanvas().style.cursor = 'pointer';
  };
  private readonly alSalir = () => {
    this.mapa.getCanvas().style.cursor = '';
  };

  constructor(mapa: MapaLibre, opciones: { animar: boolean; alPulsarTren: (id: string) => void }) {
    this.mapa = mapa;
    this.interpolador = new Interpolador(opciones.animar);
    registrarIconos(mapa);
    mapa.addSource(FUENTE, { type: 'geojson', data: VACIA });
    // Debajo, el halo del tren seleccionado y el de la línea destacada.
    mapa.addLayer({
      id: CAPA_DESTACADA,
      type: 'circle',
      source: FUENTE,
      filter: NINGUNO,
      paint: {
        'circle-radius': 13,
        'circle-color': 'rgba(142, 174, 255, 0.18)',
        'circle-stroke-color': '#8eaeff',
        'circle-stroke-width': 1.5,
      },
    });
    mapa.addLayer({
      id: CAPA_HALO,
      type: 'circle',
      source: FUENTE,
      filter: NINGUNO,
      paint: {
        'circle-radius': 17,
        'circle-color': 'rgba(255, 255, 255, 0.12)',
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 2.5,
      },
    });
    mapa.addLayer({
      id: CAPA_SIMBOLOS,
      type: 'symbol',
      source: FUENTE,
      layout: {
        'icon-image': ['get', 'icono'],
        'icon-rotate': ['get', 'rumbo'],
        'icon-rotation-alignment': 'map',
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        // Los más retrasados, encima.
        'symbol-sort-key': ['get', 'orden'],
        'text-field': ['get', 'etiqueta'],
        'text-font': ['Noto Sans Regular'],
        'text-size': 12,
        'text-offset': [1.1, 0],
        'text-anchor': 'left',
        'text-optional': true,
      },
      paint: {
        'text-color': '#ffffff',
        'text-halo-color': '#101114',
        'text-halo-width': 1.6,
      },
    });
    this.alPulsar = (e) => {
      const id = e.features?.[0]?.properties?.id;
      if (typeof id !== 'string') return;
      // Que no abra también la estación que haya debajo (CapaLinea mira esta marca).
      e.preventDefault();
      opciones.alPulsarTren(id);
    };
    mapa.on('click', CAPA_SIMBOLOS, this.alPulsar);
    mapa.on('mouseenter', CAPA_SIMBOLOS, this.alEntrar);
    mapa.on('mouseleave', CAPA_SIMBOLOS, this.alSalir);
  }

  actualizar(trenes: readonly TrenMapa[], nuevoDato: boolean): void {
    this.trenes = trenes;
    this.interpolador.sincronizar(trenes, performance.now(), nuevoDato);
    this.pintar();
  }

  seleccionar(id: string | null): void {
    this.mapa.setFilter(CAPA_HALO, ['==', ['get', 'id'], id ?? '']);
  }

  destacarLinea(destacada: LineaDestacada | null): void {
    this.mapa.setFilter(
      CAPA_DESTACADA,
      destacada
        ? ['all', ['==', ['get', 'linea'], destacada.linea], ['==', ['get', 'nucleo'], destacada.nucleo]]
        : NINGUNO,
    );
  }

  animar(si: boolean): void {
    this.interpolador.animar = si;
    if (!si) {
      this.interpolador.terminar();
      this.pintar();
    }
  }

  destruir(): void {
    if (this.fotograma !== null) cancelAnimationFrame(this.fotograma);
    this.fotograma = null;
    this.mapa.off('click', CAPA_SIMBOLOS, this.alPulsar);
    this.mapa.off('mouseenter', CAPA_SIMBOLOS, this.alEntrar);
    this.mapa.off('mouseleave', CAPA_SIMBOLOS, this.alSalir);
    for (const capa of [CAPA_SIMBOLOS, CAPA_HALO, CAPA_DESTACADA]) {
      if (this.mapa.getLayer(capa)) this.mapa.removeLayer(capa);
    }
    if (this.mapa.getSource(FUENTE)) this.mapa.removeSource(FUENTE);
  }

  /** Pinta un fotograma y, si queda algún tren a medio camino, pide el siguiente. */
  private readonly pintar = (): void => {
    if (this.fotograma !== null) cancelAnimationFrame(this.fotograma);
    this.fotograma = null;
    const ahora = performance.now();
    this.ultimoPintado = ahora;
    const features: Feature<Point>[] = [];
    for (const tren of this.trenes) {
      const posicion = this.interpolador.posicion(tren.id, ahora) ?? [tren.lon, tren.lat];
      const categoria = categoriaRetraso(tren.retrasoMin);
      features.push({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [posicion[0], posicion[1]] },
        properties: {
          id: tren.id,
          linea: tren.linea ?? '',
          nucleo: tren.nucleo,
          icono: icono(tren, categoria),
          rumbo: tren.estado === 'EN_ORIGEN' ? 0 : (tren.rumbo ?? 0),
          // «+7» si va con retraso y «?» si no se sabe: el color nunca va solo.
          etiqueta:
            tren.estado === 'EN_ORIGEN' ? '' : categoria === 'sin-dato' ? '?' : etiquetaRetraso(tren.retrasoMin),
          orden: tren.estado === 'EN_ORIGEN' ? -1 : (tren.retrasoMin ?? 0),
        },
      });
    }
    const fuente = this.mapa.getSource<GeoJSONSource>(FUENTE);
    fuente?.setData({ type: 'FeatureCollection', features });
    if (this.interpolador.enMovimiento(ahora)) this.siguienteFotograma();
  };

  private siguienteFotograma(): void {
    if (this.fotograma !== null) return;
    const esperar = (instante: number) => {
      this.fotograma = null;
      if (instante - this.ultimoPintado < MS_POR_FOTOGRAMA) {
        this.fotograma = requestAnimationFrame(esperar);
        return;
      }
      this.pintar();
    };
    this.fotograma = requestAnimationFrame(esperar);
  }
}
