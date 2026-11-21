Eres la sesión de programación de **TrainWatch**, la fase 6 («Estadísticas»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0» (puntos 3, 4, 7 y 8), §2.4 y la fase 6 en §6;
- los informes de las fases 0 a 5, también `fase-3b.md`;
- los ADR, sobre todo el 0009 (particiones) y el 0012 (paradas inferidas);
- el contrato de la API en `docs/api/`.

Construye sobre lo fusionado en `main`. Si las fases 3b, 4 o 5 no están fusionadas, o `./mvnw verify` o `npm test` fallan, **para** y explica por qué en `docs/informes/fase-6.md`.

## Objetivo

**Cifras propias de puntualidad**, honestas y comprobables, que Iván pueda enseñar en una entrevista y publicar en LinkedIn. Por ejemplo, «la R2 Sud llega con 4 min de media a las 8:00». Una cifra que no se pueda explicar o que engañe es peor que no tenerla.

## Lo que ya hay

- La tabla `retrasos`: el retraso final de cada tren en cada parada, en minutos enteros, con `fecha_servicio`, `trip_id`, `stop_sequence`, `stop_id`, `route_id`, `linea`, `nucleo`, `registrado` e `inferido`. Particiones mensuales que se guardan siempre.
- `posiciones` solo dura 48-72 h, y el horario (`horario`) **se sustituye entero cada noche**. Lo que haga falta de ellos para las estadísticas hay que guardarlo antes de que desaparezca.

## Qué construir

1. **Definiciones, antes que código.** Escríbelas en el informe y en un ADR:
   - **Puntualidad:** el plan pide medias y porcentajes, porque el retraso llega en minutos enteros. Propuesta: media de minutos, y los porcentajes de paradas con menos de 3 min, de 3 a 5, de 6 a 15 y 16 o más (los mismos tramos que los colores del mapa). Elige y justifica: ¿por parada, por viaje (retraso al llegar a su última parada vista) o las dos?
   - **Qué entra:** solo trenes vistos en directo; sin autobuses (`route_type` 3). Las paradas `inferido` no entran en las cifras principales y se cuentan aparte. Los `CANCELED` explícitos se cuentan aparte, nunca como retraso. Un tren que no aparece no cuenta como cancelado.
   - **Franja horaria:** la **hora programada** de la parada en hora de Madrid, no la hora en que se cerró el retraso (`registrado`). La fase 4 añadió esa hora a `retrasos` (mira su informe y su ADR). Decide qué pasa con las filas antiguas que no la tienen y explícalo en el ADR.
   - **Día:** el día de servicio (`fecha_servicio`), no el día del calendario: un tren de las 00:30 cuenta en el día anterior.
   - **Muestra mínima:** por debajo de un número de viajes, la cifra no se enseña («datos insuficientes»). Elige el umbral y explícalo.
2. **Agregados que se guardan para siempre** (Flyway):
   - **Por día de servicio, núcleo, línea y hora:** guarda sumas y recuentos, o un histograma por minutos, nunca medias. Así se combinan días y semanas sin perder exactitud. Si guardas el histograma, salen los percentiles.
   - **Por estación:** para la ficha de la estación de la fase 5.
   - **Tramos:** los minutos que se ganan o pierden entre dos paradas consecutivas del mismo viaje, por par de estaciones y línea. Es lo que responde «dónde se pierde el tiempo».
   - **Cancelaciones explícitas** por día y línea, guardadas antes de que se borre su partición de `posiciones`.
   - **Cálculo:** una tarea programada por la noche, cuando ha terminado el día de servicio y **antes** de que se cambie el horario. Tiene que ser **idempotente**: recalcular un día lo sustituye entero en una transacción. Añade una forma de recalcular un intervalo de fechas a mano (propiedad o endpoint de Actuator, no público).
   - Que la tarea no compita con la ingesta: el mismo cuidado con los bloqueos que en la fase 3b.
3. **API** (en el mismo estilo que la fase 4: OpenAPI con ejemplos en español, `ProblemDetail` y caché):
   - por núcleo: ranking de líneas;
   - por línea: puntualidad por hora y por día de la semana, evolución semanal y los tramos con más retraso;
   - por estación: su puntualidad.

   Con `desde` y `hasta`, y por defecto las últimas 4 semanas. Cada respuesta dice el periodo, el número de viajes y paradas, cuántas inferidas se han quitado y la **cobertura** del núcleo. Un JSON de ejemplo por endpoint en `docs/api/`.
4. **Web (React):**
   - una página por línea con la puntualidad por hora, por día y semana a semana, y sus tramos con más retraso;
   - una página del núcleo con el ranking de líneas;
   - la RL4, como cualquier otra línea, sin destacar (decisión de Iván del 21-11);
   - la puntualidad en la ficha de la estación;
   - enlaces desde el mapa y la lista.

   Gráficos accesibles: cada gráfico tiene al lado su **tabla** con los mismos datos, y la información no depende solo del color. Si añades una librería de gráficos, que sea ligera y explícala en un ADR. SVG propio también vale.

   Siempre a la vista: el periodo, «solo trenes vistos en directo», la cobertura del núcleo, «Datos: Renfe (CC BY 4.0)» y el aviso de proyecto no oficial.
5. **Nada público antes de tiempo.** El plan pide no publicar cifras hasta tener semanas de datos.
   - Pon una propiedad con la fecha desde la que se publica, o un número mínimo de días con datos.
   - Hasta entonces, las páginas dicen «recogiendo datos desde el …, cifras a partir del …».
6. **Pruebas:**
   - **cálculo** con Testcontainers y un conjunto pequeño de filas escritas a mano, cuyos resultados se calculan a mano en la propia prueba: medias, porcentajes, horas en Madrid (incluido un día de cambio de hora), el día de servicio pasada la medianoche, las inferidas, las canceladas y la muestra mínima;
   - idempotencia: recalcular un día da lo mismo;
   - cada endpoint (contrato, errores, sin datos);
   - el frontend con Vitest y Testing Library, y el e2e de Playwright con una página de línea.
7. **Comprobación con datos reales** (la aceptación del plan: «cifras comprobadas a mano contra los datos sin procesar en una muestra»):
   - deja la ingesta en marcha en local al menos unas horas, o usa lo que ya haya en la base local;
   - elige 3 cifras (una media por línea y hora, un porcentaje y un tramo) y recalcúlalas con SQL directo sobre `retrasos`;
   - pon en el informe las consultas, los resultados y la comparación con lo que da la API.

   Si hay pocos datos, dilo y apunta las cifras igualmente: no se publican.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-6-estadisticas`;
  - especificación en el informe, con las definiciones del punto 1;
  - pruebas;
  - informe `docs/informes/fase-6.md`;
  - **revisión independiente** con un subagente sin contexto, también de las definiciones (¿alguna cifra puede engañar?);
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - guía de defensa `docs/defensa/fase-6.md`:
    - el SQL de los agregados (`GROUP BY`, funciones de ventana como `LAG` para los tramos y percentiles);
    - por qué agregados precalculados y no consultas al vuelo;
    - los sesgos y cómo se cubren: inferidas, trenes no vistos, núcleos con poca cobertura y minutos enteros;
    - cómo explicar en una entrevista de dónde sale una cifra.
- **ADR:** los que hagan falta (definiciones, franja horaria y agregados, librería de gráficos).
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y Docker Desktop arrancado.
- **Límites:** no toques nada fuera de esta carpeta. Nada de pago. Sin push.
- **Presupuesto limitado.**
  - Si no llegas, este es el orden de prioridad: definiciones y ADR; agregados y su tarea; pruebas del cálculo; API; comprobación con datos reales; páginas de la web; e2e.
  - Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - las definiciones elegidas;
  - qué funciona;
  - las 3 cifras comprobadas a mano;
  - commit de fusión;
  - pruebas;
  - lo que queda.
