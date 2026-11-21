# ADR 0023 · El encuadre de cada núcleo es el rectángulo donde está su servicio, no el de todas sus estaciones

**Fecha:** 21-11-2026 · **Estado:** aceptada · **Fase:** 5b · **Lo pedía:** `docs/prompts/fase-5b.md` (fallo visto por Iván en producción el 21-11)

## Contexto

`GET /api/nucleos` da para cada núcleo `limites`, `centro` y `zoom`, y el mapa encuadra con `fitBounds` sobre `limites`. En producción, con Rodalies (el núcleo por defecto), la web abría con media península a la vista y los trenes apiñados en una esquina.

No era el `fitBounds`: se aplicaba bien (comprobado instrumentando el mapa contra la API de producción). Eran los **límites**: el rectángulo de **todas** las estaciones del núcleo en el horario. Rodalies tiene líneas regionales muy largas con muy pocos trenes:

| Estación | Línea | Horas de paso (horario del 20-11) |
|---|---|---:|
| Villanueva de Gállego (Aragón, lon −0,82) | R15 | 10 |
| Caspe (lon −0,04) | R15 | 126 |
| Ulldecona (lat 40,60) | R16 | 125 |
| Cerbère (Francia, lon 3,163) | R11 | 40 |
| *Total del núcleo* | | *340.441* |

Con ellas, el rectángulo iba de Zaragoza a Francia (4° de ancho): zoom 6,8 en una ventana de 1024 px con el hueco del panel, y 5,9 en un móvil. En Bilbao pasaba lo mismo con la C4 hasta León.

## Decisión

1. **Cada estación pesa sus horas de paso** (filas de `stop_times` de trenes, sin autobuses) y, **en cada eje por separado**, se quita por cada lado lo que sume menos del **0,5 %** del total (`ExtensionDelServicio`). Queda el rectángulo donde pasa el 99 % del servicio en cada eje.
2. `limites`, `centro` y `zoom` salen de ese rectángulo. El campo se sigue llamando `limites`; cambia su descripción en OpenAPI.
3. Se calcula en Java sobre una consulta que agrupa las horas de paso por núcleo y estación (unas 1.000 filas) y se guarda en memoria hasta el horario siguiente, como antes.
4. En la web, el primer encuadre va **sin animación** (el mapa aparece ya encuadrado) y los siguientes, con un vuelo de 0,8 s salvo con movimiento reducido. Si el contenedor aún no tiene tamaño, se espera a su primer `resize`. El margen **sustituye** al que MapLibre tenga guardado (`absolutePadding`: el de la ficha de un tren se sumaba) y el zoom no pasa de 12.

Resultado con el horario real: Rodalies pasa de lon −0,82…3,16 a **0,54…3,15** (de Flix a Portbou y del sur de Tortosa a Colera; dentro Lleida, Tortosa y Girona; Puigcerdà queda 2,5 km por encima del borde) y de zoom 6,9 a **7,4**; Bilbao, de −5,57 a −4,91 (zoom 7,5 → 7,9); Madrid, Asturias, Sevilla, Cádiz y Santander se acercan entre 0,1 y 0,4 niveles; el resto no cambia.

## Alternativas

- **Estaciones distintas sin peso, con percentiles:** una estación con 2 trenes al día pesaría como Sants; con pocas estaciones lejanas pero numerosas (la R15 tiene 9 en Aragón) no las quitaría.
- **Recortar el 1 % por lado:** deja fuera Lleida-Pirineus por 0,02° (la estación de Lleida, de donde salen sus líneas RL).
- **Encuadrar los trenes en directo, no las estaciones:** el encuadre cambiaría con la hora y a primera hora de la mañana enseñaría tres trenes; además, al abrir aún no han llegado.
- **Límites fijos a mano por núcleo:** a mano no se mantienen cuando Renfe cambia el horario.
- **Arreglarlo solo en la web** (más zoom, menos margen): ocultaría el síntoma en una pantalla y no en otra.

## Consecuencias

- Las estaciones muy apartadas quedan fuera del primer encuadre; se ven al alejar el mapa o al filtrar su línea.
- El rectángulo depende del horario: si Renfe añade servicio en una línea larga, el encuadre crece solo.
- La prueba `ExtensionDelServicioTests` usa las horas de paso reales de Rodalies y Bilbao (un CSV de 13 KB en `src/test/resources`); con el cálculo de antes, falla.
