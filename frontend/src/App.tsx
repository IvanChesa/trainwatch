import { useEffect, useState } from 'react';
import { describirDirecto, obtenerEstadoDirecto, type EstadoDirecto } from './api/directo';
import { formatearHora, obtenerEstado, type EstadoBackend } from './api/estado';
import './App.css';

type Conexion =
  | { tipo: 'cargando' }
  | { tipo: 'conectado'; estado: EstadoBackend }
  | { tipo: 'sin-backend' };

type Datos = { tipo: 'cargando' } | { tipo: 'conectado'; estado: EstadoDirecto } | { tipo: 'sin-datos' };

/** Página provisional: el mapa llega en la fase 5. */
export default function App() {
  const [conexion, setConexion] = useState<Conexion>({ tipo: 'cargando' });
  const [datos, setDatos] = useState<Datos>({ tipo: 'cargando' });

  useEffect(() => {
    // Se cancela al desmontar (y en el doble montaje de StrictMode en desarrollo).
    const control = new AbortController();
    obtenerEstado(control.signal)
      .then((estado) => setConexion({ tipo: 'conectado', estado }))
      .catch(() => {
        if (!control.signal.aborted) setConexion({ tipo: 'sin-backend' });
      });
    // Fase 7: el estado de los datos en directo (la ingesta ya funciona en el servidor, aunque el mapa no esté).
    obtenerEstadoDirecto(control.signal)
      .then((estado) => setDatos({ tipo: 'conectado', estado }))
      .catch(() => {
        if (!control.signal.aborted) setDatos({ tipo: 'sin-datos' });
      });
    return () => control.abort();
  }, []);

  return (
    <>
      <main className="pagina">
        <h1>TrainWatch · en construcción</h1>
        <p className="resumen">
          Un mapa con los trenes de Cercanías y Rodalies en directo, con su retraso y estadísticas propias de
          puntualidad por línea, por hora y por día.
        </p>
        <p className="aviso" role="note">
          <strong>Proyecto personal no oficial.</strong> No tiene relación con Renfe ni con Adif.
        </p>
        <p className="backend" role="status">
          {conexion.tipo === 'cargando' && 'Consultando el backend…'}
          {conexion.tipo === 'conectado' && (
            <>
              Backend {conexion.estado.version} · {formatearHora(conexion.estado.hora)}
            </>
          )}
          {conexion.tipo === 'sin-backend' && 'Backend no disponible'}
        </p>
        <p className="datos">
          {datos.tipo === 'cargando' && 'Consultando el estado de los datos…'}
          {datos.tipo === 'conectado' && describirDirecto(datos.estado)}
          {datos.tipo === 'sin-datos' && 'Estado de los datos no disponible.'}
        </p>
      </main>
      <footer className="pie">
        <p>Datos: Renfe (CC BY 4.0)</p>
      </footer>
    </>
  );
}
