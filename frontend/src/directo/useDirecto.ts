import { useEffect, useState } from 'react';
import { rutaApi } from '../api/base';
import type { TrenMapa } from '../api/tipos';
import { ConexionDirecto, type EstadoConexion } from './ConexionDirecto';

export interface Directo {
  /** Los trenes del núcleo, por id. Un objeto nuevo en cada evento (para que React vea el cambio). */
  trenes: ReadonlyMap<string, TrenMapa>;
  /** Hora local (`Date.now()`) en que llegó el último evento con trenes. */
  recibido: number | null;
  /**
   * Antigüedad del último dato de Renfe, en segundos, en el momento `antiguedadEn` (hora local). Se calcula con las
   * horas del servidor (`generado - ultimoDato` o el `antiguedadS` del latido), así que no depende del reloj del
   * navegador; para saber la de ahora se suma lo que haya pasado desde `antiguedadEn`.
   */
  antiguedadS: number | null;
  antiguedadEn: number | null;
  conexion: EstadoConexion;
  /** `true` desde que llega la primera instantánea del núcleo. */
  cargado: boolean;
}

const INICIAL: Directo = {
  trenes: new Map(),
  recibido: null,
  antiguedadS: null,
  antiguedadEn: null,
  conexion: { tipo: 'conectando' },
  cargado: false,
};

function segundosEntre(desde: string | null, hasta: string): number | null {
  if (!desde) return null;
  const s = (Date.parse(hasta) - Date.parse(desde)) / 1000;
  return Number.isFinite(s) ? Math.max(0, s) : null;
}

/** Abre el directo del núcleo y lo cierra al cambiar de núcleo o al desmontar. */
export function useDirecto(nucleo: number | null): Directo {
  const [directo, setDirecto] = useState<{ nucleo: number | null; datos: Directo }>({ nucleo: null, datos: INICIAL });

  useEffect(() => {
    if (nucleo === null) return;
    const actualizar = (cambio: (d: Directo) => Directo) =>
      setDirecto((previo) => ({ nucleo, datos: cambio(previo.nucleo === nucleo ? previo.datos : INICIAL) }));

    const conexion = new ConexionDirecto(rutaApi(`/api/directo?nucleo=${nucleo}`), {
      instantanea: (i) => {
        const ahora = Date.now();
        actualizar((d) => ({
          ...d,
          trenes: new Map(i.trenes.map((t) => [t.id, t])),
          recibido: ahora,
          antiguedadS: segundosEntre(i.ultimoDato, i.generado),
          antiguedadEn: ahora,
          cargado: true,
        }));
      },
      cambios: (c) => {
        const ahora = Date.now();
        actualizar((d) => {
          const trenes = new Map(d.trenes);
          for (const t of c.altas) trenes.set(t.id, t);
          for (const t of c.cambios) trenes.set(t.id, t);
          for (const id of c.bajas) trenes.delete(id);
          return {
            ...d,
            trenes,
            recibido: ahora,
            antiguedadS: segundosEntre(c.ultimoDato, c.generado),
            antiguedadEn: ahora,
          };
        });
      },
      latido: (l) => {
        const ahora = Date.now();
        actualizar((d) => ({ ...d, antiguedadS: l.antiguedadS, antiguedadEn: ahora }));
      },
      estado: (estado) => actualizar((d) => ({ ...d, conexion: estado })),
    });
    conexion.abrir();
    return () => conexion.cerrar();
  }, [nucleo]);

  return directo.nucleo === nucleo ? directo.datos : INICIAL;
}

/** Antigüedad del último dato ahora mismo, en segundos. */
export function antiguedadAhora(d: Pick<Directo, 'antiguedadS' | 'antiguedadEn'>, ahora: number): number | null {
  if (d.antiguedadS === null || d.antiguedadEn === null) return null;
  return d.antiguedadS + Math.max(0, (ahora - d.antiguedadEn) / 1000);
}
