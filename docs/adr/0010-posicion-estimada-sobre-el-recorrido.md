# ADR 0010 · La posición del tren se estima con el horario, el retraso y el recorrido

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 3

## Contexto

La fase 0 midió que la posición del feed **salta de estación en estación**: el 78 % está a menos de 50 m de una estación, y su `stop_id` es la parada actual o la última. Si el mapa pintara esa posición, los trenes estarían quietos y luego saltarían kilómetros. La fase 2 dejó proyectada cada parada sobre el recorrido de su línea (`parada_forma.posicion`, la fracción 0..1 de `ST_LineLocatePoint`) y marcó qué viajes encajan (`forma_encaja`, el 93,5 %).

## Decisión

`EstimadorPosicion` calcula, para un tren en marcha que está en (o acaba de dejar) la parada A y va a la B:

```
sale   = salida programada de A + retraso      llega = llegada programada a B + retraso
t      = (ahora − sale) / (llega − sale), entre 0 y 1
avance = avance(A) + t · (avance(B) − avance(A))      (módulo 1 en los recorridos cerrados)
fracción del recorrido = avance, o 1 − avance si el viaje va al revés del dibujo
```

- Con la fracción salen las **coordenadas**: un punto del recorrido, interpolado en el mismo plano de grados en que se calculó `ST_LineLocatePoint`, para que cada parada caiga exactamente en su proyección.
- Y el **rumbo**: el del tramo del recorrido en que cae el punto, geodésico, girado 180° si el viaje va al revés. Se guarda para la futura vista 3D.
- **El feed corrige:** cuando llega una posición con otra parada, A cambia y la estimación parte de ahí. `t` no pasa de 1, así que un tren que va tarde se queda en B hasta que el feed lo mueve; uno adelantado salta a B cuando el feed lo pone allí.
- **En origen o terminado:** en la parada de origen o en la última.
- **Sin recorrido** (viaje sin horario o que no encaja): la posición del feed tal cual, y el rumbo del último desplazamiento de más de 30 m.
- Se recalcula en cada ciclo y se guarda en el `Tren`, y en `posiciones` (`fraccion`, `lat_estimada`, `lon_estimada`, `rumbo`) cuando se escribe la fila.

## Alternativas

- **Interpolar entre dos posiciones del feed:** no sirve; están casi siempre en estaciones y el tren iría siempre una parada por detrás.
- **Línea recta entre estaciones:** atraviesa montañas y curvas, y el recorrido existe para el 93,5 % de los viajes.
- **Calcularlo en PostGIS** (`ST_LineInterpolatePoint`): una consulta por tren cada 7 s. En Java, con los 144 recorridos en memoria, son microsegundos.
- **Interpolar por metros reales** en vez de por la fracción en grados: algo más exacto en recorridos muy curvos, pero la fracción de las paradas viene en grados y mezclar las dos medidas desplazaría las paradas.

## Consecuencias

- Entre dos paradas el tren avanza a velocidad constante, sin acelerar ni frenar.
- El retraso es el de la **próxima** parada y en minutos enteros: la posición puede ir hasta un minuto adelantada o atrasada.
- El navegador (fase 5) puede repetir el mismo cálculo entre dos mensajes del SSE para mover el tren con suavidad.
