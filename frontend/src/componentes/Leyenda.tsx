import { useState } from 'react';
import { FRANJAS } from '../mapa/retraso';

/** La leyenda del mapa: forma, color **y** texto de cada franja de retraso, y el símbolo de los trenes en origen. */
export function Leyenda() {
  const [abierta, setAbierta] = useState(() => window.matchMedia?.('(min-width: 768px)').matches ?? true);
  return (
    <section className="leyenda" aria-labelledby="leyenda-titulo">
      <h2 id="leyenda-titulo" className="leyenda__titulo">
        <button type="button" aria-expanded={abierta} aria-controls="leyenda-contenido" onClick={() => setAbierta(!abierta)}>
          Leyenda
        </button>
      </h2>
      <div id="leyenda-contenido" hidden={!abierta}>
        <ul className="leyenda__lista">
          {FRANJAS.map((f) => (
            <li key={f.categoria}>
              <span className="simbolo simbolo--tren" aria-hidden="true" style={{ background: f.color }} />
              {f.texto}
            </li>
          ))}
          <li>
            <span className="simbolo simbolo--origen" aria-hidden="true" />
            En origen, aún sin salir
          </li>
        </ul>
        <p className="leyenda__nota">
          La punta señala hacia dónde va el tren; «+7», los minutos de retraso, y «?», que no se sabe. Anillo blanco: el
          tren de la ficha abierta.
        </p>
      </div>
    </section>
  );
}
