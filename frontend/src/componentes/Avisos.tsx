import type { Aviso } from '../api/tipos';

const FECHA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short', timeZone: 'Europe/Madrid' });

function fecha(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : FECHA.format(d);
}

/** Avisos de Renfe (tal cual llegan en `alerts.pb`), con las líneas a las que afectan. */
export function ListaAvisos({ avisos, vacio }: { avisos: readonly Aviso[]; vacio: string }) {
  if (avisos.length === 0) return vacio ? <p className="nota">{vacio}</p> : null;
  return (
    <ul className="avisos">
      {avisos.map((a) => {
        const desde = fecha(a.inicio);
        const hasta = fecha(a.fin);
        return (
          <li key={a.id} className="aviso">
            <p>{a.texto}</p>
            <p className="aviso__meta">
              {a.lineas.length > 0 && <>Líneas: {a.lineas.join(', ')}. </>}
              {desde && <>Desde el {desde}</>}
              {hasta && <> hasta el {hasta}</>}
              {desde && '.'}
            </p>
          </li>
        );
      })}
    </ul>
  );
}
