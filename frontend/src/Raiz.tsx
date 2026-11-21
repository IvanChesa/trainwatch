import { Suspense, lazy } from 'react';
import App from './App';
import { leerRuta } from './estadisticas/ruta';

// Las páginas de estadísticas (fase 6) van en su propio trozo: no cargan el mapa, y el mapa no las carga a ellas.
const PaginaEstadisticas = lazy(() => import('./estadisticas/PaginaEstadisticas'));

/** El mapa en `/` y las estadísticas en `/estadisticas/…` (ver `estadisticas/ruta.ts`). */
export default function Raiz() {
  const estadisticas = leerRuta(window.location.pathname, window.location.search);
  if (!estadisticas) return <App />;
  return (
    <Suspense
      fallback={
        <p className="cargando-pagina" role="status">
          Cargando las estadísticas…
        </p>
      }
    >
      <PaginaEstadisticas ruta={estadisticas} />
    </Suspense>
  );
}
