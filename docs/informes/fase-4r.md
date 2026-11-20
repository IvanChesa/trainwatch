# Fase 4r · De Angular a React: informe

**Fecha:** 20-11-2026. **Rama:** `fase-4r-react`. **Resultado:** hecha. El frontend es React 19 con Vite y TypeScript estricto; lint, 9 pruebas y build en verde, y el backend sigue en verde (63 pruebas, 1 manual omitida).

## 1. Especificación

Tarea `docs/prompts/React.md`. **Entra:** sustituir `frontend/` por React 19 + Vite + TypeScript estricto, Vitest + Testing Library y el linter de la plantilla; la misma página «en construcción» con el aviso de no oficial, la cita de los datos y `GET /api/estado` por el proxy de Vite; CI del frontend; puerto 5173 y CORS; ADR, README, plan y guía de defensa. **No entra:** librerías de estado o de interfaz, mapa (fase 5), CORS si el backend aún no lo tiene (fase 4).

**Aceptación:** `npm run lint`, `npm test` y `npm run build` en verde, y `./mvnw verify` sigue en verde.

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| Proyecto | `npm create vite@9.3.4 -- --template react-ts`, sin los ficheros de ejemplo. `strict: true` explícito en los dos `tsconfig` |
| Página | `App.tsx`: título, resumen, aviso (`role="note"`), estado del backend (`role="status"`: «Consultando el backend…», «Backend 0.0.1-SNAPSHOT · 20/11/26, 13:27:58» o «Backend no disponible») y pie con «Datos: Renfe (CC BY 4.0)». Mismos estilos que en Angular |
| Llamada | `src/api/estado.ts`: `fetch('/api/estado')` con `AbortSignal`, comprueba el código HTTP y la forma del JSON; la hora se enseña en hora de Madrid |
| Proxy | `vite.config.ts`: puerto 5173 con `strictPort` y `/api` → `http://localhost:8080` |
| Pruebas | 9 con Vitest y Testing Library en `jsdom`: 4 de la página (textos, versión y hora, 503, aborto al desmontar) y 5 de la llamada y el formato de hora, con `fetch` simulado |
| CI | Trabajo del frontend: Node 24, `npm ci`, `npm run lint`, `npm test`, `npm run build`. El del backend, sin tocar |
| Docs | [ADR 0011](../adr/0011-react-en-lugar-de-angular.md), nota en el ADR 0001, índice de ADR, README, PLAN (§4, esquema y fases 4 y 5) y guía de defensa |

## 3. Versiones

React y React DOM **19.3.1** · Vite **8.3.8** (Rolldown) · `@vitejs/plugin-react` 6.3.0 · TypeScript **7.0.2** · Vitest **5.1.1** · `@testing-library/react` 16.3.3 · `@testing-library/jest-dom` 8.0.0 · jsdom 31.0.1 · oxlint 1.94.0 · Node 24.22.1 y npm 11.19.1 en local.

## 4. Cifras y comprobaciones

| Comando | Resultado |
|---|---|
| `./mvnw verify` en `main` antes de empezar | BUILD SUCCESS, 63 pruebas (1 manual omitida) |
| `npm run lint` · `npm test` · `npm run build` | Sin avisos · **9 de 9** · JS de 221 kB (69 kB comprimido) |
| Prueba manual: PostGIS + `spring-boot:run` + `vite` | `localhost:5173/api/estado` responde a través del proxy; la página enseña «Backend 0.0.1-SNAPSHOT · 20/11/26, 13:27:58» |
| `./mvnw verify` en la rama | BUILD SUCCESS, 63 pruebas (1 manual omitida) |

## 5. Decisiones tomadas sin preguntar

- **oxlint en lugar de ESLint:** la tarea pedía «ESLint, el que trae la plantilla», pero la plantilla de `create-vite` 9 ya trae **oxlint**. Se usa el de la plantilla, con `--deny-warnings`. Si hace falta ESLint (por ejemplo, reglas de accesibilidad), se añade en la fase 5.
- **No había un ADR de Angular** que marcar como sustituido: Angular se eligió en el §4 del plan. El ADR 0011 dice que sustituye a esa elección, y el ADR 0001, que lo citaba, lleva una nota.
- **CORS:** el backend aún no tiene configuración de CORS, así que no hay nada que cambiar de 4200 a 5173. `docs/prompts/fase-4.md` ya pide CORS para `localhost:5173`, y el plan lo apunta en la fase 4.
- `docs/prompts/fase-4.md`, `fase-5.md` y `CLAUDE.md` no nombran Angular ni el 4200: sin cambios.
- Las guías e informes de fases pasadas siguen diciendo Angular: son históricos.

## 6. Revisión independiente

Un subagente sin contexto revisó el diff contra la tarea. Ejecutó `npm ci`, lint, pruebas (también con `TZ=America/New_York`) y build. **Nada grave ni importante.**

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 | Las pruebas esperan el texto exacto de `Intl` en `es-ES`, que depende de los datos ICU de Node | **Se deja:** Node 24 trae ICU completo y la zona va explícita |
| 2 | No había prueba del aborto al desmontar | **Corregido:** prueba nueva |
| 3 | El esquema del plan seguía diciendo Angular | **Corregido** |
| 4 | Faltaban el informe, la guía y la línea del diario | Hechos en este cierre |

## 7. Qué cambiaría del plan

Nada más allá de lo ya cambiado. En la fase 5, decidir si se añade ESLint con `jsx-a11y` para la accesibilidad del mapa.
