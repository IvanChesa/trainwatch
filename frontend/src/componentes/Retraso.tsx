import { categoriaRetraso, franja, textoRetraso } from '../mapa/retraso';

/** El retraso con su color **y** su texto (el color nunca va solo, WCAG 1.4.1). */
export function Retraso({ minutos }: { minutos: number | null | undefined }) {
  const categoria = categoriaRetraso(minutos);
  return (
    <span className={`retraso retraso--${categoria}`}>
      <span className="retraso__punto" aria-hidden="true" style={{ background: franja(categoria).color }} />
      {textoRetraso(minutos)}
    </span>
  );
}
