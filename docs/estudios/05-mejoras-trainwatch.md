# TrainWatch: mejoras posibles a partir de los estudios 01 a 04

> Copia de la síntesis de los estudios de la competencia (21-11-2026). Los estudios completos (01 a 04) están fuera del repo, en la carpeta de estudios de Iván.

- **Fecha:** 21 de noviembre de 2026.
- **Qué es:** una lista única de mejoras para <https://trainwatch.ivanchesa.com>, sacada de cuatro estudios:
  - `01-apps-parecidas.md`: webs y apps parecidas;
  - `02-radardetrenes.md`;
  - `03-traintracker24.md`;
  - `04-renfe-tiempo-real.md`.
- **Reglas de partida:**
  - TrainWatch es portfolio: **nunca llevará anuncios**, y la captación de visitas es para dar visibilidad.
  - No se usa como identidad nada de Renfe (nombre, logotipo, morado ni tipografía Alliance).
  - Se respeta lo que Iván ya decidió: por ejemplo, el 21-11 **se quitó «RL4 destacada»**.
- **Columna «Encaje»:** dice si la mejora **ya está en una fase planificada** (5c o 6, según las notas de la sesión de control del 21-11) o si es **nueva**.

---

## 1. En pocas líneas

1. Hoy TrainWatch es **una sola URL** que sin JavaScript sirve 9 palabras.
   - `/sitemap.xml` devuelve la SPA con un 200.
   - No hay `canonical` ni datos estructurados.

   Para Google casi no existe.
2. Las tres webs estudiadas enseñan tres cosas:
   - el **visor de Renfe**, claridad (entrada por núcleo, mapa apagado, un solo color para el retraso);
   - **TrainTracker24**, producto (fichas ricas, historial por número de tren, sin anuncios);
   - **RadarDeTrenes**, captación (unas 1.740 páginas renderizadas en el servidor con datos vivos).
3. **El hueco de TrainWatch:**
   - la web más clara, ligera y accesible para **Rodalies y Cercanías**;
   - en **castellano y catalán**;
   - con **estadísticas propias** bien explicadas;
   - con páginas por línea y estación que se puedan encontrar en Google.
4. **Prioridad:**
   1. SEO técnico mínimo (bajo esfuerzo y alto impacto);
   2. lo que ya planifica la fase 5c;
   3. páginas de línea y de estación con HTML real;
   4. ficha de tren y patrones de interfaz;
   5. catalán, FGC y difusión.

---

## 2. Mejoras, por bloques

Impacto y esfuerzo: **A** alto, **M** medio, **B** bajo.

### A. SEO técnico mínimo (nuevo, bajo esfuerzo)

| # | Mejora | Por qué | Impacto | Esfuerzo | Encaje |
|---|---|---|---|---|---|
| A1 | **`sitemap.xml` real**, generado en la compilación, y `Sitemap:` en `robots.txt` | Hoy la ruta devuelve la SPA con un 200 (posible *soft 404*). RadarDeTrenes tiene un índice con 9 sitemaps divididos por tipo de página | A | B | Nuevo |
| A2 | **404 reales** para las rutas que no existen, sin devolver `index.html` con un 200 en todo | Evita contenido duplicado y *soft 404*. RadarDeTrenes devuelve 404 de verdad | M | B | Nuevo |
| A3 | **`canonical`** en cada página, URL sin parámetros duplicados (`?station=` y similares fuera del rastreo en `robots.txt`) y estado del mapa en el fragmento `#` | Es lo que hace RadarDeTrenes en su `robots.txt` | M | B | Nuevo |
| A4 | **Alta en Google Search Console y Bing Webmaster Tools**, con el sitemap enviado | Sin esto no se sabe qué consultas traen visitas ni qué páginas se indexan. TrainTracker24 ajusta su `robots.txt` mirando Search Console | A | B | Nuevo |
| A5 | **Títulos y descripciones con las palabras que busca la gente** y con cifras del momento. Por ejemplo, «Rodalies en directo: trenes y retrasos ahora» y «72 trenes hoy, el primero a las 05:57» | El autocompletado muestra demanda de «rodalies en directe», «cercanias tiempo real», «cercanias madrid horarios c3»… | M-A | B | Nuevo |
| A6 | **JSON-LD honesto:** WebSite, Person (Iván como autor), BreadcrumbList y, cuando existan las páginas, `TrainStation` con `GeoCoordinates` y `Dataset` en las estadísticas | Sin valoraciones inventadas y sin FAQ de relleno (RadarDeTrenes quitó un esquema que daba más de 1.600 errores) | M | B | Nuevo |

### B. Lo que ya prepara la fase 5c (confirmado por los estudios)

| # | Mejora | Qué añaden los estudios | Encaje |
|---|---|---|---|
| B1 | **Open Graph y Twitter** con imagen de 1200×630 | RadarDeTrenes y TrainTracker24 lo tienen. Sirve para compartir en LinkedIn y WhatsApp | Fase 5c, punto 1 |
| B2 | **«Estaciones cerca de mí»** con coordenadas redondeadas | Lo tienen el visor de Renfe y RadarDeTrenes. Al pulsar una estación cercana, que el mapa **vuele hasta ella** y abra su ficha (patrón de Renfe) | Fase 5c, punto 2 |
| B3 | **Contenido sin JavaScript** en el `index.html` | **No usar `<noscript>` como única fuente del texto SEO:** TrainTracker24 lo hace y su página renderizada se queda sin `h1`. Mejor un texto visible en el HTML que React sustituya, como el `#ssr-content` de RadarDeTrenes | Fase 5c, punto 3 |
| B4 | **Rendimiento medido en producción** | Hay que ganar a las tres webs: RadarDeTrenes ~1,3 MB con anuncios, TrainTracker24 ~10 MB de JavaScript sin comprimir, y Renfe envía el JSON sin comprimir. **Comprimir con Brotli** las respuestas JSON y el SSE de la API | Fase 5c, punto 4 |
| B5 | **Botón para pausar el movimiento** | Renfe lo tiene («Pausar movimiento de trenes»). Hay que respetar también `prefers-reduced-motion` | Fase 5c, punto 6 |

### C. Páginas que se puedan encontrar (lo que trae visitas)

| # | Mejora | Por qué y cómo | Impacto | Esfuerzo | Encaje |
|---|---|---|---|---|---|
| C1 | **HTML real por ruta:** prerenderizado en la compilación de las páginas de núcleo, línea y estación (la lista cambia poco) y los datos en directo cargados después con JavaScript. Opcional: un Worker de Cloudflare que inserte un resumen vivo («X trenes circulando, Y con retraso a las HH:MM») | Es la clave del tráfico de RadarDeTrenes: cada URL trae texto útil y datos en el HTML. Decidirlo en un ADR (prerenderizado frente a SSR en Spring frente a un Worker) | A | M | Nuevo; necesario antes de C2 y C3 |
| C2 | **Páginas por núcleo y por línea** con URL legibles: `/rodalies`, `/rodalies/r13`, `/cercanias/madrid/c3`. Cada una con estaciones, trenes circulando ahora, retraso de hoy y avisos | Apuntan a «cercanias madrid horarios c3» o «r2 rodalies en directe». El visor de Renfe no tiene ninguna página así | A | M | La fase 6 hace páginas de línea y de núcleo (estadísticas): **que lleven URL propia, el HTML de C1 y su enlace desde el sitemap** |
| C3 | **Páginas por estación** (`/estacion/lleida-pirineus`), empezando por unas 150 a 200 de Rodalies y Cercanías Madrid y publicadas **en tandas de 50 a 100**, comprobando la indexación. Con próximas salidas, líneas, estaciones cercanas y puntualidad de la estación | Las estaciones de RadarDeTrenes tienen entre un 39 y un 46 % de texto propio: hay que superarlo con estadísticas propias. No hacer miles de páginas casi vacías como TrainTracker24 | A | M-A | La fase 6 pone la puntualidad en la ficha de estación: **convertir esa ficha en página** |
| C4 | **Enlazado en estrella:** portada → núcleo → línea → estación → estaciones cercanas y líneas que paran, con migas de pan visibles | Así ninguna página se queda huérfana. RadarDeTrenes enlaza ~1.500 estaciones desde una página índice por provincia | M-A | B | Nuevo (cuando existan C2 y C3) |
| C5 | **Páginas de puntualidad** con URL propia (`/puntualidad`, una por mes o semana), **CSV descargable**, `Dataset` en JSON-LD y una página de metodología | Es lo que más se cita y se enlaza (RadarDeTrenes tiene una página por día con CSV), y es el escaparate técnico del portfolio | A | M | Encaja con la fase 6 (cuando se publiquen las cifras) |
| C6 | **Fichas de tren con URL** (`/tren/{id}`) y **`noindex`** porque son efímeras; indexar solo las que tengan historial de puntualidad de ese número | RadarDeTrenes pone `noindex` a las fichas de tren; TrainTracker24 indexa 23.799 números con poco contenido | M | B-M | Nuevo |

### D. Producto e interfaz (patrones, sin copiar el aspecto)

| # | Mejora | Inspiración | Impacto | Esfuerzo | Encaje |
|---|---|---|---|---|---|
| D1 | **Entrada guiada por núcleo:** la primera pantalla pregunta «¿Rodalies o Cercanías Madrid?» con tarjetas grandes, recuerda la elección y la URL refleja el núcleo | Visor de Renfe: evita el mapa nacional saturado | A | B | Nuevo |
| D2 | **Mapa base apagado:** estilo vectorial propio y desaturado (MapLibre con OpenFreeMap o Protomaps), carreteras y topónimos al 30–40 % | Renfe (variante gris del mapa base del IGN). Es lo que más contribuye a la sensación de limpieza | A | B-M | Nuevo |
| D3 | **Un canal de color solo para el retraso:** la línea con su color en el trazado; el retraso en el marcador (punto, halo o borde) con 3 pasos **y además texto o forma** («+7») | Renfe (punto verde, ámbar o rojo). TrainTracker24 colorea la flecha, pero se satura | A | B | Revisar contra los tramos de color de la fase 6 (<3, 3–5, 6–15 y 16 o más) |
| D4 | **Ficha de tren pensada para el andén:** número grande, retraso en una píldora, origen → destino con barra de progreso, **parada anterior, actual y siguiente con vía y hora estimada**, e itinerario en línea temporal | Jerarquía de TrainTracker24 y RadarDeTrenes; campos de vía y llegada a la siguiente estación del visor de Renfe. Comprobar si el GTFS-RT de TrainWatch trae la vía | A | M | Nuevo |
| D5 | **Historial por número de tren:** «este R13 de las 14:40 llegó puntual el X % de los últimos 30 días» | TrainTracker24 (pestañas de historial y estadísticas); Zugfinder (estudio 01) | A | M | Con los agregados de la fase 6 |
| D6 | **Panel «Peores retrasos ahora»** y «estaciones más afectadas» | TrainTracker24 y el «pulso de la red» de RadarDeTrenes | M | B | Nuevo |
| D7 | **Chapas de línea como filtro múltiple** con «borrar selección» | Renfe. **No repetir su fallo:** al borrar el filtro, los trenes desaparecieron hasta recargar | M | B | Nuevo |
| D8 | **Panel de capas con la leyenda integrada:** trenes por estado, estaciones, accesibilidad y mapa base claro u oscuro | Renfe | M | B | Nuevo |
| D9 | **Filtro «solo trenes y estaciones accesibles»** con el campo `accesible` de Renfe | Renfe y el contador de trenes accesibles de RadarDeTrenes | M | B-M | Nuevo |
| D10 | **El mismo panel en escritorio y en móvil:** panel lateral en escritorio y hoja inferior en móvil, sin recortar funciones; buscador abajo en móvil; barra de pestañas en las fichas | Renfe (mismo componente), RadarDeTrenes (buscador abajo) y TrainTracker24 (pestañas) | M | M | Nuevo |
| D11 | **Indicador de «sin rastro»:** trenes programados que no aparecen en el directo, con una etiqueta prudente | Estudio 01 (idea 2). RadarDeTrenes avisa de que una ausencia no prueba una cancelación | M | M | Encaja con la cobertura de la fase 6 |

### E. Accesibilidad y rendimiento como seña propia

| # | Mejora | Por qué | Impacto | Esfuerzo | Encaje |
|---|---|---|---|---|---|
| E1 | **WCAG 2.2 AA demostrable:** `lang`, `h1` real tras el renderizado, `<button>` de verdad, nombre accesible en todos los botones de icono, objetivos de 24 px o más, contraste AA, enlace para saltar al contenido, **sin bloquear el zoom** y una declaración de accesibilidad publicada | Las tres webs fallan en algo: Renfe no tiene `lang` ni `h1` y bloquea el zoom; TrainTracker24 no tiene `h1` y mezcla idiomas; RadarDeTrenes tiene botones sin nombre y objetivos pequeños | A (portfolio) | M | Parcialmente en la 5c (revisiones); falta la declaración |
| E2 | **Vista de lista accesible** como alternativa al mapa: trenes como botones con texto completo («R13 33503, de Lleida a Les Borges, +3 min») y resumen por línea con `aria-live` | RadarDeTrenes tiene una lista así para lectores de pantalla; el mapa de Renfe no tiene alternativa | A | M | Revisar lo que ya hay en la vista de lista |
| E3 | **Presupuesto de rendimiento en la CI** (Lighthouse CI): un tope de JavaScript inicial y del LCP, el texto principal en el HTML y la nota en el README | Las tres webs pesan más de lo necesario. Sin anuncios es fácil ganar | M | B-M | Tras la 5c |

### F. Alcance, idioma y difusión

| # | Mejora | Por qué | Impacto | Esfuerzo | Encaje |
|---|---|---|---|---|---|
| F1 | **Versión en catalán** de la portada y de las páginas de Rodalies, con `hreflang` es, ca y x-default y URL legibles en cada idioma | Ninguna de las tres webs tiene catalán, y hay demanda de «rodalies en directe», «rodalies temps real» y «estat rodalies en directe». Además le da un sello local de Lleida | M-A | M | Nuevo |
| F2 | **FGC en Lleida** (RL1 Balaguer y RL2 La Pobla), si su GTFS-RT abierto lo permite: **comprobar la licencia y la disponibilidad** | TrainTracker24 ya lo muestra en Lleida-Pirineus. RadarDeTrenes y Renfe no lo tienen | M | M-A | Nuevo |
| F3 | **Página «Sobre» o de prensa:** qué es, de dónde salen los datos, límites, metodología y cómo citarla | RadarDeTrenes la tiene y le sirve para conseguir enlaces y citas | M | B | Nuevo (puede salir de la ficha de portfolio de la 5c) |
| F4 | **Difusión para conseguir enlaces:** un artículo «cómo lo hice» (GTFS-RT → Spring → PostGIS → SSE → MapLibre, Azure y Cloudflare), LinkedIn, GitHub, foros ferroviarios, r/Barcelona y r/spain | Visibilidad para reclutadores. Los enlaces externos ayudan a indexar las páginas nuevas | M | B | El borrador de LinkedIn ya está en la 5c |
| F5 | **Opcional:** `llms.txt` con qué es TrainWatch y cómo citarlo; más adelante, una API pública de solo lectura documentada | RadarDeTrenes está preparada para las búsquedas con IA (`llms.txt`, MCP, OpenAPI) | B-M | B | Nuevo, de baja prioridad |

---

## 3. Lo que no hay que hacer

- **Anuncios, muros de cookies y CMP con cientos de socios** (RadarDeTrenes). Basta con una analítica sin cookies, o ninguna.
- **Contenido SEO solo en `<noscript>`** ni miles de páginas casi vacías por triplicado (TrainTracker24).
- **Mapas nacionales o europeos saturados** como primera pantalla, ni anillos multicolores en las agrupaciones.
- **HTML no semántico:** `div` como botones, sin `lang` ni `h1`, zoom bloqueado (visor de Renfe).
- **Leer endpoints internos de otras webs** (por ejemplo, el `flota.json` del visor de Renfe) sin comprobar su licencia y sus condiciones. Hay que quedarse con los datos abiertos publicados (CC BY 4.0).
- **Competir por «renfe tiempo real»**, que es una consulta de marca. Hay que ir a la cola larga: línea, estación y núcleo, en castellano y catalán.
- **Inventar cifras o valoraciones** en textos o datos estructurados.

---

## 4. Orden sugerido

| Paso | Qué | Bloques |
|---|---|---|
| 1 | SEO técnico mínimo (una tarde) | A1–A6 |
| 2 | Fase 5c tal como está, con los añadidos de los estudios | B1–B5 (texto visible en el HTML, no solo `<noscript>`; Brotli en la API) |
| 3 | ADR y prerenderizado | C1 |
| 4 | Páginas de núcleo y línea con las estadísticas de la fase 6 y el enlazado | C2, C4, C5 |
| 5 | Interfaz | D1, D2, D3, D7 y D8 (entrada por núcleo, mapa apagado, canal de retraso, filtro de líneas y capas) |
| 6 | Ficha de tren e historial | D4, D5, C6 |
| 7 | Estaciones en tandas | C3 |
| 8 | Accesibilidad demostrable y presupuesto de rendimiento | E1–E3 |
| 9 | Catalán, FGC, página de prensa y difusión | F1–F4 |

**Cómo medir si funciona:**

- en **Search Console**, páginas indexadas, impresiones y consultas, revisado cada 2 a 4 semanas tras cada tanda;
- **Lighthouse** en móvil en la CI;
- una revisión de **accesibilidad** antes del hito de portfolio.

---

## 5. Pendiente de comprobar (no verificado en los estudios)

- Lighthouse y CrUX de las tres webs: no hubo permiso para ejecutar Lighthouse en local y PageSpeed tenía la cuota agotada.
- Las posiciones reales en Google: las consultas salen del autocompletado, no de los resultados.
- Si el GTFS-RT de Cercanías que usa TrainWatch trae **vía** y **hora a la siguiente estación** (D4).
- Si FGC publica un GTFS-RT abierto y con qué licencia (F2).
- El precio del plan Essential de TrainTracker24 (hace falta cuenta). Es irrelevante para TrainWatch, que no se monetiza.
