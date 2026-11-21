# ADR 0025 · Qué es una cifra de puntualidad de TrainWatch

**Fecha:** 21-11-2026 · **Estado:** aceptada · **Fase:** 6 · **Se apoya en:** ADR 0012 (inferidas) y 0014 (hora programada)

## Contexto

La fase 6 publica cifras propias de puntualidad («la R2 Sud llega con 4 min de media a las 8:00») para enseñarlas en una entrevista y en LinkedIn. Una cifra que no se pueda explicar, o que engañe, es peor que no tenerla. Lo que hay es `retrasos`: el retraso final de cada tren en cada parada, en **minutos enteros** (Renfe da el retraso en múltiplos de 60 s; la ingesta lo pasa a minutos con `Math.round`, sin pérdida), con la fecha de servicio, la hora programada de llegada (desde la V5) y si la parada es inferida (desde la V4).

Hay que decidir la unidad, qué entra, cómo se asigna la hora y el día, y cuándo una cifra tiene muestra suficiente.

## Decisión

### 1. La unidad: la parada, y el viaje como cifra secundaria

- **La cifra principal es por parada:** la media de minutos de retraso al llegar a cada parada vista, y el reparto de esas paradas en cuatro tramos, los mismos que los colores del mapa:
  - **menos de 3 min** (incluye los adelantos: un retraso negativo cuenta tal cual en la media y aquí);
  - **de 3 a 5 min**;
  - **de 6 a 15 min**;
  - **16 min o más**.

  Es lo que vive el viajero en su andén, cualquiera que sea su parada, y es la muestra más grande. «Puntual» en los textos es **menos de 3 min**.
- **Además, la mediana y el percentil 90** (por parada), que salen de un histograma por minuto (ADR 0026). La media sola engaña cuando unos pocos trenes llevan 40 min: la mediana dice lo típico y el p90, lo malo.
- **Por viaje, como cifra secundaria:** el retraso al **llegar a su destino** (la última parada que le da el horario), media y % de menos de 3 min, **solo de los viajes que se vieron llegar a él**. Responde «¿llegó a tiempo?». Los que dejan de verse antes se cuentan («de 1.843 viajes, 1.520 vistos en destino»), pero no entran: han hecho menos recorrido y han tenido menos ocasión de acumular retraso, así que meterlos con su última parada vista inclinaría la cifra hacia «puntual».
- **No se mezclan:** cada número dice si es «de las paradas» o «de los viajes».

### 2. Qué entra

| Entra en las cifras | Se cuenta aparte (y se dice cuántas) | No entra nunca |
|---|---|---|
| Paradas **vistas** (`inferido = false`) de trenes vistos en directo, con hora programada | Paradas **inferidas** (ADR 0012): su retraso es una copia del de la parada anterior | Trenes que no aparecen en el feed: no son cancelados ni retrasos; bajan la **cobertura** |
| | Paradas **sin hora programada** (filas anteriores a la V5) | Autobuses de sustitución (`route_type` 3) |
| | Viajes **cancelados** con `CANCELED` explícito: se cuentan por día y línea, nunca como retraso | Filas sin núcleo o sin línea |

- **Cobertura:** de los viajes de tren que el horario daba ese día, cuántos dejaron alguna fila en `retrasos`; y, **por paradas**, cuántas de sus paradas programadas tienen fila (un tren que solo se ve en 2 de sus 20 paradas cuenta entero como viaje, no como paradas). Va en cada respuesta, del núcleo y de la línea: una línea con un 40 % de cobertura no dice lo mismo que una con un 95 %.

### 3. La franja horaria: la hora programada, en hora de Madrid

- La hora es la de **`llegada_programada` en `Europe/Madrid`** (`extract(hour FROM llegada_programada AT TIME ZONE 'Europe/Madrid')`), no `registrado` (cuándo se cerró el retraso): con un tren 20 min tarde, `registrado` caería en otra franja.
- **Cambio de hora:** el día de octubre, las 02:00-02:59 pasan dos veces y las dos cuentan en la franja de las 2; el día de marzo, la franja de las 2 no existe. Es lo que dice el reloj de la estación.
- **Filas antiguas sin hora (`NULL`, antes de la V5):** **fuera de todas las cifras**, también de las diarias, y contadas aparte («paradas sin hora quitadas»). Así el día es la suma exacta de sus horas y ninguna cifra mezcla dos criterios. Producción empezó con la V5 ya aplicada, así que allí no hay ninguna; solo afecta a bases de prueba o locales con datos del 20-11 por la mañana.

### 4. El día: el día de servicio

- Cuenta **`fecha_servicio`**, no el día del calendario: un tren que sale a las 23:50 y llega a las 00:30 cuenta entero en el día en que salió, y su parada de las 00:30 va a la franja de las 0 de ese día de servicio.
- **Día de la semana** (1 = lunes … 7 = domingo) y **semana** (ISO, de lunes a domingo), también del día de servicio. Una semana a medias en los bordes del periodo dice cuántos días tiene.

### 5. Muestra mínima: 30 viajes

- Una cifra con **menos de 30 viajes** no se enseña: va como «datos insuficientes» (`suficiente: false`, valores `null`), pero con su número de viajes. El umbral es la propiedad `trainwatch.estadisticas.muestra-minima`.
- **Se cuentan viajes, no paradas:** las paradas de un mismo tren no son independientes (si sale tarde, llega tarde a todas). 300 paradas de 10 trenes son 10 observaciones, no 300.
- **Por qué 30:** con una desviación típica del retraso de unos 4 min (orden de magnitud de lo visto en las fases 3 y 4), el error típico de la media con 30 viajes ronda los 0,7 min, por debajo del minuto que es la resolución de los datos. Con 10 viajes sería 1,3 min: la cifra bailaría de un día a otro por azar. En porcentajes, con 30 viajes y un 80 % de puntuales, el error típico es de unos 7 puntos: suficiente para decir «la mayoría llega a tiempo», no para comparar un 81 % con un 83 %.
- Los **tramos** piden lo mismo: 30 pasos de trenes por ese par de estaciones.

### 6. Publicación

Nada público antes de tener semanas de datos (punto 8 de «Cambios tras la fase 0»). La propiedad `trainwatch.estadisticas.publicar-desde` (en producción, **19-12-2026**: cuatro semanas completas desde el primer día entero de datos, el 21-11) decide desde qué día la API da cifras. Hasta entonces responde, con el mismo formato, «recogiendo datos desde el …, cifras a partir del …» y sin ninguna cifra. Iván puede cambiar la fecha.

## Alternativas

- **Solo por viaje** (retraso al llegar al destino): la cifra que suele dar un operador, pero con cobertura incompleta el «destino» a menudo no se ve, y pierde la información de dónde se gana o se pierde el tiempo.
- **Solo porcentajes** («el 85 % llega a tiempo»): fáciles de leer, pero un 85 % con los demás a 4 min y otro con los demás a 40 min no son lo mismo. Por eso van con la media, la mediana y el p90.
- **Umbral de 5 min** (el de otros operadores en media distancia): se escoge 3 porque es el primer color del mapa y en Cercanías 5 min es mucho.
- **Contar las inferidas:** hay más filas, pero su retraso es una copia; inflan las paradas «fáciles» de seguir y repiten el retraso de la parada anterior. Se quitan y se dice cuántas.
- **Usar `registrado` cuando falta la hora programada:** daría franjas a las filas antiguas, pero con un criterio distinto y sesgado hacia la hora siguiente en los trenes tarde. Se prefieren menos filas con un solo criterio.
- **Umbral de 10 o de 100 viajes:** con 10 la cifra baila (ver arriba); con 100, la RL4 (pocos trenes al día) no tendría casi ninguna franja en cuatro semanas.

## Consecuencias

- Cada respuesta de la API lleva el periodo, los viajes y paradas usados, cuántas inferidas y sin hora se quitaron, los cancelados y la cobertura del núcleo, para que la cifra se pueda explicar sola.
- Un núcleo con poca cobertura da cifras de **los trenes que se ven**. Si Renfe deja de emitir justo los trenes con problemas, la cifra sale mejor de lo que es; la cobertura es la forma de avisarlo, no de corregirlo.
- Los minutos enteros dan medias con un decimal que son exactas (son medias de enteros), pero un tren con 2 min 59 s de retraso real sale como «menos de 3» o no según cómo lo redondee Renfe.
