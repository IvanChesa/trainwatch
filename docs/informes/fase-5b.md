# Fase 5b · El encuadre y «Toda España»: informe

**Fecha:** 21-11-2026. **Rama:** `fase-5b-encuadre-y-espana`. **Resultado:** hecha. Rodalies ya abre encuadrado en Cataluña: la causa no era el `fitBounds` sino unos **límites que incluían paradas lejanas con muy pocos trenes** (la R15 en Aragón, la R16 en Ulldecona, la R11 en Francia). Hay una opción «Toda España» con los trenes de todos los núcleos, enlazable con `?nucleo=todos`, sobre un canal nuevo del directo con las mismas garantías. 100 clientes de «Toda España» durante 60 s con los feeds reales: 0 errores, p99 de 50 ms, heap ≤ 75 MB, ~14 KB/s por cliente. Además, por decisión de Iván (21-11), **la RL4 ya no se destaca**. Pruebas: backend 133 (1 omitida), Vitest 61, e2e 20.

## 1. Especificación

Punto de partida: `docs/prompts/fase-5b.md` (Iván, 21-11).

**Entra:**

1. **Encuadre** (fallo): al abrir y al cambiar de núcleo, el mapa se acerca a los `limites` (o al `centro` y `zoom`) de `/api/nucleos`, con el hueco del panel; sin animación con movimiento reducido. Buscar la causa y una prueba que falle sin el arreglo.
2. **«Toda España»:** opción del selector, enlazable por URL; Rodalies sigue por defecto. Backend: directo de todos los núcleos con la misma instantánea, cambios, latido, `Last-Event-ID`, máximo de conexiones y control del cliente lento. Medir tamaños y una carga corta. En la web: filtro de línea desactivado o agrupado (explicado), núcleo en la lista, rendimiento fluido. Contrato en `docs/api/` y OpenAPI.
3. **Quitar el destacado de la RL4** (Iván, 21-11, a mitad de la fase: «Quítale la estrella a lleida y déjala normal como las demás»): sin estrella en el filtro, sin el botón «★ RL4 Lleida», sin el anillo azul en el mapa ni su texto en la leyenda. El encargo tenía «la RL4 sigue destacada»: se ha quitado de `docs/prompts/fase-5b.md`.

**No entra:** las estadísticas (fase 6, en otra sesión), `infra/` y el despliegue.

**Aceptación:** Rodalies encuadrado al abrir en escritorio y móvil (prueba que falla con los límites de antes); `?nucleo=todos` abre el directo de todos con la península encuadrada; el canal nuevo pasa las mismas pruebas que el de un núcleo (instantánea, cambios, latido, reconexión, 503, cliente lento); `./mvnw verify`, Vitest y e2e en verde; cifras de carga apuntadas.

## 2. La causa del encuadre

Reproducido en local con la web contra la API de producción (1024 × 768) e instrumentando el mapa: `fitBounds` se llamaba con el mapa aún sin cargar, **pero se aplicaba bien** (centro lon 0,08 y zoom 6,78, justo lo que corresponde a esos límites con 400 px de margen). El problema eran los límites de Rodalies: lon −0,82…3,16 y lat 40,60…42,46. En el horario local (el mismo que el de producción):

- lon −0,82: **Villanueva de Gállego** (Aragón), R15, 10 horas de paso de 340.441 del núcleo; con ella Tardienta, Quinto, La Puebla de Híjar, Samper, Caspe…;
- lat 40,60: **Ulldecona**, R16, 125;
- lon 3,163: **Cerbère** (Francia), R11, 40.

Con 4° de ancho, el zoom era 6,8 en una ventana de 1024 px y 5,9 en un móvil: media península. Bilbao tenía lo mismo (la C4 hasta León). Descartado lo demás que sugería el encargo: el contenedor tenía tamaño (1024 × 768) y el mapa oculto de la vista de lista usa `visibility`, que lo conserva.

**Arreglo** ([ADR 0023](../adr/0023-encuadre-por-el-servicio-de-cada-nucleo.md)): cada estación pesa sus horas de paso y, en cada eje, se quita por cada lado lo que sume menos del 0,5 %. Rodalies queda en lon 0,54…3,15 y lat 40,81…42,41 (zoom 6,9 → 7,4); Bilbao, −4,91…−2,88 (7,5 → 7,9). En la web, además, el primer encuadre va sin animación, espera al primer `resize` si el contenedor aún no tiene tamaño, su margen sustituye al que MapLibre tenga guardado y no se acerca más que el zoom 12.

**La prueba que protege la causa es la del backend** (`ExtensionDelServicioTests`, con las horas de paso reales: con el cálculo de antes falla). Las de la web comprueban el efecto de encuadre (`Mapa.test.tsx`) y lo que se ve con los límites del contrato (e2e).

## 3. Qué se hizo

| Pieza | Detalle |
|---|---|
| `ExtensionDelServicio` | Función pura: de las paradas con sus horas de paso al rectángulo recortado. `ConsultasApi.extensiones()` agrupa las horas de paso por núcleo y estación (~900 filas) y la llama; se guarda hasta el horario siguiente |
| `DifusorDirecto` | Canal `todos` aparte del mapa de núcleos; `publicar` calcula la foto de cada núcleo y la de todos con los mismos `TrenMapa`; `difundir` (el cuerpo de antes) sirve para los dos; `suscribirTodos` comparte cola, hilo virtual, máximo y marca de fin |
| `DirectoSseController` | `nucleo` es texto: un código o `todos` (patrón en OpenAPI); lo demás, 400 `ProblemDetail` con `parametro: nucleo` |
| `Respuestas` | `Instantanea.nucleo` y `Cambios.nucleo` pasan a `Integer` (`null` en `todos`); descripción nueva de `limites`, `centro` y `zoom` |
| `encuadre.ts` | `encuadreDeNucleo`, `encuadreDeTodos` (la unión de los límites) y `movimientoEncuadre` (qué pedir a MapLibre, con márgenes y animación) |
| `Mapa.tsx` | Prop `encuadre` en vez de `nucleo`; el efecto encuadra cuando cambia su clave |
| `App.tsx` | «Toda España» en el selector (arriba, fuera del `optgroup` «Núcleos»), `?nucleo=todos`, filtro de línea desactivado con su nota en una región viva (y descrita también desde el selector de núcleo), avisos de todos. Sin la RL4 destacada: ni estrella, ni botón, ni línea añadida al filtro si no tiene trenes |
| `ListaTrenes`, `textos.ts` | Con «Toda España», la lista se agrupa por núcleo: un `<h3>` por grupo con su número de trenes, `content-visibility: auto` y orden con un `Intl.Collator`. Comas ocultas para que el lector de pantalla separe línea, destino, situación y retraso |
| `capaTrenes.ts`, `Leyenda.tsx` | Sin la capa del anillo de la línea destacada ni su texto en la leyenda |
| `docs/api/` | `nucleos.json` con los límites nuevos (los mismos que da la aplicación con el horario real) y el README con `nucleo=todos` |
| `scripts/rendimiento.mjs` | Fluidez del mapa con un núcleo y con toda España (Playwright, Chromium sin interfaz) |

### Decisiones tomadas sin consultar

- **Recorte del 0,5 %** por lado y eje: con el 1 %, Lleida-Pirineus queda fuera por 0,02°.
- **El campo sigue llamándose `limites`**: cambia su descripción, no el contrato.
- **`nucleo=todos` explícito**: `/api/directo` sin parámetro sigue siendo 400.
- **Sin REST de todos los trenes**: la web no lo usa (la instantánea del SSE es la foto entera).
- **Filtro de línea desactivado** con «Toda España», no agrupado: los nombres se repiten entre núcleos y para ver una línea basta con elegir su núcleo (ADR 0024).
- **La RL4, una línea más** (decisión de Iván, no mía): se quita todo lo que la destacaba, también lo que había hecho esta fase para destacarla en «Toda España».
- **El encuadre no se acerca más que el zoom 12** (un núcleo de una sola estación acabaría en una calle).
- **El mismo máximo de conexiones** para todos los canales.

## 4. Cifras

### Pruebas

- **Backend:** `./mvnw verify` **133 pruebas, 0 fallos, 1 omitida** (antes, 125). Nuevas: `ExtensionDelServicioTests` (4, con las horas de paso reales de Rodalies y Bilbao; **con el cálculo de antes fallan 2**), `DifusorDirectoTests` (+3: el canal de todos con instantánea, cambios y bajas, `nucleo` nulo y sin eventos de más en el de un núcleo; cliente de todos que no lee; el máximo cuenta las de todos) y `ApiIntegracionTests` (+1 y ampliada la de OpenAPI: 400 con `nucleo=espana`, instantánea de todos igual a la suma de las de cada núcleo, cambios con la secuencia siguiente, latido, `Last-Event-ID` y 503 con el máximo).
- **Vitest:** **61** (antes, 48):
  - `encuadre.test.ts`: márgenes, margen absoluto y zoom máximo, sin animación al principio y con movimiento reducido, centro y zoom sin límites, la unión de toda España y los límites de Rodalies del contrato;
  - `Mapa.test.tsx` (nueva, con un MapLibre simulado): el **efecto** de encuadre al abrir (sin animación), al refrescar los núcleos (no vuelve a encuadrar), al cambiar de núcleo (con vuelo, o sin él con movimiento reducido) y con el contenedor sin tamaño (espera al `resize`). **Falla** si el primer encuadre se anima o si no se espera al tamaño (comprobado cambiando el código);
  - `App.test.tsx`: «Toda España» por URL y por el selector, filtro desactivado y explicado (también desde el selector de núcleo), avisos de todos, lista agrupada por núcleo; la RL4 sin estrella ni botón y, si llega por la URL, en su orden.
- **Playwright:** **20 de 20** (10 casos × escritorio y móvil). Nuevos:
  - **el encuadre**: qué ciudades se ven sin contar lo que tapa el panel (Barcelona, Lleida y Girona sí; Zaragoza y Madrid no; al cambiar a Madrid, solo Madrid). **Con el `nucleos.json` de antes, falla** en los dos (Zaragoza visible; en el móvil, también Valencia);
  - **abrir y cerrar la ficha de un tren y cambiar de núcleo** (el fallo que encontró la revisión, §5);
  - **«Toda España»**: la península encuadrada con Madrid, Sevilla, Bilbao, Valencia, Barcelona y Lleida a la vista, el tren de Rodalies moviéndose y la lista agrupada por núcleo;
  - el del filtro ya no busca el botón de la RL4: comprueba que no hay ni botón ni estrella.
- `npm run lint` y `tsc -b`, sin avisos.

### Tamaños y carga (21-11-2026, 13:14-13:15, feeds reales de Renfe)

Aplicación en local (`java -Xmx512m -jar`, perfil `local`, `maximo-conexiones=100`, la base local con el horario del 20-11) y `java backend/carga/CargaSse.java http://localhost:8080 100 60 todos` en el mismo portátil.

| Medida | Valor |
|---|---:|
| Trenes en «Toda España» | 327 |
| Instantánea de todos | **140 KB** (un núcleo en la fase 4: 31 KB) |
| Cambios de todos | **50-83 KB** por ciclo (media de la prueba: 82,6 KB; el primero tras arrancar, con todos los trenes, 139 KB) |
| Clientes | **100** durante 60 s, conectados en 157 ms; la conexión 101, **503** con `Retry-After: 30` |
| Eventos | 100 instantáneas, 800 cambios, 400 latidos; 82,1 MB |
| Correcto | 0 errores, 0 cortes, 0 huecos de secuencia, todas empiezan por la instantánea |
| Latencia de `cambios` | **p50 14 ms · p95 47 ms · p99 50 ms · máx 51 ms** |
| Heap | 51 MB sin clientes; **57-75 MB** con 100 (de 512 MB) |
| CPU del proceso | 6,6 % al conectarse; luego **0,1-1,5 %** |
| Ancho de banda | **~14 KB/s por cliente** (~1,4 MB/s con 100) |

Sin `WARN` ni `ERROR` de la API en el registro.

### Fluidez del mapa

`node scripts/rendimiento.mjs 15` contra la build y el backend local, 1440 × 900, Chromium sin interfaz con WebGL por software (SwiftShader: pesimista frente a una tarjeta gráfica):

| | Trenes | Fotogramas/s | Intervalo p50 · p95 · máx | Tareas largas | JS del hilo principal |
|---|---:|---:|---|---:|---:|
| Rodalies | 87 | 34,4 | 33 · 50 · 67 ms | 0 | 108 ms/s |
| Toda España | 327 | 35,0 | 33 · 50 · 67 ms | 0 | 100 ms/s |

Con casi cuatro veces más trenes, ni una tarea larga ni más trabajo: la capa pinta a 30 fps como mucho y la interpolación es lineal en el número de trenes. Vista a mano en el navegador a 1024 px con 321 trenes reales: la península entera con todos los núcleos.

### Capturas

`docs/capturas/fase-5b/` (`scripts/capturas.mjs` contra la build con la CSP de producción y el backend local con los feeds reales, porque producción aún no tiene `nucleo=todos`): Rodalies a 1440 y 375 px (ya encuadrada en Cataluña), lista, ficha del tren, y «Toda España» en mapa y lista a 1440 px y en mapa a 375 px. **Sin errores en la consola ni violaciones de la CSP.** En 20 s, 28 de los 50 trenes en marcha de Rodalies se movieron (336 m de media). La captura `escritorio-rl4.png` de `docs/capturas/fase-5/` se queda como registro de esa fase; el script ya no la hace.

## 5. Revisión independiente

Dos revisiones, cada una con un subagente sin contexto de la conversación.

**Código** (con el código y el encargo; 7 hallazgos):

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (alta) | **Tras abrir la ficha de un tren, cambiar de núcleo no encuadraba.** MapLibre 6 guarda el margen del último `easeTo` (el de la ficha: 360 + 410 px) y `fitBounds` le suma el suyo: a 1280 px no cabía nada y el mapa no se movía; en el móvil, el núcleo salía en la mitad de arriba | **Corregido:** `absolutePadding: true` (el margen sustituye al guardado) y el margen también en el `jumpTo`. e2e nuevo: abrir un tren desde la lista, cerrarlo y elegir Madrid → solo Madrid a la vista |
| 2 (media) | Las pruebas de la web que «fallan sin el arreglo» dependen del JSON de ejemplo, no del código; el efecto de `Mapa.tsx` (primer encuadre sin animación, esperar al tamaño) no tenía prueba | **Corregido:** `Mapa.test.tsx` con un MapLibre simulado (falla si se quita cualquiera de las dos cosas). La prueba de la causa es la del backend, `ExtensionDelServicioTests`, y así lo dice el §2 |
| 3 (baja-media) | Un rectángulo sin superficie (núcleo de una estación) lleva el mapa al zoom ~22 | **Corregido:** `maxZoom: 12` en el encuadre |
| 4 (baja) | El botón de la RL4 con «Toda España» era un conmutador que navegaba | **Ya no aplica:** el botón se quitó (decisión de Iván) |
| 5 (baja) | El servidor aceptaba ` 51`, `+51` o `-5` aunque OpenAPI dice `^([0-9]+|todos)$` | **Corregido:** el mismo patrón en el servidor; prueba con `-5`, `+51`, `%2051` y un número de 11 cifras (400) |
| 6 (baja) | El encuadre de «Toda España» se calculaba una vez: si al principio faltaban límites, no se rehacía | **Corregido:** la clave lleva cuántos núcleos tienen límites |
| 7 (baja) | La lista de ~400 trenes se reordenaba en cada pintado con `localeCompare` | **Corregido:** `useMemo` e `Intl.Collator` |

Dio por buenos el canal `todos` (mismo `difundir`, cerrojo, secuencia y serialización única; la ingesta sigue sin tocar sockets; máximo, latido y apagado comunes; `conoce(0)` falso; sin regresiones en el canal de un núcleo), el contrato (`Integer` anulable y `number | null`) y el estado en la URL.

**Diseño y accesibilidad** (`web-design-guidelines` con las reglas de Vercel descargadas, `ui-ux-pro-max` y WCAG 2.1 AA; 8 hallazgos, ninguno alto, sin probar con lector de pantalla):

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (media) | El `<select>` de línea desactivado sale del orden del tabulador: con el teclado no se oye por qué; la nota aparecía sin región viva | **Corregido:** la nota está siempre en la página con `aria-live="polite"` (vacía si no hace falta) y el selector de núcleo también la cita con `aria-describedby`; el texto va al grano («Para filtrar por línea, elige un núcleo») |
| 2 (media) | El estado desactivado apenas se ve (`color: inherit` quita el gris del navegador) | **Corregido:** borde discontinuo, opacidad y `not-allowed`; la etiqueta, atenuada |
| 3 (media) | ~330 botones seguidos sin estructura; el núcleo repetido en cada uno | **Corregido:** un grupo por núcleo con su `<h3>` («Madrid (12 trenes)»), para saltar por encabezados; `content-visibility: auto` en cada grupo en vez de virtualizar (que rompería Ctrl+F y el lector) |
| 4 (baja-media) | El nombre del botón de cada tren se lee de corrido («C1 Hacia Castelló Próxima…») | **Corregido:** comas ocultas entre las partes |
| 5 (baja) | En el móvil la nota alarga el panel | **Corregido:** el ejemplo de la C1 se oculta a la vista por debajo de 768 px (lo sigue leyendo el lector) |
| 6 (baja) | «Todas (elige un núcleo para filtrar)» se corta a 320 px | **Corregido:** «Todas las líneas»; la nota lo explica |
| 7 (baja) | «Núcleo 10» mientras no han llegado los nombres | **Corregido:** sin nombres no se agrupa; el grupo sin nombre dice «Otro núcleo» |
| 8 (baja) | «Toda España» mezclado con los núcleos | **Corregido:** los núcleos van en un `<optgroup label="Núcleos">` |

**Movimiento (`accessible-animation` y `60fps-animation`):** los trenes se pintan en WebGL (no hay propiedades CSS que provoquen *layout*) y la capa no lee el DOM en cada fotograma. Con `prefers-reduced-motion`, los vuelos del mapa (encuadre y ficha) y el fundido se quitan y los trenes saltan en cada actualización (~7 s) en vez de deslizarse. **Se deja apuntado:** WCAG 2.2.2 (pausar lo que se mueve más de 5 s) se interpreta con la excepción de lo esencial (el movimiento *es* la información en directo) y con dos alternativas: la vista de lista y el movimiento reducido. Si se quiere cumplir sin interpretaciones, haría falta un botón «Pausar el movimiento».

**Skills:** `frontend-design` y `design:accessibility-review` las veo; las de `.claude/skills/` no estaban cargadas en esta sesión (se instalaron con ella abierta) y se leyeron sus ficheros a mano. `modern-web-guidance` **no se pudo usar**: su `npx` lo bloqueó el control de permisos de la sesión; hace falta que Iván lo apruebe.

## 6. Qué quedó fuera

1. **Desplegarlo:** sin push; lo hace la sesión de control. El backend y la web tienen que ir juntos: la web nueva con la API vieja no tiene `nucleo=todos` (400 → «sin conexión» y reintentos) y la API vieja da los límites de antes.
2. **Un máximo de conexiones aparte para «Toda España»** y **comprimir o mandar solo los campos que cambian**: el ancho de banda es lo primero que se agotará (ADR 0024).
3. **REST de todos los trenes** (`GET /api/trenes`): la web no lo necesita.
4. **El filtro por línea agrupado por núcleo** con «Toda España».
5. **Un botón para pausar el movimiento** de los trenes (WCAG 2.2.2, ver §5).
6. **El e2e sigue fuera de la CI** (informe de la fase 5).

## 7. Qué cambiaría del plan

- **Probar con los datos reales de producción** las pantallas de la web (encuadre, número de trenes) antes de dar una fase por hecha: el e2e usaba un `nucleos.json` con los mismos límites malos y nadie miró si encuadraban bien. Ahora el e2e lo comprueba.
- La aceptación de las fases con mapa debería incluir el móvil a 375-412 px: es donde el fallo era más visible.
