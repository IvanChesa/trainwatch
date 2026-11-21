import type { StyleSpecification } from 'maplibre-gl';

/**
 * El estilo oscuro de OpenFreeMap trae 47 capas pensadas para un mapa general. Para un mapa de trenes sobran
 * edificios, aeropuertos, caminos, nombres de calles y de barrios: quitarlos ahorra trabajo al móvil al crear el mapa y
 * al pintar cada fotograma (Lighthouse, fase 5). Y las vías del tren, que el estilo solo enseña desde el zoom 13, se
 * enseñan desde el 8 y un poco más claras.
 */
const SOBRAN = new Set([
  'landcover_ice_shelf',
  'landcover_glacier',
  'landcover_wood',
  'water_name',
  'building',
  'aeroway-taxiway',
  'aeroway-runway-casing',
  'aeroway-area',
  'aeroway-runway',
  'road_area_pier',
  'road_pier',
  'highway_path',
  'road_oneway',
  'road_oneway_opposite',
  'highway_name_other',
  'highway_name_motorway',
  'place_other',
  'place_suburb',
  // Las vías con traviesas (línea discontinua) necesitan otro programa de WebGL: basta la línea continua.
  'railway_transit_dashline',
  'railway_minor_dashline',
  'railway_dashline',
]);

const VIAS = new Set(['railway']);

export function aligerarEstilo(_anterior: StyleSpecification | undefined, estilo: StyleSpecification): StyleSpecification {
  return {
    ...estilo,
    layers: estilo.layers
      .filter((capa) => !SOBRAN.has(capa.id))
      .map((capa) => {
        if (!VIAS.has(capa.id) || capa.type !== 'line') return capa;
        return {
          ...capa,
          minzoom: 8,
          paint: { ...capa.paint, 'line-color': '#3d4250' },
        };
      }),
  };
}
