# ADR 0027 · Las páginas de puntualidad: rutas propias, gráficos en SVG propio con su tabla, y un ranking de núcleos para toda España

**Fecha:** 21-11-2026 · **Estado:** aceptada · **Fase:** 6 · **Se apoya en:** ADR 0022 (sin librerías de datos), 0024 («Toda España») y 0025 (definiciones)

## Contexto

La fase 6 pide en la web una página por línea (por hora, por día de la semana, semana a semana y sus tramos con más retraso), una del núcleo con el ranking de líneas y la puntualidad en la ficha de la estación, con enlaces desde el mapa y la lista. Los gráficos tienen que ser accesibles: cada uno con su tabla al lado y nada que dependa solo del color. Siempre a la vista: el periodo, «solo trenes vistos en directo», la cobertura, la cita de Renfe y el aviso de no oficial. Además, desde la fase 5b el mapa tiene «Toda España» (`?nucleo=todos`).

La web no tenía enrutador: todo es una pantalla (el mapa) con el estado en la URL (`?nucleo=`, `?linea=`, `?vista=`).

## Decisión

1. **Rutas propias, sin enrutador:** `/estadisticas` (toda España), `/estadisticas/51` (núcleo) y `/estadisticas/51/R4` (línea), con `?desde=&hasta=` opcionales. `Raiz.tsx` mira `location.pathname` al cargar y monta el mapa o la página; los enlaces son `<a href>` normales (una navegación completa). Las páginas van en su propio trozo (`lazy`): 15 KB (4 KB con gzip) que el mapa no carga, y el mapa (1 MB) no se carga en ellas. Cloudflare Pages sirve `index.html` en cualquier ruta (sin `404.html`, trata la web como aplicación de una página), igual que `vite preview`.
2. **Gráficos en SVG propio**, sin librería: columnas de una sola serie (la media por hora, por día o por semana), con su escala redonda, el valor escrito encima de cada columna y «—» donde no hay muestra; y una barra al 100 % para el reparto por tramos de retraso. El SVG va con `aria-hidden`: **al lado va su tabla**, visible y con `caption`, que es lo que lee un lector de pantalla. Los rankings y los tramos son tablas, con una barra decorativa dentro de la celda junto a la cifra escrita.
3. **Colores**: los de las franjas del mapa (verde, amarillo, naranja, rojo), siempre con su texto. Los tramos de la estadística no coinciden del todo con los del mapa (aquí «menos de 3 min» es puntual; en el mapa, 1-2 min ya es amarillo), y por eso nunca va un color sin su etiqueta. Las columnas, en el color de acento: una serie, un color.
4. **Toda España es un ranking de núcleos** (`GET /api/estadisticas/nucleos/todos`), no de líneas: los nombres de las líneas se repiten entre núcleos (C1 en Madrid, Valencia, Asturias, Sevilla…), y comparar líneas de núcleos distintos mezcla servicios muy diferentes. Cada núcleo va con su cobertura (un núcleo que se ve al 50 % no se compara igual que uno al 95 %) y enlaza con su página, donde están sus líneas. El histograma de cada núcleo se suma en la base de datos (`unnest … WITH ORDINALITY`) para no traer decenas de miles de filas.
5. **El periodo se cambia con dos fechas** y un botón que navega a la URL nueva (la CSP tiene `form-action 'none'`: no se envía ningún formulario).
6. **La RL4 no se destaca** (Iván, 21-11): es una línea más, en la API y en la web.

## Alternativas

- **Una librería de gráficos** (Recharts, Chart.js, ECharts): 50-200 KB más para columnas de una serie y una barra apilada; sus SVG o `canvas` necesitan igualmente la tabla al lado, y alguna pide estilos en línea que la CSP no deja.
- **React Router**: resuelve lo mismo con más dependencias; con tres rutas y navegación por enlaces, leer `location.pathname` basta.
- **Páginas dentro del mapa** (una hoja lateral con las cifras): no caben las tablas, y una página de línea es lo que se comparte en LinkedIn.
- **Ranking de todas las líneas de España**: cada C1 tendría que llevar su núcleo, y el primer puesto sería casi siempre una línea pequeña con pocos trenes.

## Consecuencias

- Cambiar de página recarga la aplicación (unos 80 KB con gzip, ya en la caché del navegador). Si un día hay más páginas, un enrutador.
- Los gráficos no tienen tooltips: el valor ya va escrito en cada columna y en la tabla.
- La muestra mínima la dice la API (`publicacion.muestraMinima`), y la web la usa en sus textos («hacen falta 30»): si cambia la configuración, no hay que tocar la web.
