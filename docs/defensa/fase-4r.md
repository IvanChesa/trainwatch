# Fase 4r · De Angular a React: guía de defensa

Lo que tienes que saber explicar del cambio de frontend. Es pequeño en código, pero te preguntarán por qué cambiaste y en qué se diferencian.

## 1. La foto en 30 segundos

> «El backend es Java 25 con Spring Boot. El frontend lo empecé en Angular, pero antes de escribir el mapa lo pasé a React 19 con Vite y TypeScript estricto: quería un proyecto React + Java, y Angular ya lo tengo en otros dos proyectos. Como solo había una página provisional, el cambio costó una tarde. Las pruebas son de Vitest y Testing Library, y en desarrollo Vite hace de proxy de `/api` al backend, así que no necesito CORS en local.»

## 2. Por qué React y Vite

- **React + Java:** es la combinación que piden muchas ofertas full stack, y así el portfolio enseña Angular (Torno, Sistema Solar) y React (TrainWatch).
- **El momento:** el frontend era una página «en construcción»; cambiar antes de la fase 5 (el mapa) no tira trabajo. Después habría costado rehacer el mapa.
- **Vite** y no Create React App (abandonado) ni Next.js (la web es una SPA estática para Cloudflare Pages y el servidor ya es Spring Boot; Next.js sería un segundo backend). Vite sirve los módulos ES nativos en desarrollo, con arranque y recarga casi instantáneos, y empaqueta para producción.
- **Lo que se pierde:** el argumento de «Angular es lo que más piden las consultoras en España». Está en el [ADR 0011](../adr/0011-react-en-lugar-de-angular.md).

## 3. React frente a Angular (lo que te preguntarán)

| | Angular | React |
|---|---|---|
| Qué es | Un **framework** completo: router, formularios, HTTP, inyección de dependencias, CLI | Una **librería** de interfaz: el resto (router, datos, estado) se elige aparte |
| Componentes | Clases con decoradores y plantilla HTML aparte | Funciones que devuelven JSX |
| Estado | Signals (y RxJS) | Hooks: `useState`, `useReducer`, `useEffect`… |
| Cambios en la vista | Detección de cambios; con signals, fina y por dependencia | Se vuelve a ejecutar el componente y React compara el árbol virtual (el React Compiler, opcional y aquí sin activar, memoriza solo) |
| Datos del servidor | `HttpClient` con observables | `fetch` en un efecto, o una librería como TanStack Query |
| Pruebas | `TestBed` | Testing Library: se prueba lo que ve el usuario (roles, textos), no la implementación |

**Cómo lo explicas:** Angular decide por ti y da uniformidad en equipos grandes; React da piezas pequeñas y eliges tú, con más libertad y más decisiones.

## 4. Cómo funciona la página

- `App.tsx` guarda la conexión en un `useState` con tres estados (`cargando`, `conectado`, `sin-backend`), una unión discriminada de TypeScript.
- El `useEffect` pide `/api/estado` al montar y devuelve una función que **aborta la petición** con un `AbortController`. En desarrollo, `StrictMode` monta el componente dos veces a propósito para destapar efectos sin limpieza: la primera petición se cancela y no pinta nada.
- `obtenerEstado` comprueba el código HTTP y la forma del JSON antes de fiarse de él (en TypeScript los tipos no existen en ejecución).
- **Proxy de Vite:** el navegador pide `localhost:5173/api/estado` y Vite lo reenvía a `localhost:8080`. Mismo origen, sin CORS. En producción la web y la API están en dominios distintos y el CORS lo pone la fase 4.
- **Pruebas:** Vitest con `jsdom`; `fetch` se sustituye con `vi.stubGlobal` por uno simulado, y se comprueba que la página enseña la versión, la hora de Madrid o «Backend no disponible».

## 5. Preguntas probables

- **«¿Por qué no usaste una librería de estado (Redux, Zustand)?»** No hay estado compartido: una página y un dato. Si el mapa lo necesita, lo decido en la fase 5; meterla antes es complejidad sin uso.
- **«¿Por qué `useEffect` y no TanStack Query?»** Para una sola petición sin caché ni reintentos, un efecto con cancelación basta. Para la API del mapa (varias consultas, caché, revalidación) TanStack Query sí tiene sentido.
- **«¿Qué linter usas?»** oxlint, el que trae ahora la plantilla de Vite: está escrito en Rust y es mucho más rápido que ESLint, con las reglas de hooks de React. En la CI un aviso rompe el build (`--deny-warnings`).
- **«¿Qué es Vite por dentro?»** En desarrollo, un servidor que transforma cada fichero bajo demanda y lo sirve como módulo ES; en producción, un empaquetado optimizado (Rolldown en Vite 8) con hash en los nombres para la caché.
