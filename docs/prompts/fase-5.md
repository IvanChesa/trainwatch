Eres la sesión de programación de **TrainWatch**, la fase 5 («El mapa»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0», §2 y la fase 5 en §6;
- los informes de las fases 0 a 4 y el de la 7 (despliegue);
- el contrato de la API en `docs/api/`.

Construye sobre lo fusionado en `main`. Si la fase 4 o la 7 no están fusionadas, **para** y explica por qué en `docs/informes/fase-5.md`.

## Objetivo

La web en **React** (`frontend/`, con React 19, Vite y TypeScript desde la tarea `React.md`) que se enseña en una entrevista: un mapa con los trenes **moviéndose en directo**, que se entiende en 5 segundos.

## Lo que ya hay (fase 7: TrainWatch está en producción desde el 20-11)

- **La API real** funciona en `https://trainwatch-api.ivanchesa.com`, recogiendo datos las 24 h. Su CORS admite `https://trainwatch.ivanchesa.com` y `http://localhost:5173`, así que en local puedes probar contra ella además de contra tu backend.
- **URL de la API:** sale de `VITE_API_URL` (`frontend/src/api/base.ts`, función `rutaApi`). En producción la pone la CI; en local, el proxy de Vite.
- **Publicación:** al fusionar en `main`, `.github/workflows/despliegue.yml` publica la web sola en Cloudflare Pages. **No toques la infraestructura** (`infra/`, Terraform, workflows de despliegue) ni hagas push: lo hace la sesión de control.
- **CSP:** la web tiene cabeceras de seguridad estrictas en `frontend/public/_headers`, que hoy solo admiten la API. Añade lo justo para el mapa y nada más: el proveedor de teselas y estilos (OpenFreeMap) en `connect-src`, `img-src` y, si hace falta, `font-src`; y `blob:` en `worker-src` (y donde lo pida MapLibre). Comprueba en el navegador que no hay errores de CSP, también con la build de producción (`vite preview`).
- **Directo lleno:** `/api/directo` responde 503 con `Retry-After` cuando se llega al máximo de conexiones, y `EventSource` no reintenta solo tras un 503 (ver `docs/api/README.md`). El frontend tiene que **reabrir el directo** con espera creciente.
- **Estado de los datos:** `/api/directo/estado` da también `guardadoAlDia` y `pendientes`. Para el aviso «datos de hace X min» usa la antigüedad del último dato.

## Qué construir

1. **El mapa:**
   - **MapLibre GL** con el estilo gratuito de **OpenFreeMap**, sin clave;
   - citas visibles: «© OpenStreetMap», el proveedor del mapa y «Datos: Renfe (CC BY 4.0)»;
   - aviso de **proyecto no oficial**.

   Las dependencias nuevas permitidas son `maplibre-gl` y, si de verdad simplifica, `react-map-gl` (su adaptador para MapLibre). Para los datos de la API puedes usar TanStack Query. Apunta versiones y motivos en un ADR.
2. **Trenes en directo:**
   - una capa GeoJSON alimentada por el **SSE** `/api/directo?nucleo=…`, con reconexión automática;
   - **color por retraso:** a tiempo, 1-5 min, 6-15 min y más de 15 min, con una leyenda que no dependa solo del color;
   - **trenes en origen,** con otro símbolo («sale a las 10:25»);
   - **movimiento suave** entre actualizaciones, interpolando las posiciones estimadas que da la API, con `requestAnimationFrame`. Con `prefers-reduced-motion`, sin animación.

   La capa de trenes va **separada** del resto, para poder cambiarla por la vista 3D en el futuro.
3. **Interfaz:**
   - selector de núcleo, con Rodalies por defecto y su estado («sin datos en directo» en Bilbao y Cádiz);
   - filtro por línea;
   - **ficha del tren** al hacer clic: línea, origen y destino, próxima parada, retraso y paradas;
   - **ficha de la estación:** próximos trenes y avisos;
   - avisos de la línea;
   - «datos de hace X min» si el directo se para.

   **La RL4 de Lleida, destacada** cuando el núcleo sea Rodalies.
4. **Accesibilidad (WCAG 2.1 AA):**
   - una **vista de lista**, alternativa al mapa, con las mismas fichas;
   - todo usable con el teclado;
   - contraste suficiente;
   - textos alternativos;
   - foco visible.
5. **Diseño:**
   - limpio y moderno, oscuro como ivanchesa.com;
   - sin el nombre, el logo ni los colores de Renfe como identidad;
   - en español;
   - bien en móvil: el mapa a pantalla completa y las fichas en una hoja inferior;
   - sin cookies ni analítica.
6. **Pruebas:**
   - Vitest y Testing Library en la lógica y los componentes: interpolación, colores y reconexión;
   - **e2e con Playwright** contra la API **simulada** con los JSON de `docs/api/` y un SSE falso: el mapa carga, los trenes aparecen y se mueven, las fichas se abren, la lista funciona y el teclado también;
   - **Lighthouse en móvil ≥ 90** en las cuatro categorías: el mapa pesa, así que apunta lo que lo frena;
   - capturas a 375 y 1440 px en `docs/capturas/fase-5/`.
7. **Prueba con datos reales:** con el frontend en local contra la API de producción (o tu backend en local con la ingesta), abre Rodalies y comprueba que los trenes se mueven. Captura y nota en el informe.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-5-mapa`;
  - especificación en el informe;
  - pruebas;
  - informe `docs/informes/fase-5.md`;
  - **revisión independiente** con un subagente sin contexto, también de accesibilidad;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - guía de defensa `docs/defensa/fase-5.md`: React (hooks, estado y efectos, y cómo se integra una librería imperativa como MapLibre), SSE en el navegador, la interpolación y la accesibilidad del mapa.
- **Límites:** no toques nada fuera de esta carpeta. Nada de pago. Sin push.
- **Presupuesto limitado.**
  - Si no llegas, este es el orden de prioridad: el mapa con trenes en directo y colores; fichas; movimiento suave; selector y filtros; vista de lista; e2e; Lighthouse; capturas.
  - Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - Lighthouse;
  - commit de fusión;
  - pruebas;
  - lo que queda.
