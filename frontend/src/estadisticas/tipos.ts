/**
 * Tipos de la API de estadísticas (fase 6), copiados de `RespuestasEstadisticas.java` y de los ejemplos de `docs/api/`
 * (`puntualidadTodos`, `puntualidadNucleo`, `puntualidadLinea` y `puntualidadEstacion`). ADR 0025.
 *
 * Una cifra sin muestra suficiente llega con `suficiente: false` y sus valores a `null`; antes de la fecha de
 * publicación no llega ninguna (`publicacion.publicado === false`).
 */

export interface Publicacion {
  publicado: boolean;
  recogiendoDesde: string | null;
  cifrasDesde: string | null;
  texto: string;
}

export interface Periodo {
  desde: string;
  hasta: string;
  diasConDatos: number;
}

export interface Muestra {
  viajes: number;
  paradas: number;
  paradasInferidasQuitadas: number;
  paradasSinHoraQuitadas: number;
  cancelados: number | null;
}

export interface Cobertura {
  viajesProgramados: number | null;
  viajesVistos: number | null;
  porcentaje: number | null;
  paradasProgramadas: number | null;
  paradasVistas: number | null;
  porcentajeParadas: number | null;
}

export interface Porcentajes {
  menosDe3: number | null;
  de3a5: number | null;
  de6a15: number | null;
  de16OMas: number | null;
}

export interface Cifras {
  viajes: number;
  paradas: number;
  suficiente: boolean;
  mediaMinutos: number | null;
  medianaMinutos: number | null;
  p90Minutos: number | null;
  porcentajes: Porcentajes | null;
}

export interface LlegadaDestino {
  viajes: number;
  viajesEnDestino: number;
  suficiente: boolean;
  mediaMinutos: number | null;
  menosDe3: number | null;
}

export interface Franja {
  hora: number;
  cifras: Cifras;
}

export interface DiaSemana {
  dia: number;
  nombre: string;
  dias: number;
  cifras: Cifras;
}

export interface Semana {
  lunes: string;
  dias: number;
  cifras: Cifras;
}

export interface Parada {
  stopId: string;
  nombre: string | null;
}

export interface Tramo {
  desde: Parada;
  hasta: Parada;
  pasos: number;
  suficiente: boolean;
  mediaMinutos: number | null;
  pierden2OMas: number | null;
  ganan2OMas: number | null;
}

export interface LineaRanking {
  puesto: number | null;
  linea: string;
  cifras: Cifras;
  cancelados: number | null;
  cobertura: Cobertura | null;
}

export interface NucleoRanking {
  puesto: number | null;
  nucleo: number;
  nombre: string;
  cifras: Cifras;
  cancelados: number | null;
  cobertura: Cobertura | null;
}

export interface PuntualidadTodos {
  publicacion: Publicacion;
  periodo: Periodo;
  muestra: Muestra | null;
  cobertura: Cobertura | null;
  nucleos: NucleoRanking[];
  fuente: string;
}

export interface PuntualidadNucleo {
  nucleo: number;
  nombre: string;
  publicacion: Publicacion;
  periodo: Periodo;
  muestra: Muestra | null;
  cobertura: Cobertura | null;
  lineas: LineaRanking[];
  fuente: string;
}

export interface PuntualidadLinea {
  nucleo: number;
  nombreNucleo: string;
  linea: string;
  publicacion: Publicacion;
  periodo: Periodo;
  muestra: Muestra | null;
  coberturaNucleo: Cobertura | null;
  coberturaLinea: Cobertura | null;
  resumen: Cifras | null;
  llegadaDestino: LlegadaDestino | null;
  porHora: Franja[];
  porDiaSemana: DiaSemana[];
  porSemana: Semana[];
  tramos: Tramo[];
  fuente: string;
}

export interface LineaEnEstacion {
  nucleo: number;
  linea: string;
  cifras: Cifras;
}

export interface PuntualidadEstacion {
  stopId: string;
  nombre: string | null;
  publicacion: Publicacion;
  periodo: Periodo;
  muestra: Muestra | null;
  coberturaNucleo: Cobertura | null;
  resumen: Cifras | null;
  porLinea: LineaEnEstacion[];
  fuente: string;
}
