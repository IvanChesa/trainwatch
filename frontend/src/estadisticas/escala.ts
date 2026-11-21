/** Un paso «redondo» para el eje (1, 2, 5, 10…) que deja 3-5 líneas. */
export function escala(maximo: number): { tope: number; paso: number } {
  if (!(maximo > 0)) return { tope: 1, paso: 1 };
  const bruto = maximo / 4;
  const potencia = 10 ** Math.floor(Math.log10(bruto));
  const paso = [1, 2, 5, 10].map((m) => m * potencia).find((p) => p >= bruto) ?? 10 * potencia;
  return { tope: Math.ceil(maximo / paso) * paso, paso };
}
