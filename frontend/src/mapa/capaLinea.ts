import type { GeoJSONSource, Map as MapaLibre, MapLayerMouseEvent } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';
import type { Linea } from '../api/tipos';

/** El recorrido de la línea elegida en el filtro y sus estaciones (que se pueden pulsar para abrir su ficha). */
const FUENTE_RECORRIDO = 'tw-linea-recorrido';
const FUENTE_ESTACIONES = 'tw-linea-estaciones';
const CAPA_RECORRIDO = 'tw-linea-recorrido';
export const CAPA_ESTACIONES = 'tw-linea-estaciones';

const VACIA: FeatureCollection = { type: 'FeatureCollection', features: [] };

/** Un color de línea de Renfe puede ser muy oscuro para el fondo: si lo es, se aclara. */
export function colorVisible(color: string | null | undefined): string {
  const hex = /^#?([0-9a-f]{6})$/i.exec(color ?? '')?.[1];
  if (!hex) return '#8eaeff';
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16));
  const luz = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  return luz < 0.35 ? '#8eaeff' : `#${hex}`;
}

export class CapaLinea {
  private readonly mapa: MapaLibre;
  private readonly alPulsar: (e: MapLayerMouseEvent) => void;

  constructor(mapa: MapaLibre, antesDe: string | undefined, alPulsarEstacion: (id: string, nombre: string | null) => void) {
    this.mapa = mapa;
    mapa.addSource(FUENTE_RECORRIDO, { type: 'geojson', data: VACIA });
    mapa.addSource(FUENTE_ESTACIONES, { type: 'geojson', data: VACIA });
    mapa.addLayer(
      {
        id: CAPA_RECORRIDO,
        type: 'line',
        source: FUENTE_RECORRIDO,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 3.5, 'line-opacity': 0.75 },
      },
      antesDe,
    );
    mapa.addLayer(
      {
        id: CAPA_ESTACIONES,
        type: 'circle',
        source: FUENTE_ESTACIONES,
        paint: {
          'circle-radius': 5,
          'circle-color': '#101114',
          'circle-stroke-color': ['get', 'color'],
          'circle-stroke-width': 2.5,
        },
      },
      antesDe,
    );
    this.alPulsar = (e) => {
      const id = e.features?.[0]?.properties?.id;
      const nombre = e.features?.[0]?.properties?.nombre;
      if (typeof id === 'string' && !e.defaultPrevented) alPulsarEstacion(id, typeof nombre === 'string' ? nombre : null);
    };
    mapa.on('click', CAPA_ESTACIONES, this.alPulsar);
  }

  mostrar(linea: Linea | null): void {
    const color = colorVisible(linea?.color);
    const recorrido: FeatureCollection = linea
      ? {
          type: 'FeatureCollection',
          features: linea.recorrido.features.map((f) => ({ ...f, properties: { ...f.properties, color } })),
        }
      : VACIA;
    const estaciones: FeatureCollection = linea
      ? {
          type: 'FeatureCollection',
          features: linea.paradas.map((p) => ({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
            properties: { id: p.id, nombre: p.nombre, color },
          })),
        }
      : VACIA;
    this.mapa.getSource<GeoJSONSource>(FUENTE_RECORRIDO)?.setData(recorrido);
    this.mapa.getSource<GeoJSONSource>(FUENTE_ESTACIONES)?.setData(estaciones);
  }

  destruir(): void {
    this.mapa.off('click', CAPA_ESTACIONES, this.alPulsar);
    for (const capa of [CAPA_ESTACIONES, CAPA_RECORRIDO]) if (this.mapa.getLayer(capa)) this.mapa.removeLayer(capa);
    for (const f of [FUENTE_ESTACIONES, FUENTE_RECORRIDO]) if (this.mapa.getSource(f)) this.mapa.removeSource(f);
  }
}
