import { useEffect, useState } from 'react';

export type Consulta<T> =
  | { tipo: 'cargando' }
  | { tipo: 'lista'; datos: T }
  | { tipo: 'error'; mensaje: string; status: number | null };

/**
 * Pide algo a la API cuando cambia `clave` y cancela la petición anterior (al cambiar o al desmontar).
 * Con `clave === null` no pide nada. Con `refrescarMs`, vuelve a pedirlo cada tanto sin dejar de enseñar lo que ya
 * tenía (si el refresco falla, se queda con lo anterior). Es lo poco que hace falta aquí de TanStack Query (ADR 0022).
 */
export function useConsulta<T>(
  clave: string | null,
  pedir: (signal: AbortSignal) => Promise<T>,
  refrescarMs?: number,
): Consulta<T> {
  const [estado, setEstado] = useState<{ clave: string | null; consulta: Consulta<T> }>({
    clave: null,
    consulta: { tipo: 'cargando' },
  });

  useEffect(() => {
    if (clave === null) return;
    const control = new AbortController();
    let hayDatos = false;
    const cargar = () =>
      pedir(control.signal)
        .then((datos) => {
          hayDatos = true;
          setEstado({ clave, consulta: { tipo: 'lista', datos } });
        })
        .catch((error: unknown) => {
          if (control.signal.aborted || hayDatos) return;
          const status =
            typeof (error as { status?: unknown }).status === 'number' ? (error as { status: number }).status : null;
          const mensaje = error instanceof Error ? error.message : 'Error desconocido';
          setEstado({ clave, consulta: { tipo: 'error', mensaje, status } });
        });
    void cargar();
    const intervalo = refrescarMs ? window.setInterval(() => void cargar(), refrescarMs) : null;
    return () => {
      control.abort();
      if (intervalo !== null) window.clearInterval(intervalo);
    };
    // `pedir` cambia en cada render; lo que decide si hay que volver a pedir es la clave.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clave, refrescarMs]);

  // Mientras llega la respuesta de la clave nueva, «cargando» (y no los datos de la anterior).
  return estado.clave === clave ? estado.consulta : { tipo: 'cargando' };
}
