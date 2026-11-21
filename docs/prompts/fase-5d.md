Eres la sesión de programación de **TrainWatch**, la fase 5d («Diseño del mapa y portfolio»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero:**
- `CLAUDE.md`, sobre todo «Skills de diseño» y «Textos públicos»;
- los informes de las fases 5, 5b, 5c y 6;
- los ADR 0022 a 0024.

Construye sobre `main`, que tiene que estar en verde. Si la 5c no está fusionada, **para** y explica por qué en `docs/informes/fase-5d.md`.

## Contexto

El 21-11, Iván comparó el mapa con dos de referencia, uno de un proyecto parecido y el mapa oficial de Renfe, y eligió cuatro ideas. **Adáptalas con el estilo propio de TrainWatch, sin copiar.** Nada del nombre, el logo ni los colores de Renfe como identidad, como dice el `CLAUDE.md`. Los colores de cada línea que trae el GTFS son datos y sí se pueden usar.

## Qué hacer

1. **Grupos con anillo de retraso.**
   - **Cuándo:** con el mapa alejado y en «Toda España».
   - **Cómo se ve:** los trenes cercanos se agrupan en un círculo con el **número de trenes** y un **anillo de solo tres pasos** (a tiempo, retraso leve y retraso grave; decisión de Iván del 21-11, porque los anillos de muchos colores se leen mal). Elige y explica en el ADR cómo se agrupan los tramos de la leyenda en esos tres.
   - **Al acercarse:** el grupo se abre en trenes sueltos. Al pulsar un grupo, el mapa se acerca.
   - **Cómo hacerlo:** agrupación de MapLibre sobre la fuente GeoJSON, o calculada aparte si hace falta para el anillo. Que siga fluido con los ~400 trenes y la interpolación.
   - **Accesibilidad:** el grupo tiene que poder leerse sin color. Pon un texto o `aria-label` con el reparto, por ejemplo «73 trenes: 40 a tiempo, 20 con 1-5 min…».
2. **Líneas dibujadas.**
   - El recorrido de cada línea se dibuja **siempre**, con su color del GTFS (`route_color`), debajo de los trenes. Si falta el color, una paleta propia.
   - Al filtrar una línea, esa se resalta y las demás se atenúan.
   - **Rendimiento:** mide cuánto pesan los recorridos de un núcleo y de toda España. Simplifica las geometrías o sírvelas de forma eficiente: caché y, si hace falta, un endpoint con todos los recorridos de un núcleo.
   - **Contraste:** que se vean bien en el mapa oscuro y en el claro.
3. **Núcleos en botones.**
   - El desplegable de núcleo pasa a una **rejilla de botones**, con «Toda España» y cada núcleo con su estado, también «sin datos en directo».
   - En el móvil, cómodo con el pulgar.
   - Mantén el enlace por URL (`?nucleo=`) y el uso con teclado (grupo de botones o *radiogroup*, a tu criterio, bien anunciado).
4. **Mapa claro opcional y mapa base apagado.**
   - El estilo base, oscuro y claro, más **apagado**: carreteras y topónimos al 30-40 % y menos capas, para que manden los trenes y las líneas (estudio 05, D2).
   - Un botón para pasar del estilo oscuro a uno claro de OpenFreeMap, que se recuerde en `localStorage`.
   - Por defecto, el que pida el sistema (`prefers-color-scheme`).
   - El panel, la leyenda y las fichas también cambian. Comprueba el contraste en los dos modos.
5. **Filtro de varias líneas a la vez:** chapas de línea que se pueden combinar, con «borrar selección» (estudio 05, D7). No repitas el fallo del visor de Renfe: al borrar el filtro, los trenes desaparecían hasta recargar. Con prueba.
6. **Hito de portfolio, preparado pero sin publicar** (viene de la 5c).
   - **README:**
     - un GIF corto del mapa en movimiento, ya con el diseño nuevo, ligero (menos de 3 MB);
     - un diagrama de la arquitectura: Renfe GTFS-RT → ingesta Spring → PostGIS → API REST y SSE → React con MapLibre, más Azure y Cloudflare.
   - **El texto de la ficha para ivanchesa.com:** en `docs/portfolio/ficha-ivanchesa.md`. No toques ese repo: la sesión de control se lo pasa a la sesión SEO.
   - **El borrador de LinkedIn:** en `docs/portfolio/linkedin.md`.
   - **Textos públicos:**
     - en español de España;
     - nunca «junior» ni «formación»;
     - solo cifras comprobadas en los informes;
     - las de puntualidad, solo si la fase 6 ya permite publicarlas; si no, `[cifra de la fase 6]`.
   - **Rehaz la imagen de Open Graph** de la 5c con el diseño nuevo.

## Antes de cerrar

- **`web-design-guidelines`** y **`design:accessibility-review`** de todo lo nuevo, en los dos modos de color.
- **Lighthouse en móvil, contra producción tras desplegar:** no puede empeorar respecto a la 5c. Apunta el antes y el después.
- **Capturas** a 375 y 1440 px, oscuro y claro, en `docs/capturas/fase-5d/`.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-5d-diseno-del-mapa`;
  - pruebas: Vitest y e2e de lo nuevo;
  - informe `docs/informes/fase-5d.md`;
  - **revisión independiente** con un subagente sin contexto;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - un apartado en `docs/defensa/fase-5.md`: agrupación en el mapa, capas y su orden, temas de color y accesibilidad.
- **ADR:** uno por la agrupación y otro por los recorridos (cómo se sirven y se simplifican).
- **Skills:**
  - `modern-web-guidance`;
  - `ui-ux-pro-max`, `frontend-design` y `taste-skill` para el diseño;
  - `60fps-animation` y `accessible-animation` para el movimiento;
  - `micro-interaction` para los botones.
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y Docker Desktop arrancado.
- **Límites:**
  - nada fuera de la carpeta `trainwatch`;
  - nada de pago;
  - **sin push** ni cambios en `infra/`: la sesión de control revisa y sube.
- **Si el presupuesto es limitado,** este es el orden: 3, 1, 2, 4, 5, 6. Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - las cifras de rendimiento;
  - lo que encontraron las revisiones;
  - pruebas;
  - el commit de fusión.
