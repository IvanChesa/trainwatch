# ADR 0011 · React en lugar de Angular

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 4r (cambio de stack, antes de la fase 4)

**Sustituye** a la elección de Angular para el frontend: el §4 del plan («Frontend: Angular con MapLibre GL») y el esqueleto de Angular 22 de la fase 1. Esa elección no tenía ADR propio; se tomó en el plan y se citaba en el [ADR 0001](0001-monorepo-con-un-maven-wrapper-por-proyecto.md), que lleva ahora una nota.

## Contexto

- El plan eligió Angular como «el stack que más piden las consultoras en España». La fase 1 montó un esqueleto de Angular 22 con una página «en construcción», sin mapa ni lógica, así que cambiarlo ahora cuesta poco.
- Iván, el 20-11: quiere el stack **React + Java**, por el mercado de trabajo al que apunta.
- Angular ya está en sus proyectos **Torno** y **Sistema Solar**. Con TrainWatch en React, el portfolio enseña los dos frameworks en lugar de repetir uno.
- El backend (Java 25 y Spring Boot, fases 1-3) no cambia.

## Decisión

- **React 19** (19.3.1, la estable del 20-11) con **Vite 8** y **TypeScript 7** en modo `strict`, generado con `npm create vite@9.3.4 -- --template react-ts`.
- **Pruebas:** **Vitest 5** con **Testing Library** (`@testing-library/react` 16 y `jest-dom`) en `jsdom`. Vitest ya era el de Angular, así que las pruebas siguen con la misma herramienta.
- **Calidad:** el linter que trae la plantilla. La tarea pedía «ESLint, el que trae la plantilla», pero la plantilla actual de `create-vite` ya no trae ESLint sino **oxlint** (con los plugins `react`, `typescript` y `oxc`, y `rules-of-hooks` como error). Se usa ese, con `--deny-warnings` para que un aviso rompa la CI.
- **Sin librerías de estado ni de interfaz:** `useState` y `useEffect` bastan para la página provisional. Lo que haga falta para el mapa (MapLibre, quizá TanStack Query) se decide en la fase 5.
- **Desarrollo en `http://localhost:5173`** (`strictPort`), con el proxy de Vite de `/api` a `http://localhost:8080`: el navegador ve un solo origen y en desarrollo no hace falta CORS.

## Alternativas

1. **Seguir con Angular 22.** Lo que pedía el plan y lo más pedido en las consultoras; se descarta por decisión de Iván y porque repetiría el framework de Torno y Sistema Solar.
2. **Next.js (React con servidor).** Se descarta: la web es una SPA estática para Cloudflare Pages y el servidor ya es Spring Boot. Next.js añadiría un segundo backend (SSR, rutas de servidor) sin necesidad.
3. **Create React App.** Abandonado por el equipo de React; Vite es el sustituto que recomienda su documentación para una SPA.
4. **Añadir ESLint a mano** junto a oxlint o en su lugar. Se descarta por ahora: la tarea pide lo que trae la plantilla y oxlint ya cubre las reglas de hooks. Si en la fase 5 hace falta una regla que oxlint no tenga (por ejemplo, de accesibilidad), se añade entonces.

## Consecuencias

- El frontend de desarrollo pasa de `4200` a `5173`. El backend aún no tiene CORS; lo configura la fase 4 para `https://trainwatch.ivanchesa.com` y `http://localhost:5173` (ya está en `docs/prompts/fase-4.md`).
- La CI del frontend ejecuta `npm ci`, `npm run lint`, `npm test` y `npm run build` con Node 24.
- Las guías de defensa de la fase 1 hablan de Angular 22: describen lo que se hizo entonces y no se reescriben; la de la fase 4r explica el cambio.
- Se pierde el argumento de «lo que piden las consultoras» y se gana un portfolio con Angular y React, y un frontend de React + Java para las ofertas que lo piden.
