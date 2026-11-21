import type { Directo } from '../directo/useDirecto';
import { anuncio, textoEstadoDatos } from './textos';

export function EstadoDatos({ directo, ahora }: { directo: Directo; ahora: number }) {
  const { texto, aviso } = textoEstadoDatos(directo, ahora);
  return (
    <>
      <p className={`estado-datos${aviso ? ' estado-datos--aviso' : ''}`}>
        <span className="estado-datos__luz" aria-hidden="true" />
        {texto}
      </p>
      <p className="solo-lector" role="status">
        {anuncio(directo, ahora)}
      </p>
    </>
  );
}
