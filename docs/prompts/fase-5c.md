Eres la sesión de programación de **TrainWatch**, la fase 5c («Compartir, cerca de mí, contenido y rendimiento»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero:**
- `CLAUDE.md`, incluido el apartado «Skills de diseño»;
- `docs/PLAN.md`;
- los informes de las fases 4, 5, 5b, 6 y 7;
- `docs/api/README.md`;
- `frontend/public/_headers`.

Construye sobre `main`, que tiene que estar en verde. Si las fases 5b o 6 no están fusionadas, **para** y explica por qué en `docs/informes/fase-5c.md`.

## Contexto

TrainWatch está en producción en `trainwatch.ivanchesa.com` (web) y `trainwatch-api.ivanchesa.com` (API). El 21-11, Iván aprobó cinco mejoras. Lo que se cuenta abajo de producción lo comprobó la sesión principal el 21-11 contra `main` `721c752`; vuelve a comprobarlo.

## Qué hacer

1. **Vista previa al compartir (Open Graph y Twitter).**
   - **Qué pasa:** hoy la portada no tiene ninguna etiqueta `og:`. Al pegar el enlace en LinkedIn o WhatsApp no sale ni imagen ni descripción.
   - **Hace falta:**
     - `og:title`, `og:description`, `og:url`, `og:image` y `twitter:card` = `summary_large_image`;
     - la imagen, de 1200×630: una captura del mapa con trenes, sin nombre, logo ni colores de Renfe, y con el aviso de proyecto no oficial.
   - Los textos, en español de España, sin inventar nada.
   - Después de desplegar, lo comprueba la sesión de control con el Post Inspector de LinkedIn. Deja apuntado cómo hacerlo.
2. **«Estaciones cerca de mí».**
   - **Qué hay:** la API ya tiene `GET /api/estaciones?cerca=lat,lon&radio=…`, pero la web envía `Permissions-Policy: geolocation=()`, que bloquea la geolocalización.
   - **Hace falta:**
     - un botón «Cerca de mí» que pida la ubicación **solo al pulsarlo**;
     - en `_headers`, `geolocation=(self)`;
     - gestionar los casos de ubicación denegada, no disponible o tiempo agotado;
     - que se pueda usar con teclado y lector de pantalla;
     - los resultados, en el mapa y en la lista. Al pulsar una estación cercana, el mapa vuela hasta ella y abre su ficha.
   - **Privacidad:**
     - la ubicación no se guarda ni se envía a ningún sitio más que a la API, en esa consulta;
     - **redondea las coordenadas** antes de enviarlas (unos 3 decimales, ~100 m, basta para encontrar estaciones);
     - que la página lo diga en una línea.
3. **Contenido sin JavaScript.**
   - **Qué pasa:** el `index.html` que se sirve pesa unos 724 bytes y no tiene texto visible.
   - **Hace falta, en el propio HTML:**
     - una introducción corta: qué es y cómo se usa;
     - el aviso de proyecto **no oficial** y «Datos: Renfe (CC BY 4.0)»;
     - un `<noscript>` que explique que el mapa necesita JavaScript.
   - **El texto tiene que verse en la página, no solo dentro de `<noscript>`.** Ese es el fallo de TrainTracker24, que deja la página renderizada sin `h1`. Ponlo como contenido visible del HTML, con un `h1` real, que React complete o sustituya al cargar.
   - Sin romper la CSP de hoy: nada de scripts ni estilos en línea que no admita.
4. **Rendimiento medido en producción.**
   - **Qué pasa:** la fase 5 midió con la API simulada.
   - **Hace falta:** medir contra `https://trainwatch.ivanchesa.com` con datos reales, con la skill `cloudflare:web-perf` y con Lighthouse en móvil: LCP, INP, CLS, TBT, peso del bundle (MapLibre pesa ~1 MB) y coste de arrancar WebGL.
   - Corrige lo que se pueda (carga diferida, división del bundle, caché, prioridades; y **compresión Brotli o gzip de las respuestas JSON y del SSE de la API**, comprobando que pasa a través de Cloudflare) y apunta en el informe **el antes y el después**. Tras fusionar, la sesión de control mide de nuevo en producción.
   - **Mide también cuánto tarda en verse el mapa con trenes**, no solo el panel: el LCP puede ser el texto del panel. La sesión de control vio el 21-11 que, en el navegador integrado de la app (una pestaña en segundo plano, así que puede no ser representativo), el panel salía al momento pero el mapa tardaba 15-20 s en pintarse. Compruébalo en un navegador normal, en primer plano y con red móvil simulada, y si es real, arréglalo.
5. **Hito de portfolio:** pasa a la fase 5d (Iván, 21-11), para que el GIF y el README salgan con el diseño nuevo del mapa. Aquí no se hace.
6. **Botón para pausar el movimiento de los trenes** (WCAG 2.2.2, pendiente de la 5b): visible, con teclado y con un estado que se anuncie. Respeta `prefers-reduced-motion` como hasta ahora.
7. **Vigilancia externa con UptimeRobot** (Iván, 21-11).
   - **Qué pasa:** la vigilancia programada de GitHub Actions (`vigilancia.yml`, cada 15 min) no es fiable. El 21-11 se ejecutó a la 01:00 y a las 09:00 y después pasó más de 5 h sin hacerlo. Si la ingesta se para, podríamos no enterarnos en horas.
   - **Backend:** un endpoint de salud pensado para un vigilante simple, por ejemplo `GET /api/estado/salud`:
     - da **200** si todo va bien y **503** si el último dato de Renfe o `guardadoAlDia` pasan de 5 minutos;
     - lleva un cuerpo corto y `Cache-Control: no-store`, para que Cloudflare no lo guarde.

     Con pruebas.
   - **UptimeRobot (plan gratuito, cada 5 min):**
     - un monitor HTTP de ese endpoint (salta con lo que no sea 2xx);
     - otro de la web `https://trainwatch.ivanchesa.com/`;
     - avisos al correo de Iván.

     **La cuenta la crea Iván**, y también los monitores, o una clave de API que pega él en la terminal, nunca en el chat ni en un fichero. Tú deja en `docs/despliegue.md` los pasos exactos y la configuración de cada monitor.
   - **La vigilancia de GitHub** se queda como segunda capa: documenta que no es fiable. Pon una línea en el informe y en la guía de defensa: por qué un vigilante externo.

8. **SEO mínimo** (Iván, 21-11, a partir de los estudios de la competencia: `C:\Users\ivan\Ivan Chesa AI\estudios-trainwatch\05-mejoras-trainwatch.md`, bloque A; léelo).
   - **Dos fallos comprobados el 21-11 en producción:**
     - `https://trainwatch.ivanchesa.com/sitemap.xml` devuelve el `index.html` de la SPA (200, `text/html`);
     - cualquier ruta que no existe (`/esta-pagina-no-existe`) responde 200.
   - **Hace falta:**
     - un `sitemap.xml` real, generado en la compilación, y `Sitemap:` en `robots.txt`;
     - **404 de verdad** en las rutas que no existen. En Cloudflare Pages, con un `404.html` (sin él, Pages trata la web como SPA). Comprueba antes qué rutas de cliente hay ya (las páginas de puntualidad de la fase 6): o se prerenderizan, o se reescriben con `_redirects` a `index.html` con 200, y todas las demás dan 404;
     - `canonical` en cada página; los parámetros que duplican la misma página, fuera del rastreo en `robots.txt` si hace falta;
     - **títulos y descripciones con lo que busca la gente** (por ejemplo, «Rodalies en directo: trenes y retrasos ahora»), sin inventar cifras;
     - **JSON-LD honesto:** `WebSite` y `Person` (Iván como autor), sin valoraciones ni FAQ de relleno.
   - **Search Console y Bing Webmaster Tools:** deja en `docs/despliegue.md` los pasos para que Iván dé de alta `trainwatch.ivanchesa.com` y envíe el sitemap. La verificación por DNS en la zona compartida `ivanchesa.com` la hace Iván, o se pide a la sesión de control: tú no tocas Cloudflare.

## Antes de cerrar

- **`security-review`** de la API pública: CORS, límites del directo, validación de parámetros (también `cerca` y `radio`) y cabeceras.
- **`web-design-guidelines`** y **`design:accessibility-review`** de todo lo nuevo de la web.
- Apunta los resultados en el informe y corrige lo que salga.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-5c-compartir-y-rendimiento`;
  - pruebas: Vitest y e2e de lo nuevo, y en el backend, de lo que toques;
  - informe `docs/informes/fase-5c.md`;
  - **revisión independiente** con un subagente sin contexto;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - un apartado en `docs/defensa/fase-5.md`: Open Graph, geolocalización y privacidad, mejora progresiva, vigilancia externa y cómo se midió el rendimiento.
- **Skills:**
  - `modern-web-guidance` para cada patrón web: geolocalización, Open Graph, `noscript`, carga diferida…;
  - `ui-ux-pro-max` y `frontend-design` para el diseño;
  - `cloudflare:web-perf` para el rendimiento.
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y Docker Desktop arrancado.
- **Límites:**
  - nada fuera de la carpeta `trainwatch`;
  - nada de pago;
  - **sin push** ni cambios en `infra/`: la sesión de control revisa y sube, y la CI despliega sola.
- **Si el presupuesto es limitado,** este es el orden: 7, 8, 1, 3, 6, 2, 4. Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - el antes y el después del rendimiento;
  - lo que encontraron las revisiones;
  - pruebas;
  - el commit de fusión.
