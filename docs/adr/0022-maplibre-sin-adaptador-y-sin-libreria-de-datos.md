# ADR 0022 · El mapa: MapLibre GL 6.17 directamente, sin `react-map-gl` ni TanStack Query, con OpenFreeMap

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 5 · **Lo pedía:** PLAN §4 y `docs/prompts/fase-5.md`

## Contexto

La fase 5 pinta los trenes en directo sobre un mapa en la web de React 19 (ADR 0011). El encargo permite tres dependencias nuevas: `maplibre-gl`, su adaptador `react-map-gl` «si de verdad simplifica» y TanStack Query para los datos de la API. La web se publica en Cloudflare Pages con una CSP estricta (`frontend/public/_headers`, fase 7) y tiene que llegar a Lighthouse ≥ 90 en móvil.

## Decisión

1. **`maplibre-gl` 6.17.0** (publicada el 7-11-2026), versión fija (`--save-exact`). Licencia BSD-3. Es la única dependencia de producción nueva. Se descartaron las versiones de menos de una semana (6.18 y 6.19) por prudencia con la cadena de suministro.
2. **Sin `react-map-gl`.** El mapa es un componente (`src/mapa/Mapa.tsx`) que da un `<div>` a MapLibre y lo maneja desde efectos: uno lo crea y lo destruye, y uno por prop le pasa los cambios. Las capas son clases sin React (`capaTrenes.ts`, `capaLinea.ts`). Motivos:
   - lo que se pinta son **dos fuentes GeoJSON** y unas capas: el adaptador ahorraría unas 30 líneas y metería otra capa de abstracción, otra dependencia que actualizar y su propio ciclo de vida;
   - la animación (`requestAnimationFrame` a 30 fps) tiene que ir **fuera de React**: con el adaptador, pasar las posiciones por props haría renderizar React en cada fotograma; habría que salirse igualmente a la API imperativa;
   - la **capa de trenes queda separada** detrás de una interfaz (`CapaDeTrenes`), que es lo que pide el plan para la vista 3D: la versión 3D (Three.js dentro de MapLibre) será otra clase con la misma interfaz;
   - se ve tal cual cómo se integra una librería imperativa en React, que es una pregunta típica de entrevista.
3. **Sin TanStack Query.** Las peticiones REST son cinco (núcleos, avisos, ficha del tren, ficha de la estación y línea), sin escrituras ni paginación ni caché compartida entre pantallas. Basta un gancho de 40 líneas (`useConsulta`): pide al cambiar la clave, cancela con `AbortController` la anterior y, si se pide, refresca cada X s sin borrar lo que ya enseña. Lo que cambia de verdad (los trenes) llega por SSE, que TanStack Query no gestiona. Si la fase 6 trae muchas pantallas con datos compartidos, se reconsidera.
4. **Mapa base de OpenFreeMap** (`https://tiles.openfreemap.org/styles/dark`): gratuito, sin clave ni registro, datos de OpenStreetMap con el esquema de OpenMapTiles. Las citas («OpenFreeMap», «© OpenMapTiles», «© OpenStreetMap», «Datos: Renfe (CC BY 4.0)» y «Proyecto no oficial») van en un pie siempre visible, también en el móvil, en vez del control de atribución de MapLibre, que en pantallas pequeñas se pliega.
5. **El worker de MapLibre 6** es un módulo aparte (`maplibre-gl-worker.mjs`). MapLibre lo busca junto a su propio fichero, y Vite lo empaqueta en otro sitio, así que se importa con `?url` y se pasa a `setWorkerUrl`: Vite lo publica como un fichero más **del mismo origen**.
6. **CSP** (`_headers`), lo justo: `https://tiles.openfreemap.org` en `connect-src` (MapLibre pide estilo, teselas, sprites y letras con `fetch`) y en `img-src` (teselas de relieve); `blob:` en `img-src` y en `worker-src` (además de `'self'`). Sin `unsafe-inline` ni `unsafe-eval`. Lo comprueban las pruebas e2e, que corren contra la build con estas cabeceras (`vite preview` las lee de `_headers`).
7. **Carga diferida:** `Mapa` va con `React.lazy`, en su propio trozo (~1 MB sin comprimir). La interfaz (panel, estado del directo, lista) se pinta antes.
8. **Pruebas:** `@playwright/test` 1.65.1 (solo de desarrollo) para el e2e. Lighthouse se ejecuta con `npx lighthouse@13.6.0` (fijado) sin añadirlo al `package.json`: pesa mucho para la CI y solo se usa a mano.

## Alternativas

- **`react-map-gl` 8.1:** cómodo para mapas con muchos marcadores en JSX, pero aquí todo son capas GeoJSON y una animación fuera de React (punto 2).
- **Leaflet:** más ligero, pero raster: con cientos de puntos animados y rotados va peor que WebGL, y la vista 3D del plan pide MapLibre.
- **TanStack Query 5:** reintentos, caché y deduplicación que aquí no hacen falta (punto 3); unos 13 KB comprimidos más.
- **Teselas propias (Protomaps en un PMTiles) o de pago (MapTiler, Stadia):** más control o más estilos, pero o hay que alojarlas o piden clave y cuenta; OpenFreeMap da lo mismo gratis y sin clave.
- **El control de atribución de MapLibre:** se pliega en móvil y queda en inglés; el pie propio cumple las condiciones y es visible siempre.

## Consecuencias

- Una sola dependencia de producción nueva; el código de integración con MapLibre es nuestro y hay que mantenerlo al cambiar de versión mayor.
- La web depende de OpenFreeMap para el fondo: si cae, los trenes y la lista siguen funcionando sobre un mapa vacío.
- MapLibre es lo que más pesa y lo que más frena a Lighthouse en móvil (ver el informe de la fase 5).
