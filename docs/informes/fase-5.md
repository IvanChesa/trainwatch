# Fase 5 · El mapa: informe

**Fecha:** 20 y 21-11-2026. **Rama:** `fase-5-mapa`. **Resultado:** hecha. El mapa enseña los trenes de cada núcleo moviéndose en directo, con su color por retraso, fichas, filtros y vista de lista; 48 pruebas de Vitest y 14 e2e en verde; probado contra la API de producción sin errores ni violaciones de la CSP. **Lighthouse en móvil no llega a 90 en rendimiento** (75 con la simulación por defecto, 80 con la ralentización de DevTools); las otras tres categorías, 100.

## 1. Especificación

Punto de partida: `docs/prompts/fase-5.md`. **Entra:** mapa MapLibre con OpenFreeMap; trenes del SSE con color por retraso (a tiempo, 1-5, 6-15, >15 min y sin dato), símbolo aparte para los trenes en origen, movimiento suave con `requestAnimationFrame` y sin animación con `prefers-reduced-motion`; reapertura del directo tras 503 con espera creciente; selector de núcleo (Rodalies por defecto, con su estado), filtro de línea, RL4 destacada; fichas de tren, estación y avisos; «datos de hace X min»; vista de lista; WCAG 2.1 AA; móvil con hoja inferior; Vitest, Playwright, Lighthouse y capturas. **No entra:** vista 3D, estadísticas (fase 6), backend e infraestructura.

## 2. Qué se hizo

- `src/mapa/`: `Mapa.tsx` (MapLibre integrado con `useRef`/`useEffect`, carga diferida), `capaTrenes.ts` (capa separada tras la interfaz `CapaDeTrenes`, iconos dibujados en lienzo y girados con el rumbo, 30 fps), `interpolacion.ts` (lógica pura), `capaLinea.ts` (recorrido y estaciones de la línea filtrada), `estiloBase.ts` (estilo de OpenFreeMap aligerado y vías desde el zoom 8), `retraso.ts`.
- `src/directo/`: `ConexionDirecto.ts` (SSE, reintento 2-60 s ±20 % tras un error HTTP, vigilante de 45 s) y `useDirecto.ts` (antigüedad con horas del servidor).
- `src/componentes/`: panel, estado de los datos (región viva solo con cambios de estado), leyenda con texto y forma, lista, hoja con foco gestionado, fichas, avisos y `SinMapa` (si no hay WebGL, se pasa a la lista).
- CSP de `_headers`: `tiles.openfreemap.org` solo en `connect-src` y `blob:` en `img-src`; `worker-src 'self'`. El encargo proponía también `blob:` en `worker-src` y el proveedor en `img-src`: la revisión vio que no hacían falta, se quitaron y se comprobó en el navegador con el mapa real (sin violaciones). `vite preview` aplica `_headers` para probarla en local y en e2e.
- [ADR 0022](../adr/0022-maplibre-sin-adaptador-y-sin-libreria-de-datos.md): `maplibre-gl` 6.17.0 sin `react-map-gl` ni TanStack Query; `@playwright/test` 1.65.1 de desarrollo.

## 3. Cifras

- **Vitest:** **48 pruebas** en verde (antes, 13): colores y contraste, interpolación, reconexión (503, espera creciente, vigilante), textos del estado de los datos (la antigüedad crece con el reloj, cuenta atrás del reintento) y la aplicación con Testing Library (fichas, foco, Escape, RL4, filtro, núcleo inexistente, Bilbao «sin datos en directo»). `npm run lint` y `npm run build`, sin avisos.
- **Playwright:** **14 de 14** (7 casos × escritorio 1440 px y móvil Pixel 7) contra la build de producción con su CSP y la API simulada (`e2e/api-simulada.mjs`, con los JSON de `docs/api/` y un SSE falso que mueve un tren cada segundo): carga sin errores ni violaciones de CSP, movimiento continuo (al menos 5 posiciones distintas en 1,5 s), movimiento reducido (como mucho 3), clic en un tren y paso a una estación, lista entera con el teclado (enlace para saltar, Intro, Escape y foco devuelto), filtro y RL4, recuperación tras un 503. Repetido tras todas las correcciones.
- **Lighthouse móvil** (13.6.0, build de producción contra la API real): simulación por defecto **75 / 100 / 100 / 100** (rendimiento, accesibilidad, buenas prácticas, SEO); con ralentización aplicada de DevTools, **80 / 100 / 100 / 100**. FCP y LCP 0,8 s, CLS 0. **Lo que lo frena:** el TBT (1,3-1,4 s simulado; 470 ms aplicado). Es una tarea de ~1 s de MapLibre en el primer fotograma: compila y enlaza 10 programas WebGL (~207 ms con GPU real, que la simulación multiplica por 4). Quitar capas del estilo base no reduce el número de programas. No se llegó a 90 en rendimiento.
- **Datos reales** (build local con la CSP de producción contra `trainwatch-api.ivanchesa.com`, `scripts/capturas.mjs`): el 20-11 a las 21:49, 60 trenes en marcha pintados en Rodalies, de los que 40 se movieron en 20 s (~460 m de media); el 21-11 a las 12:20, 47 y 30 (~420 m). Los que no se mueven están parados en una estación o tienen la posición del feed. Sin errores en la consola ni violaciones de la CSP con el estilo, las teselas, el sprite y las letras reales, también a zoom 11. Capturas en `docs/capturas/fase-5/` (1440 y 375 px: mapa, lista, ficha del tren y la RL4).

- **Backend:** esta fase no toca `backend/` (el diff con `main` solo cambia `frontend/`, `docs/` y el README). Tras la fusión, `./mvnw verify` corrió las 125 pruebas: 79 en verde y 1 omitida (la manual), y las **45 de integración no pudieron arrancar porque Docker no respondía** (Testcontainers: «Previous attempts to find a Docker environment failed»; `docker info` se quedaba colgado). Es del entorno, no del código: hay que repetirlo con Docker en marcha.

## 4. Revisión independiente

Un subagente sin contexto revisó el diff con la especificación (15 hallazgos). Corregido (con prueba donde se puede): ErrorBoundary del mapa con paso a la lista (alta); estilo de reserva si OpenFreeMap no carga (alta); foco de la hoja solo al cambiar lo que enseña; botón RL4 que ya no desaparece; anuncio «Reconectando»; núcleo inexistente en la URL; la duración del movimiento solo se mide con eventos del SSE; mapa oculto con `visibility` para encuadrar bien; «?» en los trenes sin dato y anillos en la leyenda; relleno de la lista bajo la hoja en el móvil; «Datos de hace…» y cuenta atrás del reintento; lienzo normal si no hay `OffscreenCanvas`. `worker-src blob:` e `img-src https://tiles.openfreemap.org` sobraban: quitados y comprobado en el navegador. Las pruebas que faltaban (foco con el título cambiando, foco del botón RL4, antigüedad que crece con el reloj, cuenta atrás) están añadidas. **Se deja:** el e2e usa un estilo mínimo en lugar del de OpenFreeMap, para no depender de la red; la CSP con el mapa real la comprueba `scripts/capturas.mjs`, que se ejecuta a mano.

Además, tras ver las capturas: en el móvil la hoja inferior baja al 50 % de la altura y, al abrir la ficha, el mapa deja el tren encima de la hoja.

## 5. Qué quedó fuera

1. **Lighthouse ≥ 90 en rendimiento:** no se alcanza (75-80) por el coste de arrancar WebGL. Se probó a aligerar el estilo base (de 47 a 26 capas): baja algo el trabajo, pero no el número de programas que compila MapLibre.
2. **El e2e no está en la CI:** necesita los navegadores de Playwright, y los workflows quedan fuera de esta fase. Para añadirlo: un paso `npx playwright install --with-deps chromium` y `npm run e2e` en el trabajo del frontend de `ci.yml`.
3. **La vista 3D** (la capa de trenes ya está separada detrás de `CapaDeTrenes`), el movimiento siguiendo la vía y un buscador de estaciones.
4. Lo que es de otras fases: las estadísticas (fase 6) y la página de cada línea.

## 6. Qué cambiaría del plan

- La aceptación «Lighthouse ≥ 90 en móvil» es difícil con un mapa WebGL en la portada; propongo medirla con la ralentización aplicada y fijar ≥ 80 en rendimiento.
