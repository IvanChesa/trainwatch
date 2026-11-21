import type { Cifras, PuntualidadLinea, PuntualidadNucleo, PuntualidadTodos } from '../estadisticas/tipos';

/**
 * Datos de prueba de las estadísticas con muestra suficiente (los ejemplos de `docs/api/` son de unas horas de datos
 * y casi todo sale «insuficiente»). Mismo formato que la API.
 */

export function cifras(media: number, menosDe3: number, viajes = 120): Cifras {
  const resto = Math.round((100 - menosDe3) * 10) / 10;
  return {
    viajes,
    paradas: viajes * 9,
    suficiente: true,
    mediaMinutos: media,
    medianaMinutos: Math.round(media),
    p90Minutos: Math.round(media * 3),
    porcentajes: { menosDe3, de3a5: resto / 2, de6a15: resto / 4, de16OMas: resto / 4 },
  };
}

export const INSUFICIENTE: Cifras = {
  viajes: 12,
  paradas: 40,
  suficiente: false,
  mediaMinutos: null,
  medianaMinutos: null,
  p90Minutos: null,
  porcentajes: null,
};

const PUBLICADO = {
  publicado: true,
  recogiendoDesde: '2026-11-21',
  cifrasDesde: '2026-12-19',
  texto: 'Datos recogidos desde el 21-11-2026.',
  muestraMinima: 30,
};
const PERIODO = { desde: '2026-11-21', hasta: '2026-12-18', diasConDatos: 28 };
const MUESTRA = { viajes: 1843, paradas: 21904, paradasInferidasQuitadas: 412, paradasSinHoraQuitadas: 0, cancelados: 3 };
const COBERTURA = {
  viajesProgramados: 2120,
  viajesVistos: 1890,
  porcentaje: 89.2,
  paradasProgramadas: 30410,
  paradasVistas: 24800,
  porcentajeParadas: 81.6,
};
const FUENTE = 'Datos: Renfe (CC BY 4.0).';

export const LINEA: PuntualidadLinea = {
  nucleo: 51,
  nombreNucleo: 'Rodalies de Catalunya',
  linea: 'R2N',
  publicacion: PUBLICADO,
  periodo: PERIODO,
  muestra: MUESTRA,
  coberturaNucleo: COBERTURA,
  coberturaLinea: { ...COBERTURA, porcentaje: 45.5 },
  resumen: cifras(2.4, 78.4, 1843),
  llegadaDestino: { viajes: 1843, viajesEnDestino: 1520, suficiente: true, mediaMinutos: 3.1, menosDe3: 71.5 },
  porHora: [
    { hora: 8, cifras: cifras(4.1, 61.2) },
    { hora: 7, cifras: cifras(2.2, 80) },
    { hora: 23, cifras: INSUFICIENTE },
  ],
  porDiaSemana: [
    { dia: 1, nombre: 'lunes', dias: 4, cifras: cifras(3, 70) },
    { dia: 7, nombre: 'domingo', dias: 4, cifras: INSUFICIENTE },
  ],
  porSemana: [
    { lunes: '2026-11-16', dias: 2, cifras: cifras(2, 82) },
    { lunes: '2026-11-23', dias: 7, cifras: cifras(2.6, 77) },
  ],
  tramos: [
    {
      desde: { stopId: '79009', nombre: 'Granollers Centre' },
      hasta: { stopId: '79011', nombre: 'Les Franqueses' },
      pasos: 240,
      suficiente: true,
      mediaMinutos: 1.3,
      pierden2OMas: 24.6,
      ganan2OMas: 2.1,
    },
  ],
  fuente: FUENTE,
};

export const NUCLEO: PuntualidadNucleo = {
  nucleo: 51,
  nombre: 'Rodalies de Catalunya',
  publicacion: PUBLICADO,
  periodo: PERIODO,
  muestra: MUESTRA,
  cobertura: { ...COBERTURA, porcentaje: 52 },
  lineas: [
    { puesto: 1, linea: 'R3', cifras: cifras(1.2, 90.5), cancelados: 0, cobertura: COBERTURA },
    { puesto: 2, linea: 'R2N', cifras: cifras(2.4, 78.4), cancelados: 3, cobertura: COBERTURA },
    { puesto: null, linea: 'RL4', cifras: INSUFICIENTE, cancelados: 0, cobertura: COBERTURA },
  ],
  fuente: FUENTE,
};

export const TODOS: PuntualidadTodos = {
  publicacion: PUBLICADO,
  periodo: PERIODO,
  muestra: MUESTRA,
  cobertura: COBERTURA,
  nucleos: [
    { puesto: 1, nucleo: 40, nombre: 'Valencia', cifras: cifras(1.5, 88), cancelados: 1, cobertura: COBERTURA },
    { puesto: 2, nucleo: 51, nombre: 'Rodalies de Catalunya', cifras: cifras(3.2, 70.1), cancelados: 4, cobertura: COBERTURA },
  ],
  fuente: FUENTE,
};

export const SIN_PUBLICAR = {
  publicado: false,
  recogiendoDesde: '2026-11-21',
  cifrasDesde: '2026-12-19',
  texto: 'Recogiendo datos desde el 21-11-2026; cifras a partir del 19-12-2026.',
  muestraMinima: 30,
};
