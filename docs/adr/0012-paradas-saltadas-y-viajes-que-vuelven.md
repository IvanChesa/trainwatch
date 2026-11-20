# ADR 0012 · Las paradas saltadas se cierran como inferidas, y un viaje que desaparece a mitad se recuerda 3 h

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 3b

## Contexto

El retraso final de cada parada sale de `trip_updates`: el feed da el retraso de la **próxima** parada, y cuando la próxima pasa a ser otra, el último retraso conocido es el de la parada que queda atrás (ADR 0008, fase 3). La revisión de la fase 3 encontró dos huecos que **sesgan las estadísticas sin dejar rastro**:

1. **Paradas saltadas entre dos muestras.** Renfe publica cada ~20 s. Si entre dos muestras la próxima parada pasa de la *i* a la *i+2* o más (paradas muy seguidas, una muestra que falta, un tren que no para), solo se cerraba la *i*. Las intermedias no dejaban fila ni sumaban a `sinCerrar`.
2. **Viajes que vuelven.** Un viaje que falta en 3 muestras completas seguidas se borra del seguimiento. Si luego vuelve más adelante en su recorrido, se trataba como nuevo (`previo == null`): las paradas por las que pasó mientras no estaba se perdían igual.

Las dos cosas tiran de las estadísticas hacia los tramos «fáciles» de seguir y no se notaban en ninguna métrica.

## Decisión

- **Paradas saltadas:** al pasar de la *i* a la *j > i+1*, se cierran todas, de la *i* a la *j−1*. La *i* con su último retraso, como siempre; las intermedias, **con ese mismo retraso y marcadas como inferidas**.
- **Columna `inferido`** en `retrasos` (migración V4, `BOOLEAN NOT NULL DEFAULT false`). Las estadísticas pueden quitarlas, contarlas aparte o usarlas. Un cierre inferido **no pisa** uno visto (`ON CONFLICT … WHERE retrasos.inferido OR NOT EXCLUDED.inferido`); uno visto sí sustituye a uno inferido.
- **Métrica** `trainwatch.directo.retrasos.inferidos` (contador). Si no se sabe el retraso, las saltadas suman a `trainwatch.directo.retrasos.sin.cerrar`, una por parada.
- **Viajes que vuelven:** un viaje que desaparece **a mitad** de su recorrido (no en la última parada, que se cierra como hasta ahora) no se olvida: pasa a una lista de **retirados** durante **3 h**. Si vuelve en ese tiempo, se recupera su seguimiento y las paradas que faltan se cierran como en el punto anterior, **todas inferidas**: el último retraso conocido es de antes de desaparecer. Pasadas las 3 h, se olvida.
- **Tras un reinicio** no se infiere nada: un viaje que se ve por primera vez a mitad de su recorrido empieza a contar desde ahí (como en la fase 3). Inventar las paradas anteriores a la primera vez que se le ve sería adivinar.

## Alternativas

- **No cerrar las saltadas y solo contarlas:** las estadísticas no se inventan nada, pero cada tramo con paradas seguidas queda con menos filas y la media se calcula sobre otra muestra de paradas. Contarlas en una métrica avisa del hueco, pero no lo llena.
- **Interpolar el retraso** entre el de la *i* y el de la *j*: más fino, pero Renfe da minutos enteros (fase 0) y el retraso de la *j* aún no es final. Sería precisión aparente.
- **Conservar el seguimiento más tiempo que el tren visible** (por ejemplo, 10 ausencias en vez de 3): es lo mismo que los retirados, pero mezclado con la regla de desaparición de los trenes del mapa, que tiene que seguir siendo rápida (fase 0: 2-3 muestras).
- **Retirados para siempre (hasta el cambio de día):** cuesta poco, pero un viaje que vuelve 6 h después es casi seguro otro problema (un `trip_id` reutilizado, datos raros). 3 h cubre un viaje entero de Cercanías.

## Consecuencias

- **Más filas en `retrasos`** y algunas con un retraso aproximado. Se pueden filtrar con `WHERE NOT inferido`. La fase 6 (estadísticas) decide si las cuenta.
- **Funciona con el punto 1 de la fase 3b:** si la base de datos falla al buscar un viaje, ese viaje se salta en esa muestra; en la siguiente, el salto de paradas se cierra con esta misma regla (lo que el tren pasó mientras tanto se cierra como visto o inferido, no se pierde).
- **Memoria:** unos cientos de retirados como mucho, que se purgan en cada muestra.
- **Lo que sigue sin cerrarse:** viajes sin horario (no hay `stop_sequence`), viajes que vuelven después de 3 h o tras un reinicio, y viajes que retroceden en el feed.
