# ADR 0009 · Particiones diarias para `posiciones` y mensuales para `retrasos`, gestionadas por la aplicación

**Fecha:** 20-11-2026 · **Estado:** aceptada, con un cambio en la fase 3b (al final) · **Fase:** 3

## Contexto

`posiciones` recibe unas 0,26 M filas al día (solo los cambios, fase 0) y solo hace falta guardarlas 48 h. `retrasos` recibe ~51.000 al día (~19 M al año) y se guarda siempre: es la base de las estadísticas. Borrar las posiciones viejas con `DELETE` dejaría filas muertas y obligaría a depender de `VACUUM` en un servidor pequeño.

## Decisión

- **Particionado declarativo de PostgreSQL** (`PARTITION BY RANGE`):
  - `posiciones` por `instante` (la marca del tren en el feed), **una partición por día de Madrid**: `posiciones_p20261120` va de las 00:00 a las 00:00 hora local (23 o 25 h los días de cambio de hora);
  - `retrasos` por `fecha_servicio`, **una por mes**: `retrasos_p202611`.
- **La aplicación las crea y las borra** (`GestorParticiones`), desde la ingesta: al arrancar y luego como mucho una vez por hora o al cambiar el día.
  - Crea el día anterior, el actual y los dos siguientes; y el mes anterior, el actual y el siguiente.
  - **Borra con `DROP TABLE`** cada partición diaria cuyo día entero queda más atrás que la retención (48 h). Así en la base hay entre 48 y 72 h. Los retrasos no se borran nunca.
- **Sin partición por defecto:** una fila fuera de rango falla y se cuenta como error, en vez de acabar en una partición `DEFAULT` que luego impide crear la buena.

## Alternativas

- **`DELETE … WHERE instante < now() - 48 h`** cada hora: sin particiones, pero borra ~11.000 filas por hora, hincha la tabla y los índices y depende del autovacuum.
- **`pg_partman` o `pg_cron`:** hacen lo mismo y están probados, pero son extensiones que hay que instalar en la imagen y en el servidor. Para dos tablas con reglas simples bastan 150 líneas de Java con su prueba.
- **Particiones por día UTC:** más simples, pero un día de servicio de Renfe se partiría en dos a la 01:00 de Madrid.
- **Retrasos por día o por año:** por día serían 365 tablas al año de 51.000 filas; por año, una partición de 19 M filas que no se puede archivar por meses.

## Consecuencias

- Borrar un día de posiciones es instantáneo y no deja nada que limpiar.
- Las consultas que filtran por `instante` o por `fecha_servicio` leen solo sus particiones (*partition pruning*).
- La clave primaria de `retrasos` tiene que incluir la columna de partición: `(fecha_servicio, trip_id, stop_sequence)`.
- Si la aplicación ha estado parada más de dos días, al arrancar crea las particiones que faltan antes de escribir.

## Cambio en la fase 3b: el mantenimiento sale de la ingesta y no espera cerrojos

La revisión de la fase 3 vio que el `DROP TABLE` de una partición pide un cerrojo exclusivo sobre `posiciones` entera y se hacía **desde el hilo de la ingesta**, sin `lock_timeout`. Con un lector largo (la API de la fase 4), el `DROP` esperaría, y detrás de él todos los `INSERT` de la ingesta. Una prueba lo confirma: con el `DROP` de antes y un lector abierto, una escritura de la ingesta se quedaba 14,7 s bloqueada (hasta el `statement_timeout`).

- **Tarea programada aparte** (`GestorParticiones.programado`, cada hora, `trainwatch.directo.mantenimiento-particiones`), además de al arrancar. La ingesta ya no la llama. El programador tiene tres hilos.
- **`SET lock_timeout`** (`trainwatch.directo.espera-cerrojo-particiones`, 5 s) en cada sentencia: si no consigue el cerrojo, falla en vez de quedarse en la cola.
- **`ALTER TABLE posiciones DETACH PARTITION … CONCURRENTLY`** antes del `DROP TABLE`. Separa la partición sin bloquear las escrituras en `posiciones` (pide `SHARE UPDATE EXCLUSIVE`, compatible con los `INSERT`) y el `DROP` solo bloquea la tabla ya separada. No se puede ejecutar dentro de una transacción: va en una conexión en *autocommit*.
- **Si algo falla, se retoma en la pasada siguiente:** un `DETACH` a medias se termina con `DETACH PARTITION … FINALIZE`, y una tabla separada que no llegó a borrarse se borra.
- **Crear particiones** también lleva `lock_timeout`; se crean con días (o un mes) de adelanto, así que un fallo se reintenta a la hora siguiente.
