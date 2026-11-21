# Fase 6 · Estadísticas de puntualidad: informe

**Fecha:** 21-11-2026. **Rama:** `fase-6-estadisticas`. **Resultado:** **hecha.** Lo que entró:

- las definiciones ([ADR 0025](../adr/0025-definiciones-de-puntualidad.md));
- los agregados diarios con su tarea nocturna (V6, [ADR 0026](../adr/0026-agregados-diarios-y-tarea-nocturna.md));
- la API de estadísticas, con la fecha mínima de publicación;
- las páginas de la web: toda España, núcleo, línea y la ficha de la estación ([ADR 0027](../adr/0027-paginas-de-puntualidad-con-svg-propio.md));
- las pruebas y la comprobación con datos reales.

Pruebas: 159 en el backend (1 omitida), 76 de Vitest y 28 e2e. Las tres cifras comprobadas a mano coinciden exactamente con la API.

> La comprobación con datos reales es **provisional**: se hizo con 1 h 45 min de ingesta local (4.036 filas), porque Iván prefirió avanzar a esperar más horas. Otra sesión la repetirá contra producción con más datos (§6). Las cifras son de prueba: no se publican.

**Cómo se hizo:**

- **El backend**, sin esperar a la fase 5, que se hacía a la vez en otra sesión.
- **La web**, cuando la sesión de control avisó de que se habían fusionado la 5 y la 5b. Desde entonces aplica dos decisiones de Iván: la **RL4 no se destaca** y existe **«Toda España»**.
- **Dos cosas añadidas a petición de la sesión de control:**
  - el porcentaje **con 5 min o menos**;
  - los **cancelados en la página de la línea**, como cota inferior.

## 1. Especificación

Punto de partida:

- el encargo, `docs/prompts/fase-6.md`, con la corrección de `main`: la RL4, sin destacar;
- la fase 6 del §6 del plan;
- los puntos 3 y 8 de «Cambios tras la fase 0».

**Entra:**

1. **Definiciones** antes del código (§2 y ADR 0025).
2. **Agregados que se guardan para siempre** (Flyway V6), por día de servicio:
   - por núcleo, línea y hora;
   - por línea y día;
   - por estación;
   - por tramo;
   - los cancelados explícitos.

   Solo sumas, recuentos e histograma. Una **tarea nocturna** idempotente antes del horario nuevo, un **recálculo a mano** por Actuator y cuidado con los cerrojos (ADR 0026).
3. **API** en el estilo de la fase 4: toda España, núcleo, línea y estación.
   - Con `desde` y `hasta`; por defecto, las últimas 4 semanas.
   - Cada respuesta lleva el periodo, los viajes, las paradas, las inferidas quitadas y la cobertura.
   - `ProblemDetail`, caché y OpenAPI, con un ejemplo por endpoint en `docs/api/`.
4. **Web:**
   - página de línea (por hora, por día, semana a semana y tramos);
   - página del núcleo con el ranking;
   - toda España;
   - la puntualidad en la ficha de la estación;
   - enlaces desde el mapa y la lista.

   Cada gráfico, con su tabla. Siempre a la vista: el periodo, «solo trenes vistos en directo», la cobertura, «Datos: Renfe (CC BY 4.0)» y el aviso de no oficial.
5. **Nada público antes de tiempo:** la propiedad `publicar-desde`.
6. **Pruebas:** del cálculo con filas a mano, de la idempotencia, de los endpoints, Vitest y el e2e de una página de línea.
7. **Comprobación con datos reales:** 3 cifras recalculadas con SQL y comparadas con la API.

**No entra:**

- el despliegue, y el recálculo en producción de los días desde el 21-11 (§7);
- el historial por número de tren (§7).

**Aceptación:**

1. `./mvnw verify`, `npm test` y el e2e en verde, con las pruebas de la lista.
2. Tres cifras de la API (una media por línea y hora, un porcentaje y un tramo) iguales a las calculadas con SQL directo sobre `retrasos`.

## 2. Definiciones (ADR 0025)

- **Unidad:**
  - la cifra principal es por **parada**: el retraso al llegar a cada parada vista;
  - la secundaria, por **viaje**: el retraso al llegar al **destino**, solo de los viajes que se vieron llegar (los demás se cuentan aparte).

  Nunca se mezclan.
- **Cifras:**
  - la media (con un decimal), la **mediana** y el **p90**, en minutos;
  - el reparto de las paradas: **menos de 3 min** (con los adelantos), de 3 a 5, de 6 a 15 y 16 o más;
  - el porcentaje **con 5 min o menos**: el umbral que suele usar Renfe en Cercanías. Sale de contar las paradas, no de sumar dos porcentajes ya redondeados.
- **Qué entra:**
  - solo paradas **vistas** de trenes en directo, con hora programada;
  - las **inferidas** y las paradas **sin hora** quedan fuera y se cuentan aparte;
  - los **autobuses** quedan fuera;
  - los **cancelados** explícitos se cuentan aparte, nunca como retraso.
- **Cancelados:** son una **cota inferior**. Solo cuentan los que Renfe marca `CANCELED` en las posiciones: uno que solo se anuncia en los retrasos no se cuenta, y un tren que no aparece no cuenta como cancelado.
- **Cobertura:** de lo que daba el horario ese día, cuánto se vio en directo, por **viajes** y por **paradas**. Se enseña la del núcleo y, en la página de la línea, también la suya.
- **Franja horaria:** la hora **programada** de llegada, en **hora de Madrid**.
  - El día del cambio de hora, las dos 02:30 van a la franja de las 2.
  - Las filas sin hora quedan fuera de todas las cifras.
- **Día:** el **día de servicio**.
- **Muestra mínima:** **30 viajes** por cifra y 30 pasos por tramo. La API lo dice en `publicacion.muestraMinima`.
- **Publicación:** la propiedad `trainwatch.estadisticas.publicar-desde`. En producción, el **19-12-2026**.
- **Toda España:** un ranking de **núcleos**, no de líneas. Los nombres de línea se repiten entre núcleos, y cada núcleo va con su cobertura.

## 3. Qué se hizo

| Pieza | Detalle |
|---|---|
| **V6** | Cinco tablas: `estad_linea_hora`, `estad_linea_dia`, `estad_estacion_dia`, `estad_tramo_dia` y `estad_dias`. Solo sumas, recuentos e histograma de 61 posiciones, con `CHECK`. Ver el ADR 0026 |
| `CalculoEstadisticas` | Calcula un día en una transacción: con plazos propios, el cerrojo de la importación en modo compartido y uno por día, las copias de lo que ya no se puede volver a sacar, el `DELETE` del día y cuatro `INSERT … SELECT` (`GROUP BY` con `FILTER`, histograma con `generate_series`, `row_number` y `LAG`). La tarea nocturna va a las **03:45**: calcula ayer y recupera los dos días anteriores |
| `EstadisticasEndpoint` | Actuator: `GET` y `POST /actuator/estadisticas`, con el intervalo y un resultado por día. En producción, en el 8081, que no sale de la máquina |
| `EstadisticasController` | `GET /api/estadisticas/nucleos/todos`, `/nucleos/{n}`, `/lineas/{n}/{linea}` y `/estaciones/{id}`. Errores como `ProblemDetail`, `Cache-Control` de 1 h y `ETag`. Antes de la fecha de publicación, ninguna cifra |
| `Acumulado` | Suma las filas sin perder exactitud. Media y porcentajes como `round(numeric, 1)`; mediana y p90 como `percentile_disc` |
| Web: `frontend/src/estadisticas/` | `Raiz.tsx` elige entre el mapa y las páginas por la ruta. Las páginas van en un trozo aparte de 4 KB con gzip. Llevan gráficos en SVG propio (columnas de una serie y la barra del reparto), cada uno con su tabla. Las cifras, por este orden: la media, el % con menos de 3 min, el % con 5 o menos, la mediana y el p90. Además, el titular del ranking, el contexto con las dos coberturas, el periodo con dos fechas, las migas y los estados sin publicar, insuficiente, cargando y error |
| Enlaces | En la cabecera del mapa, «Puntualidad» va a la del núcleo, a la de la línea filtrada o a toda España. En la ficha del tren, «Puntualidad de la R4». En la ficha de la estación, su puntualidad, con enlace a cada línea |
| `docs/api/` | `puntualidadTodos`, `puntualidadNucleo`, `puntualidadLinea` y `puntualidadEstacion`, de la aplicación con datos reales |
| Documentación | Los ADR 0025, 0026 y 0027; `fase-6-comprobacion.sql` (la consulta del §6); este informe y la [guía de defensa](../defensa/fase-6.md) |

### Decisiones tomadas sin consultar

- **El histograma va en un `integer[]`** de 61 posiciones, además de la suma exacta: así hay de 5 a 10 veces menos filas.
- **Tramos con el horario.** `stop_sequence` tiene huecos en el 1,5 % de los viajes de Renfe (1.894 de 129.867), así que la parada anterior se le pregunta al horario de esa noche.
- **Ranking:** por el % con menos de 3 min y luego por la media. Las líneas o núcleos sin muestra suficiente van al final, sin puesto.
- **El titular del ranking no corona empates.** Si el primero saca al siguiente menos de 5 puntos, dice «igualadas». Si el primero tiene poca cobertura, también lo dice.
- **Rutas sin enrutador** y **gráficos sin librería** (ADR 0027). El gráfico de 24 horas se dibuja a su tamaño natural y, si no cabe, se desplaza dentro de su caja.
- **Las horas van en el orden del día de servicio**, de las 4 a las 3: lo de pasada la medianoche, al final.
- **Periodo:** por defecto, 28 días hasta ayer; como mucho, 400. Un periodo sin datos da un 200 con ceros, no un 404.
- **Se corrigió un fallo de la fase 4:** los endpoints con `@RespuestasDeError` salían en OpenAPI **sin la respuesta 200**, y sin su ejemplo. La prueba de la fase 4 solo miraba `/api/nucleos`.

## 4. Pruebas

| | `main` (`d3fc2f6`) | Ahora |
|---|---:|---:|
| `./mvnw verify` | 133 (1 omitida) | **159 (1 omitida)** |
| `npm test` (Vitest) | 61 | **76** |
| e2e (Playwright: escritorio y móvil) | 20 | **28** |

**Backend:**

- **`EstadisticasIntegracionTests`** (17, con PostGIS en Testcontainers). Usa 22 filas de `retrasos` escritas a mano, con las cifras **calculadas a mano en los comentarios**. Cubre:
  - las **horas en Madrid**: un adelanto de −1, la franja de las 9 en horario de invierno;
  - el **día de servicio** pasada la medianoche y el **cambio de hora**, con las dos 02:30;
  - las **inferidas**, las paradas **sin hora**, el **autobús**, los **cancelados** (también uno pasada la medianoche) y la cobertura por viajes y por paradas;
  - los **tramos** con `LAG` y un hueco en `stop_sequence`;
  - **por estación**, la **idempotencia**, lo que **se conserva** sin horario ni `posiciones`, y la **nocturna**;
  - los **cerrojos**: el mismo día, con la importación en marcha o con una tabla retenida, falla en ~1 s sin dejar el día a medias;
  - la **API**:
    - toda España, con el histograma sumado en SQL: mediana 2 y p90 18 a mano;
    - el núcleo, la línea (media 5,5, % con 5 o menos 69,2, mediana 4, p90 18, destino 2,5) y la estación;
    - los casos sin datos, el periodo por defecto y los errores;
    - que **antes de publicar no salga ninguna cifra**;
    - Actuator y OpenAPI.
- **`AcumuladoTests`** (5): compara el percentil con `percentile_disc` en 500 listas al azar, y prueba la muestra mínima, los redondeos, el % con 5 o menos (calculado sobre las paradas, 71,4 y no 71,5) y el ranking.
- **`EjemplosApiTests`:** los cuatro ejemplos nuevos.

**Web:**

- **Vitest** (15 nuevas):
  - las rutas, incluida una con un `%` mal formado;
  - los textos: espacio de no separación, «60 min o más» y las horas de 4 a 3;
  - el titular del ranking: igualadas y poca cobertura;
  - la página de línea:
    - el contexto obligatorio y las dos coberturas;
    - la tabla de cada gráfico, con el valor escrito en cada columna;
    - el reparto con su texto, el % con 5 o menos y los cancelados como cota inferior;
    - la flecha de los tramos, que no se lee;
  - que el periodo llegue a la petición, con el error ligado a los campos;
  - sin publicar, 404, los rankings y la estación.
- **e2e** (4 nuevas × 2 proyectos), contra la build de producción con la CSP. La línea usa un JSON con datos suficientes. Prueban:
  - el gráfico de 20 horas a tamaño natural, con 18 columnas;
  - el reparto, los tramos y las dos coberturas;
  - las migas hasta toda España;
  - el periodo en la URL;
  - el paso del mapa a la puntualidad;
  - que en el móvil no haya desplazamiento lateral y que AvPág desplace la página;
  - 0 errores de consola y 0 violaciones de la CSP.

  Hubo que afinar una prueba del mapa, porque la ficha de la estación tiene ahora dos tablas.

### Lo que se vio por el camino

- **Docker Desktop no arrancaba.** No podía borrar sockets AF_UNIX viejos de la sesión anterior. Se resolvió renombrando `%LOCALAPPDATA%\Docker\run` y `docker-secrets-engine`, sin tocar los volúmenes; hay una nota en la carpeta compartida.
- **`ProgramacionHorario.alArrancar` borra todos los `trainwatch-horario-*.zip` de `java.io.tmpdir`.** Así le quitó el ZIP a la importación de la ingesta local cuando otra instancia arrancó en la misma máquina. En producción no pasa, porque hay una sola instancia (§7).

## 5. Revisiones independientes

### Backend

Un subagente sin contexto revisó el código y las definiciones, con la pregunta expresa de si alguna cifra podía engañar. **Se corrigió todo lo de gravedad media**:

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 | El `socketTimeout` de 30 s cortaba antes que el plazo de 2 min | Se usa `setNetworkTimeout`, como en la importación. Sin prueba: haría falta una consulta de más de 30 s |
| 2 | **La cifra por viaje engañaba:** la «última parada vista» de un tren que deja de verse pronto inclina la cifra hacia «puntual» | Ahora es la **llegada a destino**, solo de los viajes que llegan |
| 3 | La cobertura solo iba por viajes | Ahora va también **por paradas** |
| 4 | `diasConDatos` contaba días vacíos | Ahora solo cuenta los días con filas |
| 5 | El cambio de esquema del horario no espera a quien lo lee | El cálculo toma el cerrojo de la importación en modo compartido |
| 6-9 | La partición a medio borrar, los tramos sin horario, el redondeo distinto del de SQL y Actuator | Corregidos, con prueba |
| 10 | Pruebas débiles o que faltaban | Añadidas las de cerrojos, la de un cancelado pasada la medianoche y la de lo que se conserva |

### Web

Hubo tres revisiones de lo nuevo, todas con un subagente:

- **la independiente del código**;
- **`web-design-guidelines`**, con las reglas de Vercel;
- **`design:accessibility-review`**, la de WCAG 2.1 AA, que **no encontró nada bloqueante**. Calculó los contrastes: el texto secundario da 8,3:1 y los enlaces, 8,7:1.

Las tres se corrigieron, con prueba donde cabía:

| Hallazgo | Revisión | Qué se hizo |
|---|---|---|
| La página de línea no enseñaba la cobertura del núcleo | Código | Ahora salen las dos |
| **«La más puntual» coronaba diferencias que la muestra no permite** (Asturias, con un 11 % de cobertura, delante de Rodalies por 0,1 puntos) | Código | Ahora dice «igualadas» por debajo de 5 puntos, y avisa de la cobertura baja |
| El e2e de la línea no pintaba ningún gráfico (todo «insuficiente») | Código | Se añadió un JSON con datos suficientes |
| El eje se rotulaba mal con medias de menos de 0,2 min | Código | Pasos sin acumular errores y claves por índice |
| Una URL con `%` mal formado dejaba la página en blanco | Código | `try`, y la ruta se trata como inexistente |
| La madrugada salía la primera en la tabla por horas | Código | Ahora van de 4 a 3 |
| Con 24 columnas, el gráfico encogía el texto a 3-6 px | Accesibilidad | El gráfico va a tamaño natural y se desplaza; con más de 12 columnas, rotula de 3 en 3 |
| El borde de los campos de fecha tenía un contraste de 1,76:1 | Accesibilidad | Ahora 3,8:1 (`#6b7079`) |
| Los estados de carga no se anunciaban | Accesibilidad y Vercel | `role="status"` y `aria-live` |
| Las cifras se partían de su unidad («32,7 / %») | Vercel | Espacio de no separación |
| El botón se desactivaba con un periodo al revés, sin error ligado a los campos | Accesibilidad y Vercel | El botón sigue activo; el error se liga con `aria-invalid` y `aria-describedby` |
| La página se desplazaba dentro de un `div` | Accesibilidad y Vercel | Ahora se desplaza el documento (`html.con-desplazamiento`) |
| Las cabeceras numéricas iban alineadas al revés que sus cifras; los separadores de las migas y la flecha de los tramos se leían en voz alta; la figura no tenía nombre | Vercel y Accesibilidad | Corregido |

Lo menor que queda: `translate="no"` solo en la marca y en los nombres de línea de la estación, y el texto de «Cargando las estadísticas…».

## 6. Comprobación con datos reales (provisional)

**Datos:**

- ingesta local contra los feeds de Renfe, el 21-11-2026 de 12:46 a 14:32 de Madrid, con unos minutos parados en tres reinicios para cambiar el jar;
- **4.036 filas** de `retrasos` de **461 viajes**, con **214 inferidas** (5,3 %);
- **0 errores** en el registro.

**Cómo se calculó:**

- Se recalculó el día con `POST /actuator/estadisticas`.
- Cada cifra se recalculó con la consulta de `docs/informes/fase-6-comprobacion.sql`, solo sobre las filas registradas antes de ese cálculo (`registrado <= estad_dias.calculado`), porque la ingesta seguía escribiendo.
- La API, en dos sitios: la ingesta, con la configuración de producción (muestra mínima de 30), y una segunda instancia de solo lectura sobre la misma base, con la muestra mínima a 5, porque con 1 h 45 min ninguna hora ni ningún tramo llega a 30.

| Cifra | SQL directo sobre `retrasos` | API | ¿Igual? |
|---|---|---|---|
| **1. Media de la C5 de Madrid a las 13:00** | 8,3 min · 227 paradas · 21 viajes | `porHora[13]`: 8,3 min · 227 · 21 (mínimo 5). Con el mínimo de 30: «datos insuficientes: 21 viajes» | **Sí** |
| **2. Porcentajes de la C5, todo el día** | < 3 min: **30,1 %** · ≤ 5 min: **60,5 %** · media 7,8 · mediana 4 · p90 18 · 395 paradas · 30 viajes | `resumen` con el mínimo de 30: 30,1 · 60,5 · 7,8 · 4 · 18 · 395 · 30 | **Sí** |
| **3. Tramo Embajadores → Laguna (C5)** | +5,2 min de media · 12 pasos · el 100 % pierde 2 min o más | `tramos[0]`: +5,2 · 12 · 100 % (mínimo 5) | **Sí** |

**Lo que dicen estas cifras, sin publicarlas:**

- **La C5 iba mal ese mediodía:** solo el 30 % de las paradas con menos de 3 min.
- **El tramo Embajadores → Laguna suma 4 min a casi todos los trenes:** 10 de 12, todos los posteriores a las 13:00.
  - Una suma tan constante en cada tren parece más el horario (o cómo recalcula Renfe el retraso en ese punto) que una incidencia.
  - Es la pregunta que estas estadísticas permiten hacer; se responderá con semanas de datos.

La **comprobación definitiva** se repetirá contra producción, con días completos y la muestra mínima de 30. La hará otra sesión con la misma consulta, que solo necesita cambiar la fecha, la línea y el tramo.

## 7. Qué quedó fuera o pendiente

- **El despliegue y el recálculo en producción.** Al desplegar, la V6 se aplica sola y la tarea empieza esa misma noche.
  - Para tener los días desde el 21-11, hay que recalcularlos a mano **cuanto antes** con `POST /actuator/estadisticas` en el 8081.
  - Lo que sale de `retrasos` se puede recalcular siempre. Lo que sale de `posiciones` (los cancelados) se pierde a las 48-72 h, y los viajes programados, en cuanto el horario ya no cubre ese día.
- **El historial por número de tren** («este R13 de las 14:40 llegó puntual el X % de los últimos 30 días») va a una fase posterior, la de la «ficha de tren e historial». Los agregados no guardan el número de tren.
  - `retrasos` sí guarda **para siempre** el `trip_id` de cada día, que lleva el número dentro (`4023V24219C1` → 24219).
  - Así, se podrá hacer **sin perder historial**: con una tabla nueva en la tarea nocturna y su recálculo desde el 21-11.
- **Los cancelados que solo vienen en `trip_updates`** no se cuentan. Para contarlos, la ingesta tendría que apuntarlos en una tabla propia.
- **`alArrancar` borra los ZIP temporales de otras instancias** (§4).
- **Sin prueba automática:** el plazo de red de 2 min + 1.
- **Las líneas con poco servicio, como la RL4,** tendrán muchas franjas horarias como «datos insuficientes». Si se quiere enseñar más, lo honrado es agrupar en franjas de 2-3 horas, no bajar el umbral.
- **Capturas** de las páginas con datos reales, cuando haya semanas de datos (`frontend/scripts/capturas.mjs`).

## 8. Qué cambiaría del plan

- **§6, fase 6:** la aceptación del plan («cifras comprobadas a mano contra los datos sin procesar en una muestra») se cumple ya, de forma provisional. Las cifras **publicables** necesitan cuatro semanas: a partir del 19-12.
- **Despliegue:** el recálculo de los días desde el 21-11 en producción debería ir en cuanto esté la V6 (§7).
- **Una fase de «ficha de tren e historial»**, con el número de tren (§7).
