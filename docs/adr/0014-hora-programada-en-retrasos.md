# ADR 0014 · `retrasos` guarda la hora programada de llegada a cada parada

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 4 · **Completa:** ADR 0012 y 0013

## Contexto

La fase 6 publicará estadísticas por franja horaria («la R2 Sud llega con 4 min de media a las 8:00»). Para eso hace falta la hora **programada** de cada parada de cada viaje, y `retrasos` no la guardaba:

- el horario de ese día **se sustituye cada noche** (ADR 0006): una semana después ya no se puede buscar a qué hora tocaba pasar el `4023V24219C1` por la 69103;
- `registrado` es **cuándo se cerró** el retraso (al ver al tren ir a la parada siguiente), no cuándo tocaba pasar. Con un tren 20 min tarde, o una parada inferida (ADR 0012), cae en otra franja.

## Decisión

1. **Migración V5:** `ALTER TABLE retrasos ADD COLUMN llegada_programada TIMESTAMPTZ`. Sin `DEFAULT`: en una tabla particionada, añadir una columna que admite `NULL` no reescribe ninguna partición.
2. **La rellena la ingesta al cerrar cada parada**, también las inferidas: `Viaje.instante(fechaServicio, llegada_s)`, la misma cuenta que usa la posición estimada. Las horas GTFS cuentan desde «mediodía menos 12 h» del día de servicio, así que valen las de más de 24:00 (un tren de las 23:50 que llega a las 24:10 es el día siguiente a las 00:10) y los dos días de cambio de hora.
3. **La llegada, no la salida:** es lo que espera el viajero en el andén y lo que mide el retraso de Renfe en `trip_updates` (el de la llegada a la próxima parada). En la primera parada son iguales.
4. **`NULL`** en las filas anteriores a la V5 (su horario ya no está) y si no se supiera la fecha. Un tren sin horario no cierra paradas (no hay `stop_sequence`), así que no llega a escribir nada.
5. Si la misma parada se vuelve a cerrar, se conserva la hora que hubiera (`coalesce`): la hora programada de una parada no cambia.

## Alternativas

- **Calcularla en la fase 6 cruzando con el horario:** solo vale para el horario de hoy. Para el histórico haría falta guardar todos los horarios (unas 1,8 M horas de paso al día) o una tabla de horarios «archivados»; es mucho más volumen que una columna.
- **Guardar `llegada_s` (segundos) en vez de un `TIMESTAMPTZ`:** ocupa menos, pero cada consulta tendría que rehacer la cuenta de «mediodía menos 12 h» con la fecha y la zona; un instante se agrupa directamente por hora local (`extract(hour from llegada_programada AT TIME ZONE 'Europe/Madrid')`).
- **Usar `registrado`:** es lo que había, y es incorrecto para las franjas (ver el contexto).

## Consecuencias

- La fase 6 puede agrupar por franja horaria y día de la semana **programados** desde hoy; los días anteriores a la V5 quedan sin franja (`NULL`) y conviene excluirlos o contarlos aparte.
- Unos 8 bytes más por fila (~51.000 filas al día): despreciable.
- Pruebas: cierre normal (10:10, no la salida de las 10:11), cierre inferido, viaje que pasa la medianoche con un calendario de varios días (fecha deducida), día del cambio de hora (29-03-2026), tren sin horario y, en la integración, la fila real del C1 de Valencia (10:24, hora de Madrid) y una fila sin hora que queda en `NULL`.
