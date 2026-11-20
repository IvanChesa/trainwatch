import { useEffect, useId, useRef, type ReactNode } from 'react';

/**
 * El panel de las fichas: a la derecha en pantallas anchas y como hoja inferior en el móvil. No es modal (el mapa sigue
 * usable detrás). Al abrirse, el foco va a su título; con Escape o «Cerrar» se cierra y el foco vuelve a donde estaba
 * (eso lo hace `App`).
 */
export function Hoja({ titulo, alCerrar, children }: { titulo: string; alCerrar(): void; children: ReactNode }) {
  const idTitulo = useId();
  const encabezado = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    encabezado.current?.focus();
  }, [titulo]);

  useEffect(() => {
    const alPulsar = (e: KeyboardEvent) => {
      if (e.key === 'Escape') alCerrar();
    };
    document.addEventListener('keydown', alPulsar);
    return () => document.removeEventListener('keydown', alPulsar);
  }, [alCerrar]);

  return (
    <aside className="hoja" aria-labelledby={idTitulo}>
      <div className="hoja__cabecera">
        <h2 id={idTitulo} ref={encabezado} tabIndex={-1}>
          {titulo}
        </h2>
        <button type="button" className="boton boton--cerrar" onClick={alCerrar}>
          Cerrar<span className="solo-lector"> la ficha</span>
        </button>
      </div>
      <div className="hoja__cuerpo">{children}</div>
    </aside>
  );
}
