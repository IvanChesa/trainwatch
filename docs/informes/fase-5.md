# Fase 5 · El mapa: informe

**Fecha:** 20-11-2026. **Rama:** `fase-5-mapa` (sin fusionar: se cortó por límite de uso). **Resultado:** hecha salvo la fusión, la línea del diario y la repetición del e2e y las capturas tras las últimas correcciones.

## 1. Especificación

Punto de partida: `docs/prompts/fase-5.md`. **Entra:** mapa MapLibre con OpenFreeMap; trenes del SSE con color por retraso (a tiempo, 1-5, 6-15, >15 min y sin dato), símbolo aparte para los trenes en origen, movimiento suave con `requestAnimationFrame` y sin animación con `prefers-reduced-motion`; reapertura del directo tras 503 con espera creciente; selector de núcleo (Rodalies por defecto, con su estado), filtro de línea, RL4 destacada; fichas de tren, estación y avisos; «datos de hace X min»; vista de lista; WCAG 2.1 AA; móvil con hoja inferior; Vitest, Playwright, Lighthouse y capturas. **No entra:** vista 3D, estadísticas (fase 6), backend e infraestructura.

## 2. Qué se hizo

- `src/mapa/`: `Mapa.tsx` (MapLibre integrado con `useRef`/`useEffect`, carga diferida), `capaTrenes.ts` (capa separada tras la interfaz `CapaDeTrenes`, iconos dibujados en lienzo y girados con el rumbo, 30 fps), `interpolacion.ts` (lógica pura), `capaLinea.ts` (recorrido y estaciones de la línea filtrada), `estiloBase.ts` (estilo de OpenFreeMap aligerado y vías desde el zoom 8), `retraso.ts`.
- `src/directo/`: `ConexionDirecto.ts` (SSE, reintento 2-60 s ±20 % tras un error HTTP, vigilante de 45 s) y `useDirecto.ts` (antigüedad con horas del servidor).
- `src/componentes/`: panel, estado de los datos (región viva solo con cambios de estado), leyenda con texto y forma, lista, hoja con foco gestionado, fichas, avisos y `SinMapa` (si no hay WebGL, se pasa a la lista).
- CSP de `_headers`: `tiles.openfreemap.org` en `connect-src` e `img-src`, `blob:` en `img-src` y `worker-src`. `vite preview` aplica `_headers` para probarla en local y en e2e.
- [ADR 0022](../adr/0022-maplibre-sin-adaptador-y-sin-libreria-de-datos.md): `maplibre-gl` 6.17.0 sin `react-map-gl` ni TanStack Query; `@playwright/test` 1.65.1 de desarrollo.

## 3. Cifras

- **Vitest:** 41 pruebas en verde (antes, 13): colores y contraste, interpolación, reconexión (503, espera creciente, vigilante), aplicación con Testing Library.
- **Playwright:** 14 de 14 (7 casos × escritorio y móvil) contra la build de producción con su CSP y la API simulada (`e2e/api-simulada.mjs`): carga sin errores ni violaciones de CSP, movimiento continuo, movimiento reducido, clic en un tren, lista con teclado, filtro y RL4, recuperación tras 503. **Pendiente de repetir** tras las correcciones de la revisión (la RL4 pasó a ser un botón con `aria-pressed`).
- **Lighthouse móvil** (13.6.0, build de producción contra la API real): simulación por defecto **75 / 100 / 100 / 100** (rendimiento, accesibilidad, buenas prácticas, SEO); con ralentización aplicada de DevTools, **80 / 100 / 100 / 100**. FCP y LCP 0,8 s, CLS 0. **Lo que lo frena:** el TBT (1,3-1,4 s simulado; 470 ms aplicado). Es una tarea de ~1 s de MapLibre en el primer fotograma: compila y enlaza 10 programas WebGL (~207 ms con GPU real, que la simulación multiplica por 4). Quitar capas del estilo base no reduce el número de programas. No se llegó a 90 en rendimiento.
- **Datos reales** (build local contra `trainwatch-api.ivanchesa.com`, 20-11 ~21:49): 60 trenes en marcha pintados en Rodalies; 40 se movieron en 20 s, ~460 m de media, en 16 de cada 19 segundos; sin errores en la consola ni violaciones de la CSP con el estilo, las teselas y el sprite reales. Capturas en `docs/capturas/fase-5/` (1440 y 375 px).

## 4. Revisión independiente

Un subagente sin contexto revisó el diff con la especificación (15 hallazgos). Corregido: ErrorBoundary del mapa con paso a la lista (alta); estilo de reserva si OpenFreeMap no carga (alta); foco de la hoja solo al cambiar lo que enseña; botón RL4 que ya no desaparece; anuncio «Reconectando»; núcleo inexistente en la URL; la duración del movimiento solo se mide con eventos del SSE; mapa oculto con `visibility` para encuadrar bien; «?» en los trenes sin dato y anillos en la leyenda; relleno de la lista bajo la hoja en el móvil; «Datos de hace…» y cuenta atrás del reintento; lienzo normal si no hay `OffscreenCanvas`. **Se deja:** el e2e de CSP usa un estilo mínimo (la CSP con el mapa real se comprobó con `scripts/capturas.mjs`); `worker-src blob:` e `img-src https://tiles.openfreemap.org` podrían sobrar (el revisor lo dedujo leyendo MapLibre; falta confirmarlo en el navegador); faltan pruebas del foco con el título cambiando y de la antigüedad creciendo con el reloj.

## 5. Qué queda

1. Repetir `npm run e2e` y `node scripts/capturas.mjs` con las correcciones.
2. Fusión `--no-ff` en `main` y línea en `DIARIO.md`.
3. Rendimiento de Lighthouse ≥ 90: no alcanzado (75-80) por el coste de arrancar WebGL.
4. El e2e no está en la CI (necesita los navegadores de Playwright); no se tocaron los workflows.

## 6. Qué cambiaría del plan

- La aceptación «Lighthouse ≥ 90 en móvil» es difícil con un mapa WebGL en la portada; propongo medirla con la ralentización aplicada y fijar ≥ 80 en rendimiento.
