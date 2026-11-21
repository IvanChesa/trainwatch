# Fase 5 · El mapa: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista: **React (hooks, estado y efectos), cómo se mete una librería imperativa como MapLibre en React, el directo por SSE en el navegador, la interpolación del movimiento y la accesibilidad del mapa**. Este código no lo has escrito tú: repásalo con esta guía delante y abre cada fichero que se cita (todos en `frontend/src/`).

## 1. La foto en 30 segundos

> «La web es React 19 con Vite y TypeScript estricto. El mapa es MapLibre GL con el estilo oscuro gratuito de OpenFreeMap. Al abrirla, la web se conecta por Server-Sent Events al directo del núcleo (Rodalies por defecto): recibe una instantánea con todos los trenes y después, cada 7 segundos, solo los que cambian. Cada tren se pinta con un símbolo que señala hacia dónde va, de un color según su retraso y con los minutos al lado, para no depender solo del color. Entre una actualización y la siguiente, los trenes avanzan suavemente: interpolo en línea recta desde donde se ve el tren hasta su nueva posición estimada, con `requestAnimationFrame`, y si el sistema pide menos movimiento, saltan sin animación. Si el directo se cae o la API está llena (503), la web vuelve a abrirlo con espera creciente, y si Renfe deja de mandar datos lo dice: "datos de hace 6 min". Hay una vista de lista con las mismas fichas para quien no use el mapa, y todo se maneja con el teclado.»

## 2. Decisiones y por qué

| Decisión | Por qué | Alternativa que descarté |
|---|---|---|
| **MapLibre directamente, sin `react-map-gl`** ([ADR 0022](../adr/0022-maplibre-sin-adaptador-y-sin-libreria-de-datos.md)) | Son dos fuentes GeoJSON y unas capas; la animación tiene que ir fuera de React de todas formas | El adaptador: otra dependencia para ahorrar 30 líneas |
| **Sin TanStack Query** | Cinco `GET` sin escrituras; un gancho con `AbortController` basta. Lo vivo llega por SSE | TanStack Query (caché, reintentos que aquí no hacen falta) |
| **OpenFreeMap** | Gratis, sin clave ni cuenta, datos de OpenStreetMap | MapTiler o Stadia (clave), teselas propias (alojarlas) |
| **Capa de trenes separada detrás de una interfaz** (`CapaDeTrenes`) | El plan pide poder cambiarla por una vista 3D | Pintar los trenes dentro del componente del mapa |
| **Interpolar hacia la última posición estimada** | La API ya estima la posición sobre la vía; el navegador solo suaviza el paso entre dos estimaciones | Extrapolar con velocidad y rumbo (se pasa de largo y luego retrocede) |
| **Reabrir el SSE con espera creciente y un vigilante de 45 s** | `EventSource` no reintenta tras un 503, y una conexión colgada no da error | Fiarse de la reconexión automática del navegador |
| **Vista de lista con las mismas fichas** | Un mapa en un `<canvas>` no es accesible con el teclado ni con un lector de pantalla | Hacer los símbolos del mapa enfocables uno a uno |
| **Carga diferida del mapa** (`React.lazy`) | MapLibre pesa ~1 MB; la interfaz sale antes | Todo en un solo paquete |

## 3. React: hooks, estado y efectos

### Dónde vive cada cosa

- **`App.tsx`** tiene el estado de la pantalla con `useState`: núcleo, línea, vista (mapa o lista) y qué ficha está abierta (`Seleccion`, una unión discriminada: tren, estación, avisos o nada). Lo que se calcula a partir del estado (trenes filtrados, lista de líneas) va con `useMemo`, no en otro estado: **el estado mínimo y lo demás derivado**.
- **`useDirecto(nucleo)`** (`directo/useDirecto.ts`) es un gancho propio: abre el SSE en un `useEffect` que depende del núcleo y lo cierra en la función de limpieza. Al cambiar de núcleo, React ejecuta la limpieza (cierra el viejo) y luego el efecto (abre el nuevo).
- **`useConsulta(clave, pedir, refrescarMs)`** (`api/useConsulta.ts`): pide algo cuando cambia la clave y lo cancela con `AbortController` si la clave cambia antes de que llegue la respuesta. Así no se pinta la ficha del tren anterior si el usuario pulsa dos trenes seguidos (una **condición de carrera** clásica).
- **`useAhora(1000)`** renueva la hora cada segundo para los textos «hace 12 s»; **`useReducirMovimiento()`** escucha `matchMedia('(prefers-reduced-motion: reduce)')`.

### Preguntas típicas

- **«¿Por qué un efecto y no llamar a `new EventSource` en el cuerpo del componente?»** El cuerpo se ejecuta en cada render y tiene que ser puro. Los efectos son para sincronizar con algo de fuera (una conexión, una librería, el DOM) y tienen limpieza.
- **«¿Qué pasa con `StrictMode`?»** En desarrollo, React monta, desmonta y vuelve a montar cada componente para detectar efectos sin limpieza. Por eso todo efecto aquí cierra lo que abre: el SSE, los intervalos, las peticiones (`abort`) y el mapa (`map.remove()`).
- **«¿Por qué el estado de `useDirecto` guarda el núcleo?»** Para no enseñar un instante los trenes del núcleo anterior: si el estado es de otro núcleo, el gancho devuelve el inicial.
- **«¿Por qué un `Map` nuevo en cada evento?»** React compara por referencia: si se mutara el mismo `Map`, no vería el cambio.
- **«¿Cómo evitas renderizar React 60 veces por segundo?»** La animación no pasa por React: React recibe un evento cada ~7 s y se lo da a la capa de trenes, que anima por su cuenta con `requestAnimationFrame` (sección 5).

## 4. Una librería imperativa dentro de React: MapLibre

React es **declarativo** (describes qué se ve y React cambia el DOM); MapLibre es **imperativo** (creas un mapa y le das órdenes: `addLayer`, `setData`, `fitBounds`). La pauta (`mapa/Mapa.tsx`):

1. React solo pinta un `<div ref={contenedor}>`. MapLibre mete dentro su `<canvas>`; React no lo toca.
2. **Un efecto con `[]` crea el mapa** al montarse y lo destruye (`m.remove()`) al desmontarse. La instancia va en un `useRef`, no en el estado: cambiarla no tiene que provocar un render.
3. **Un efecto por prop** le pasa cada cambio: `[props.trenes]` → `capa.actualizar(trenes)`, `[props.seleccionado]` → `capa.seleccionar(id)`, `[nucleo.id]` → `fitBounds`. Todos esperan a `listo` (el evento `load` del mapa: hasta entonces no se pueden añadir capas).
4. **Las callbacks van en una ref** (`ultimas.current = props`). Si el efecto que crea el mapa dependiera de `alPulsarTren`, el mapa se rehará cada vez que cambie esa función. Leyéndola de la ref, el mapa se crea una vez y siempre llama a la última.
5. **Las capas son clases sin React** (`capaTrenes.ts`, `capaLinea.ts`), con métodos y un `destruir()`. Eso aísla MapLibre: el resto de la aplicación no sabe que existe.

**Detalles que te pueden preguntar:**
- **Los iconos se dibujan en un `OffscreenCanvas`** (una gota que apunta hacia arriba, de cada color, y un cuadrado hueco para los trenes en origen) y se registran con `addImage`. MapLibre los gira con `icon-rotate` según el rumbo que da la API. No hay ficheros de imágenes.
- **El worker de MapLibre 6** es un módulo aparte; Vite lo publica con `?url` y se le dice a MapLibre dónde está con `setWorkerUrl`. Así se sirve del mismo origen y la CSP solo necesita `worker-src 'self'`.
- **El clic en un tren** se escucha en la capa (`map.on('click', capa, …)`); si hay una estación debajo, el tren marca el evento (`preventDefault`) y la estación no se abre.

## 5. La interpolación: cómo se mueven los trenes

**El problema:** la API manda la posición de cada tren cada ~7 s. Si se pinta tal cual, los trenes van a saltos.

**Lo que hace la API (fase 3, ADR 0010):** estima la posición sobre el recorrido de la línea con el horario y el retraso. El navegador **no estima nada**: solo suaviza el paso de una estimación a la siguiente.

**`mapa/interpolacion.ts` (lógica pura, sin MapLibre ni DOM, con el reloj como parámetro):**

1. Cuando llega una posición nueva de un tren, empieza un **movimiento**: desde donde se le ve *ahora* (que puede ser a medio camino del anterior) hasta la posición nueva, en línea recta y a velocidad constante.
2. **La duración** es lo que se espera que tarde la actualización siguiente: la media del intervalo entre las dos últimas (7 s en producción). Un intervalo de más de 20 s (un corte, una reconexión) no cuenta.
3. En cada fotograma: `t = (ahora − inicio) / duración`, y la posición es `desde + (hasta − desde) · t`, con `t` entre 0 y 1.
4. Los trenes que no han cambiado siguen con su movimiento (los eventos `cambios` solo traen los que cambian).
5. **Un salto de más de 5 km no se anima**: es un cambio de fuente de la posición o un tren que reaparece; animarlo haría cruzar el mapa en diagonal.
6. Con **movimiento reducido**, `animar = false`: el tren salta a su posición nueva.

**El bucle (`capaTrenes.ts`):** `requestAnimationFrame` pide un fotograma; en cada uno se calculan las posiciones y se hace `setData` de la fuente GeoJSON. Se limita a **30 fps** (sobra para algo que avanza unos metros por segundo y descansa el móvil) y **se para** cuando ningún tren está a medio camino. `requestAnimationFrame` además se detiene solo con la pestaña oculta.

**El precio:** el tren va **un ciclo por detrás** de la estimación (llega a la posición de hace 7 s justo cuando llega la siguiente). A cambio, nunca se pasa de largo ni retrocede.

**Preguntas típicas:**
- **«¿Por qué no extrapolar con la velocidad y el rumbo?»** Porque al llegar el dato real habría que corregir hacia atrás cuando el tren frena en una estación: se ve peor que ir 7 s por detrás.
- **«¿Por qué en línea recta y no siguiendo la vía?»** En 7 s un tren recorre unos 100-200 m: la recta entre dos puntos estimados sobre la vía apenas se separa de ella. Seguir la vía exigiría bajar el recorrido de cada línea al navegador; queda para la vista 3D.
- **«¿Cómo lo pruebas?»** Con el reloj como parámetro: `interpolacion.test.ts` comprueba la mitad del camino, el final, el empalme a medio camino, los saltos, los trenes que desaparecen y la media del intervalo. El e2e comprueba que en 1,5 s el tren pasa por al menos 5 posiciones distintas, y que con movimiento reducido no.

## 6. El directo por SSE en el navegador

`directo/ConexionDirecto.ts`:

- **`EventSource`** abre `GET /api/directo?nucleo=51` y escucha tres tipos de evento: `instantanea` (sustituye todo), `cambios` (altas, cambios y bajas) y `latido` (cada 15 s, con la antigüedad del último dato).
- **Reconexión que hace el navegador solo:** si se corta un flujo que iba bien (la API cierra cada conexión a los 30 min), `EventSource` vuelve a conectarse y llega otra instantánea.
- **Reconexión que hace la web:** si la respuesta es un **error HTTP** (el **503** de «directo completo», un 502 de Cloudflare), `EventSource` pasa a `CLOSED` y no lo intenta más. Entonces la web lo vuelve a abrir tras **2, 4, 8, 16, 32 y 60 s**, con un **±20 % al azar** (*jitter*) para que, si la API se reinicia, no vuelvan todos los clientes en el mismo segundo. La espera vuelve a 2 s en cuanto llega una instantánea.
- **Vigilante:** si en **45 s** no llega nada (ni el latido de 15 s), la conexión está colgada aunque el navegador no lo sepa (un proxy que la deja abierta sin datos): se cierra y se abre otra.
- **«Datos de hace X min»:** la antigüedad se calcula con horas **del servidor** (`generado − ultimoDato` en cada evento, o el `antiguedadS` del latido) más lo que ha pasado desde entonces en el reloj del navegador. Así no depende de que el reloj del usuario esté en hora. A partir de 2 min se avisa en amarillo; si la conexión se cae, el contador sigue subiendo.
- **Por qué no se lee el `Retry-After` del 503:** `EventSource` no da acceso a las cabeceras. La espera creciente llega a los 30 s del `Retry-After` en el quinto intento.

**Preguntas típicas:** «¿SSE o WebSocket?» (ver [la guía de la fase 4](fase-4.md): el navegador solo recibe, SSE es HTTP normal y se reconecta solo). «¿Qué pasa con 6 pestañas abiertas?» Con HTTP/1.1 el navegador limita a 6 conexiones por dominio y cada SSE ocupa una; con HTTP/2 (Cloudflare lo usa) van todas por una conexión.

## 7. Accesibilidad (WCAG 2.1 AA)

| Criterio | Cómo se cumple |
|---|---|
| **1.1.1 Contenido no textual** | El `<canvas>` del mapa lleva un `aria-label` que dice cuántos trenes hay y que la lista es la alternativa. Los símbolos de la leyenda son decorativos (`aria-hidden`) junto a su texto |
| **1.4.1 Uso del color** | El retraso nunca va solo con color: en el mapa, «+7» al lado del símbolo; en la lista y las fichas, «7 min de retraso»; la leyenda tiene texto. Los trenes en origen tienen otra forma (cuadrado hueco) |
| **1.4.3 y 1.4.11 Contraste** | Texto claro sobre fondo `#16181c` (más de 7:1 el principal, ~7:1 el secundario); los cinco colores de los símbolos tienen al menos 3:1 con el fondo del mapa, y lo comprueba una prueba (`retraso.test.ts`) |
| **2.1.1 Teclado** | Todo son botones, enlaces y `<select>` nativos. Los trenes del mapa no se pueden recorrer con el tabulador (están en un `<canvas>`): por eso existe la **vista de lista**, con los mismos trenes y las mismas fichas. El mapa en sí se mueve con las flechas y se acerca con `+`/`−` (MapLibre) |
| **2.4.1 Saltar bloques** | Primer elemento de la página: «Ir a la lista de trenes» |
| **2.4.3 Orden del foco** | Al abrir una ficha, el foco va a su título; con Escape o «Cerrar», vuelve al botón que la abrió |
| **2.4.7 Foco visible** | Contorno de 3 px en el color de acento en todo lo enfocable (`:focus-visible`) |
| **2.3.3 y movimiento** | Con `prefers-reduced-motion`, los trenes no se animan ni el mapa vuela al cambiar de núcleo |
| **4.1.3 Mensajes de estado** | El estado del directo se anuncia con `role="status"`, pero solo cuando cambia («En directo», «Sin conexión», «Datos parados»), no cada segundo |

**Preguntas típicas:** «¿Cómo hace accesible un mapa?» No se puede del todo: se da una alternativa equivalente (la lista) y el mapa se describe. «¿Por qué la ficha no es un diálogo modal?» Porque el mapa tiene que seguir usable detrás (se puede pulsar otro tren); es un panel (`<aside>`) con el foco gestionado.

## 8. Pruebas

- **Vitest** (`npm test`): colores y franjas (con el contraste), interpolación (reloj falso), reconexión del SSE (un `EventSource` de mentira y temporizadores falsos: 503, espera creciente, vuelta a 2 s, corte normal, vigilante, cierre) y la aplicación con Testing Library (el mapa sustituido por botones, porque jsdom no tiene WebGL): núcleo por defecto, RL4, Bilbao «sin datos en directo», filtro, lista, fichas, foco y Escape, avisos, «datos de hace 6 min» y 503.
- **Playwright** (`npm run e2e`) contra la **build de producción** con la CSP de `_headers` y una **API simulada** (`e2e/api-simulada.mjs`) que sirve los JSON de `docs/api/` y un SSE falso que mueve un tren cada segundo. En escritorio y en móvil: el mapa carga sin errores ni violaciones de la CSP, los trenes se mueven, con movimiento reducido no, el clic abre la ficha, la lista funciona con el teclado, el filtro y la RL4, y la recuperación tras un 503.
- **Lighthouse en móvil** y **capturas** con datos reales: ver el informe.

## 9. Lo que no está y te pueden preguntar

- **La vista 3D:** la capa de trenes tiene una interfaz para cambiarla, pero no está hecha.
- **El movimiento sigue una recta, no la vía** (sección 5).
- **Las estadísticas** (fase 6).
- **Los trenes del mapa no se recorren con el teclado** (sección 7: la lista es la alternativa).
- **Las fichas de las estaciones** se abren desde las paradas de un tren, los próximos trenes o las estaciones de la línea elegida en el filtro; no hay un buscador de estaciones.

## 10. Fase 5b: el encuadre y «Toda España» (21-11)

Iván vio el mapa en producción y pidió dos cosas: que Rodalies no abriera con media península a la vista y poder ver todos los núcleos a la vez. Ficheros: `backend/.../api/ExtensionDelServicio.java`, `DifusorDirecto.java`, `DirectoSseController.java`; `frontend/src/mapa/encuadre.ts`, `Mapa.tsx`, `App.tsx`. ADR [0023](../adr/0023-encuadre-por-el-servicio-de-cada-nucleo.md) y [0024](../adr/0024-directo-de-todos-los-nucleos.md).

### El fallo del encuadre: síntoma y causa

> «El mapa de Rodalies abría casi con toda la península. Lo primero que sospeché fue el `fitBounds`: que se lanzara antes de cargar el mapa o con el contenedor sin tamaño. Instrumenté el mapa contra la API de producción y no: el `fitBounds` se aplicaba bien. El problema eran los datos: los límites eran el rectángulo de **todas** las estaciones del núcleo, y Rodalies tiene la R15 que llega a Aragón, la R16 a Ulldecona y la R11 a Francia, con muy pocos trenes. El rectángulo iba de Zaragoza a Cerbère. Lo arreglé en el backend: cada estación pesa sus horas de paso y, en cada eje, recorto por cada lado lo que suma menos del 0,5 %. Queda el rectángulo donde pasa el 99 % del servicio.»

- **Por qué en el backend y no en la web:** el síntoma estaba en la web, pero la causa era el dato. Ajustar el zoom en la web lo habría tapado en una pantalla y no en otra, y Bilbao (la C4 hasta León) tenía lo mismo.
- **Por qué pesar por horas de paso:** con percentiles sobre estaciones sin peso, una estación con dos trenes al día pesa lo mismo que Sants, y la R15 tiene nueve estaciones en Aragón: no se irían.
- **Por qué el 0,5 % y no el 1 %:** con el 1 %, Lleida-Pirineus quedaba fuera por 0,02°, y la RL4, que la web destaca, sale de Lleida. Lo comprobé con los datos reales.
- **Por qué cada eje por separado:** es un rectángulo; recortar en dos dimensiones a la vez (por ejemplo, una elipse o un casco convexo) no cambia lo que hace `fitBounds`, que solo entiende rectángulos.
- **La prueba que falla sin el arreglo:** `ExtensionDelServicioTests` con las horas de paso reales de Rodalies y Bilbao (un CSV sacado del horario) y el e2e `al abrir, el mapa encuadra Rodalies`, que mira qué ciudades se ven **sin contar lo que tapa el panel**: con los límites de antes, Zaragoza se veía.
- **En la web:** el primer encuadre va sin animación (aparece ya encuadrado), y si el contenedor no tiene tamaño todavía, se espera al primer `resize` de MapLibre. El cálculo es una función pura (`movimientoEncuadre`) para probarla sin WebGL.

**Preguntas típicas:** «¿Cómo depuraste un fallo que solo se veía en producción?» Reproduciéndolo en local contra la API de producción (admite CORS desde `localhost:5173`) y midiendo en vez de suponer: centro y zoom del mapa tras el `fitBounds`, comparados con los que salen de los límites. «¿Y si Renfe añade servicio en una línea larga?» El rectángulo crece solo, porque sale del horario de cada día.

### «Toda España»: un canal más

> «El directo es un canal por núcleo con su foto, su secuencia y sus suscriptores. Para toda España añadí un canal más con la foto de todos los trenes. En cada ciclo de la ingesta calculo la de cada núcleo y la de todos con los mismos objetos, y el canal de todos pasa por el mismo método que los demás: calcula los cambios y los serializa una vez por ciclo. Las conexiones usan la misma cola, el mismo hilo virtual y el mismo máximo de conexiones, así que el arreglo del cliente lento de la fase 4 vale igual.»

- **`nucleo=todos` y no `/api/directo` sin parámetro:** un cliente que se olvide del parámetro sigue recibiendo un 400, no el flujo más pesado.
- **Por qué no 15 conexiones desde la web:** 15 veces el máximo de conexiones por persona y el navegador solo abre 6 conexiones HTTP/1.1 por dominio.
- **El filtro de línea, desactivado:** los nombres se repiten entre núcleos (hay una C1 en cinco núcleos). Agruparlo exigía llevar el núcleo en el filtro, en la URL y en la consulta del recorrido; para ver una línea basta con elegir su núcleo. La nota lo explica y el selector la enlaza con `aria-describedby`.
- **La RL4 se resalta por núcleo y línea**, no solo por nombre.

**Cifras (21-11, feeds reales, 327 trenes):** instantánea de 140 KB (un núcleo: 31 KB) y cambios de 50-83 KB cada ~7 s. 100 clientes de «Toda España» durante 60 s: 0 errores, 0 huecos, latencia p99 50 ms, heap 57-75 MB, CPU < 7 %, ~14 KB/s por cliente. En el navegador, sin tareas largas y el mismo ritmo de fotogramas que con Rodalies.

**Preguntas típicas:** «¿Qué se rompe primero con muchos usuarios de toda España?» La salida de red: 100 clientes son ~1,4 MB/s. Lo primero sería un máximo aparte para `todos` o mandar solo los campos que cambian. «¿No duplicas memoria con dos fotos?» Son referencias a los mismos `TrenMapa`; lo que se duplica es un mapa de ~330 entradas.
