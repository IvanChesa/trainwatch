# Fase 5b · El encuadre y «Toda España»: informe

**Fecha:** 21-11-2026. **Rama:** `fase-5b-encuadre-y-espana`. **Resultado:** hecha. Rodalies ya abre encuadrado en Cataluña: la causa no era el `fitBounds` sino unos **límites que incluían paradas lejanas con muy pocos trenes** (la R15 en Aragón, la R16 en Ulldecona, la R11 en Francia). Hay una opción «Toda España» con los trenes de todos los núcleos, enlazable con `?nucleo=todos`, sobre un canal nuevo del directo con las mismas garantías. 100 clientes de «Toda España» durante 60 s con los feeds reales: 0 errores, p99 de 50 ms, heap ≤ 75 MB, ~14 KB/s por cliente. Pruebas: backend 133 (1 omitida), Vitest 57, e2e 18.

## 1. Especificación

Punto de partida: `docs/prompts/fase-5b.md` (Iván, 21-11).

**Entra:**

1. **Encuadre** (fallo): al abrir y al cambiar de núcleo, el mapa se acerca a los `limites` (o al `centro` y `zoom`) de `/api/nucleos`, con el hueco del panel; sin animación con movimiento reducido. Buscar la causa y una prueba que falle sin el arreglo.
2. **«Toda España»:** opción del selector, enlazable por URL; Rodalies sigue por defecto. Backend: directo de todos los núcleos con la misma instantánea, cambios, latido, `Last-Event-ID`, máximo de conexiones y control del cliente lento. Medir tamaños y una carga corta. En la web: filtro de línea desactivado o agrupado (explicado), núcleo en la lista, rendimiento fluido, RL4 destacada. Contrato en `docs/api/` y OpenAPI.

**No entra:** las estadísticas (fase 6, en otra sesión), `infra/` y el despliegue.

**Aceptación:** Rodalies encuadrado al abrir en escritorio y móvil (prueba que falla con los límites de antes); `?nucleo=todos` abre el directo de todos con la península encuadrada; el canal nuevo pasa las mismas pruebas que el de un núcleo (instantánea, cambios, latido, reconexión, 503, cliente lento); `./mvnw verify`, Vitest y e2e en verde; cifras de carga apuntadas.

## 2. La causa del encuadre

Reproducido en local con la web contra la API de producción (1024 × 768) e instrumentando el mapa: `fitBounds` se llamaba con el mapa aún sin cargar, **pero se aplicaba bien** (centro lon 0,08 y zoom 6,78, justo lo que corresponde a esos límites con 400 px de margen). El problema eran los límites de Rodalies: lon −0,82…3,16 y lat 40,60…42,46. En el horario local (el mismo que el de producción):

- lon −0,82: **Villanueva de Gállego** (Aragón), R15, 10 horas de paso de 340.441 del núcleo; con ella Tardienta, Quinto, La Puebla de Híjar, Samper, Caspe…;
- lat 40,60: **Ulldecona**, R16, 125;
- lon 3,163: **Cerbère** (Francia), R11, 40.

Con 4° de ancho, el zoom era 6,8 en una ventana de 1024 px y 5,9 en un móvil: media península. Bilbao tenía lo mismo (la C4 hasta León). Descartado lo demás que sugería el encargo: el contenedor tenía tamaño (1024 × 768) y el mapa oculto de la vista de lista usa `visibility`, que lo conserva.

**Arreglo** ([ADR 0023](../adr/0023-encuadre-por-el-servicio-de-cada-nucleo.md)): cada estación pesa sus horas de paso y, en cada eje, se quita por cada lado lo que sume menos del 0,5 %. Rodalies queda en lon 0,54…3,15 y lat 40,81…42,41 (zoom 6,9 → 7,4); Bilbao, −4,91…−2,88 (7,5 → 7,9). En la web, además, el primer encuadre va sin animación y espera al primer `resize` si el contenedor aún no tiene tamaño.

## 3. Qué se hizo

| Pieza | Detalle |
|---|---|
| `ExtensionDelServicio` | Función pura: de las paradas con sus horas de paso al rectángulo recortado. `ConsultasApi.extensiones()` agrupa las horas de paso por núcleo y estación (~900 filas) y la llama; se guarda hasta el horario siguiente |
| `DifusorDirecto` | Canal `todos` aparte del mapa de núcleos; `publicar` calcula la foto de cada núcleo y la de todos con los mismos `TrenMapa`; `difundir` (el cuerpo de antes) sirve para los dos; `suscribirTodos` comparte cola, hilo virtual, máximo y marca de fin |
| `DirectoSseController` | `nucleo` es texto: un código o `todos` (patrón en OpenAPI); lo demás, 400 `ProblemDetail` con `parametro: nucleo` |
| `Respuestas` | `Instantanea.nucleo` y `Cambios.nucleo` pasan a `Integer` (`null` en `todos`); descripción nueva de `limites`, `centro` y `zoom` |
| `encuadre.ts` | `encuadreDeNucleo`, `encuadreDeTodos` (la unión de los límites) y `movimientoEncuadre` (qué pedir a MapLibre, con márgenes y animación) |
| `Mapa.tsx` | Prop `encuadre` en vez de `nucleo`; el efecto encuadra cuando cambia su clave |
| `App.tsx` | «Toda España» en el selector, `?nucleo=todos`, filtro de línea desactivado con su nota, avisos de todos, botón RL4 que lleva a Rodalies con la RL4 |
| `ListaTrenes`, `textos.ts` | El núcleo de cada tren y orden por núcleo |
| `capaTrenes.ts` | La línea destacada se filtra por núcleo y línea. De paso: con ninguna destacada, el filtro era «línea vacía» y resaltaba los trenes sin línea; ahora no resalta ninguno |
| `docs/api/` | `nucleos.json` con los límites nuevos (los mismos que da la aplicación con el horario real) y el README con `nucleo=todos` |
| `scripts/rendimiento.mjs` | Fluidez del mapa con un núcleo y con toda España (Playwright, Chromium sin interfaz) |

### Decisiones tomadas sin consultar

- **Recorte del 0,5 %** por lado y eje: con el 1 %, Lleida-Pirineus queda fuera por 0,02°.
- **El campo sigue llamándose `limites`**: cambia su descripción, no el contrato.
- **`nucleo=todos` explícito**: `/api/directo` sin parámetro sigue siendo 400.
- **Sin REST de todos los trenes**: la web no lo usa (la instantánea del SSE es la foto entera).
- **Filtro de línea desactivado** con «Toda España», no agrupado: los nombres se repiten entre núcleos y para ver una línea basta con elegir su núcleo (ADR 0024).
- **El botón de la RL4** sigue con «Toda España» y lleva a Rodalies con la RL4 filtrada; el anillo de la RL4 se ve también en el mapa de toda España.
- **El mismo máximo de conexiones** para todos los canales.

## 4. Cifras

### Pruebas

- **Backend:** `./mvnw verify` **133 pruebas, 0 fallos, 1 omitida** (antes, 125). Nuevas: `ExtensionDelServicioTests` (4, con las horas de paso reales de Rodalies y Bilbao; **con el cálculo de antes fallan 2**), `DifusorDirectoTests` (+3: el canal de todos con instantánea, cambios y bajas, `nucleo` nulo y sin eventos de más en el de un núcleo; cliente de todos que no lee; el máximo cuenta las de todos) y `ApiIntegracionTests` (+1 y ampliada la de OpenAPI: 400 con `nucleo=espana`, instantánea de todos igual a la suma de las de cada núcleo, cambios con la secuencia siguiente, latido, `Last-Event-ID` y 503 con el máximo).
- **Vitest:** **57** (antes, 48): el encuadre (márgenes, sin animación al principio y con movimiento reducido, centro y zoom sin límites, la unión de toda España y los límites de Rodalies del contrato) y la aplicación con «Toda España» (por URL y por el selector, filtro desactivado con su descripción, avisos de todos, lista con el núcleo, RL4 destacada y su botón).
- **Playwright:** **18 de 18** (9 casos × escritorio y móvil). Nuevos: **el encuadre** (qué ciudades se ven sin contar lo que tapa el panel: Barcelona, Lleida y Girona sí, Zaragoza y Madrid no; al cambiar a Madrid, solo Madrid). **Con el `nucleos.json` de antes, falla** en los dos (Zaragoza visible; en el móvil, también Valencia). Y **«Toda España»** (península encuadrada con Madrid, Sevilla, Bilbao, Valencia, Barcelona y Lleida a la vista, el tren de Rodalies moviéndose y la lista con el núcleo).
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

Con casi cuatro veces más trenes, ni una tarea larga ni más trabajo: la capa pinta a 30 fps como mucho y la interpolación es lineal en el número de trenes. Vista a mano en el navegador a 1024 px con 321 trenes reales: la península entera con todos los núcleos. Que no haya errores ni violaciones de la CSP lo comprueba el e2e de «Toda España» (con la CSP de producción).

## 5. Revisión independiente

_(Pendiente.)_

## 6. Qué quedó fuera

1. **Desplegarlo:** sin push; lo hace la sesión de control. El backend y la web tienen que ir juntos: la web nueva con la API vieja no tiene `nucleo=todos` (400 → «sin conexión» y reintentos) y la API vieja da los límites de antes.
2. **Un máximo de conexiones aparte para «Toda España»** y **comprimir o mandar solo los campos que cambian**: el ancho de banda es lo primero que se agotará (ADR 0024).
3. **REST de todos los trenes** (`GET /api/trenes`): la web no lo necesita.
4. **El filtro por línea agrupado por núcleo** con «Toda España».
5. **El e2e sigue fuera de la CI** (informe de la fase 5).

## 7. Qué cambiaría del plan

- **Probar con los datos reales de producción** las pantallas de la web (encuadre, número de trenes) antes de dar una fase por hecha: el e2e usaba un `nucleos.json` con los mismos límites malos y nadie miró si encuadraban bien. Ahora el e2e lo comprueba.
- La aceptación de las fases con mapa debería incluir el móvil a 375-412 px: es donde el fallo era más visible.
