Eres la sesión de programación de **TrainWatch**, la fase 5b («Encuadre y toda España»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero:**
- `CLAUDE.md`;
- `docs/informes/fase-4.md` y `fase-5.md`;
- los ADR 0015 (SSE) y 0022 (MapLibre);
- `docs/api/README.md`.

Construye sobre `main`, que tiene que estar en verde.

## Contexto

El mapa está en producción desde el 21-11 en `trainwatch.ivanchesa.com`. Iván lo ha visto y ha pedido dos cambios (21-11).

## Qué hacer

1. **Arreglar el encuadre** (es un fallo).
   - **Qué pasa:** en producción, la web abre con **toda la península a la vista** aunque el núcleo elegido sea Rodalies. Los trenes salen apiñados en una esquina y parece que faltan los del resto de España.
   - **Lo que tiene que pasar:**
     - al abrir y al cambiar de núcleo, el mapa se acerca a los `limites` (o al `centro` y `zoom`) que da `/api/nucleos`, respetando el hueco del panel;
     - con movimiento reducido, sin animación.
   - **Busca la causa, no solo el síntoma.** Está alrededor del efecto de encuadre de `frontend/src/mapa/Mapa.tsx`. Por ejemplo: un `fitBounds` que se lanza antes de que el mapa esté cargado o con el contenedor oculto o sin tamaño, o unos `limites` de Rodalies demasiado grandes por alguna parada lejana. Compruébalo contra la API de producción.
   - Una prueba que falle sin el arreglo, en Vitest o en el e2e.
2. **Opción «Toda España».**
   - **En el selector de núcleo:** «Toda España», que enseña a la vez los trenes de **todos** los núcleos (unos 400), con la península encuadrada. **Rodalies sigue siendo el núcleo por defecto**, como pide el plan, pero «Toda España» debe poder enlazarse por URL, como los núcleos.
   - **Backend:** el directo de hoy es por núcleo (`/api/directo?nucleo=`). Añade el directo de todos los núcleos: por ejemplo, `/api/directo` sin núcleo o con `nucleo=todos`. Debe mantener la misma instantánea, cambios, latido, `Last-Event-ID`, límite de conexiones y control del cliente lento (el fallo grave de la fase 4). Haz lo mismo con el REST de trenes si el frontend lo necesita.
   - **Carga:** mide el tamaño de la instantánea y de los cambios con todos los núcleos y repite una prueba de carga corta (por ejemplo, 100 clientes de «Toda España» durante 1 min). Apunta las cifras.
   - **En el frontend, con «Toda España»:**
     - el filtro de línea se desactiva o se agrupa por núcleo (elige y explícalo);
     - la lista enseña el núcleo de cada tren;
     - el rendimiento sigue fluido con todos los trenes y la interpolación;
   - Actualiza el contrato de `docs/api/` y OpenAPI.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-5b-encuadre-y-espana`;
  - pruebas del backend y del frontend, incluido el e2e;
  - informe `docs/informes/fase-5b.md`;
  - **revisión independiente** con un subagente sin contexto;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - un apartado nuevo en `docs/defensa/fase-5.md`.
- **A la vez**, otra sesión hace el backend de la fase 6 (estadísticas) en su propio worktree. No toques el paquete de estadísticas, y en el backend limita tus cambios a lo necesario del directo y de la API de trenes.
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y Docker Desktop arrancado.
- **Límites:** nada fuera de la carpeta `trainwatch`. Nada de pago. **Sin push:** lo hace la sesión de control y, al subirlo, la CI despliega la web y la API solas. No toques `infra/`.
- **Si el presupuesto es limitado,** primero el encuadre (punto 1), que es un fallo visible en producción. Haz commits frecuentes.
- **Al terminar:** resumen de 8 líneas como máximo:
  - la causa del encuadre;
  - qué funciona;
  - las cifras de carga;
  - las pruebas;
  - el commit de fusión.
