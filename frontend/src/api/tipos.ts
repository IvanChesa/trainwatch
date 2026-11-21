/**
 * Tipos de la API, copiados del contrato de la fase 4 (`docs/api/` y los records de `Respuestas.java`).
 * Horas en ISO 8601 con el desfase de Madrid; retrasos en minutos enteros; lo que no se sabe, `null`.
 */

import type { FeatureCollection } from 'geojson';

export type EstadoTren = 'EN_ORIGEN' | 'EN_MARCHA' | 'TERMINADO' | 'CANCELADO';

export type EstadoNucleo = 'EN_DIRECTO' | 'SIN_DATOS_EN_DIRECTO' | 'SIN_SERVICIO';

export interface Punto {
  lat: number;
  lon: number;
}

export interface Nucleo {
  id: number;
  nombre: string;
  /** `null` en los núcleos con trenes en el feed pero sin horario. */
  centro: Punto | null;
  zoom: number | null;
  limites: { latMin: number; lonMin: number; latMax: number; lonMax: number } | null;
  estado: EstadoNucleo | string;
  estadoTexto: string;
  trenesEnDirecto: number;
  cobertura: { enMarchaHorario: number; enMarchaVistos: number; porcentaje: number | null } | null;
}

export interface ProximaParada {
  id: string;
  nombre: string;
  llegadaProgramada: string | null;
  llegadaEstimada: string | null;
}

/** Un tren tal como sale en el mapa (`GET /api/nucleos/{id}/trenes` y el directo). */
export interface TrenMapa {
  id: string;
  linea: string | null;
  nucleo: number;
  estado: EstadoTren;
  destino: string | null;
  sinHorario: boolean;
  lat: number;
  lon: number;
  fuentePosicion: 'RECORRIDO' | 'FEED' | string;
  rumbo: number | null;
  retrasoMin: number | null;
  proximaParada: ProximaParada | null;
  salida: { programada: string | null; estimada: string | null } | null;
  actualizado: string;
}

export interface Instantanea {
  secuencia: number;
  nucleo: number;
  generado: string;
  ultimoDato: string | null;
  trenes: TrenMapa[];
}

export interface Cambios {
  secuencia: number;
  nucleo: number;
  generado: string;
  ultimoDato: string | null;
  altas: TrenMapa[];
  cambios: TrenMapa[];
  bajas: string[];
}

export interface Latido {
  instante: string;
  ultimoDato: string | null;
  antiguedadS: number | null;
}

export type Situacion = 'PASADA' | 'ACTUAL' | 'PROXIMA' | 'PENDIENTE';

export interface ParadaTren {
  secuencia: number;
  id: string;
  nombre: string;
  lat: number | null;
  lon: number | null;
  llegadaProgramada: string | null;
  salidaProgramada: string | null;
  llegadaEstimada: string | null;
  situacion: Situacion | string;
  retrasoFinalMin: number | null;
  inferido: boolean | null;
}

export interface Aviso {
  id: string;
  texto: string;
  inicio: string | null;
  fin: string | null;
  nucleos: number[];
  lineas: string[];
  estaciones: string[];
}

export interface PuntoEvolucion {
  secuencia: number;
  parada: string;
  nombre: string;
  retrasoMin: number;
  inferido: boolean;
  llegadaProgramada: string | null;
  registrado: string;
}

export interface FichaTren {
  id: string;
  viaje: string | null;
  vehiculo: string | null;
  linea: string | null;
  nucleo: number;
  nombreNucleo: string | null;
  estado: EstadoTren;
  sinHorario: boolean;
  fechaServicio: string | null;
  origen: string | null;
  destino: string | null;
  retrasoMin: number | null;
  lat: number;
  lon: number;
  fuentePosicion: string;
  rumbo: number | null;
  actualizado: string;
  paradas: ParadaTren[];
  evolucion: PuntoEvolucion[];
  avisos: Aviso[];
}

export interface LineaEstacion {
  nucleo: number;
  linea: string;
  color: string | null;
  colorTexto: string | null;
}

export interface ProximoTren {
  id: string;
  linea: string | null;
  nucleo: number;
  destino: string | null;
  salidaProgramada: string | null;
  salidaEstimada: string | null;
  retrasoMin: number | null;
  enDirecto: boolean;
  estado: EstadoTren | null;
}

export interface FichaEstacion {
  id: string;
  nombre: string;
  lat: number;
  lon: number;
  lineas: LineaEstacion[];
  proximos: ProximoTren[];
  avisos: Aviso[];
  consultado: string;
}

export interface Linea {
  nucleo: number;
  nombreNucleo: string;
  linea: string;
  nombreLargo: string | null;
  color: string | null;
  colorTexto: string | null;
  rutas: string[];
  recorrido: FeatureCollection;
  paradas: { id: string; nombre: string; lat: number; lon: number }[];
  avisos: Aviso[];
}
