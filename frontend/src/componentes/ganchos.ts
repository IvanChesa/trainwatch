import { useEffect, useState } from 'react';

/** La hora actual (`Date.now()`), que se renueva cada `cadaMs`. Para los textos «hace X s». */
export function useAhora(cadaMs: number): number {
  const [ahora, setAhora] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setAhora(Date.now()), cadaMs);
    return () => window.clearInterval(id);
  }, [cadaMs]);
  return ahora;
}

const CONSULTA_MOVIMIENTO = '(prefers-reduced-motion: reduce)';

/** `true` si el sistema pide menos movimiento (WCAG 2.3.3): sin animar los trenes ni los vuelos del mapa. */
export function useReducirMovimiento(): boolean {
  const [reducir, setReducir] = useState(() => window.matchMedia?.(CONSULTA_MOVIMIENTO).matches ?? false);
  useEffect(() => {
    const consulta = window.matchMedia?.(CONSULTA_MOVIMIENTO);
    if (!consulta) return;
    const alCambiar = () => setReducir(consulta.matches);
    consulta.addEventListener('change', alCambiar);
    return () => consulta.removeEventListener('change', alCambiar);
  }, []);
  return reducir;
}
