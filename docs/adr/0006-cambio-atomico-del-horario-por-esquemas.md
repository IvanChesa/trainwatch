# ADR 0006 · Cambio atómico del horario renombrando esquemas

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 2

## Contexto

Cada noche llega un horario nuevo entero (1,8 M horas de paso). La importación tarda unos 15 s, y en ese tiempo la aplicación tiene que seguir respondiendo con el horario anterior. Si la importación falla a medias (fichero cortado, una hora mal escrita, la base de datos se queda sin espacio), **el horario anterior tiene que seguir intacto**. Y no puede haber un momento en que se vean las tablas a medio llenar ni mezcla de los dos horarios.

## Decisión

Dos esquemas de PostgreSQL:

- **`horario`**, el vivo: lo que lee la aplicación. Lo define la migración de Flyway V2 (tablas e índices).
- **`horario_carga`**, el de la importación. Se crea en cada importación con `CREATE TABLE horario_carga.x (LIKE horario.x INCLUDING ALL EXCLUDING INDEXES)`, se llena con `COPY`, y después se le añaden las claves y los índices leyéndolos del catálogo (`pg_get_constraintdef`, `pg_get_indexdef`) del esquema vivo.

Al terminar bien, en **una transacción**:

```sql
SET LOCAL lock_timeout = '30s';
ALTER SCHEMA horario RENAME TO horario_anterior;
ALTER SCHEMA horario_carga RENAME TO horario;
COMMIT;
```

Después, en otra transacción y fuera del camino crítico, `DROP SCHEMA horario_anterior CASCADE`. Si falla cualquier paso antes del cambio, se borra `horario_carga` y `horario` no se ha tocado.

**Por qué funciona:** renombrar un esquema solo cambia una fila del catálogo (`pg_namespace`). No mueve datos y tarda milisegundos (3-8 ms medidos con el horario real). Por el MVCC del catálogo, las demás sesiones ven el esquema viejo hasta el `COMMIT` y el nuevo después, nunca una mezcla. Las consultas en marcha terminan con las tablas viejas, porque PostgreSQL las tiene ya resueltas por OID. El `DROP` del anterior sí espera a que terminen: por eso va aparte y su fallo no tumba la importación (se reintenta al empezar la siguiente).

**Flyway sigue siendo la única definición del esquema:** la importación copia la estructura del vivo, así que una migración futura (`ALTER TABLE horario.trips ADD COLUMN …`) se propaga sola a la siguiente carga.

## Alternativas

1. **`TRUNCATE` + carga en una transacción.** Atómico, pero `TRUNCATE` bloquea las tablas (`ACCESS EXCLUSIVE`) durante toda la carga: 15 s sin poder leer el horario cada noche. Y con los índices puestos la carga va más lenta.
2. **`DELETE` + carga en una transacción.** Los lectores no se bloquean, pero borrar e insertar 1,8 M filas genera 3,6 M de tuplas muertas cada noche, hincha las tablas y los índices y obliga a un `VACUUM` grande.
3. **Tablas con sufijo y renombrado de tablas** (`stop_times_nueva` → `stop_times`). Igual de atómico, pero hay que renombrar ocho tablas y sus índices (los nombres de índice son únicos por esquema) y es fácil olvidarse de uno. Con esquemas, los nombres de tablas e índices no cambian.
4. **Una columna de versión** (`version_horario` en cada fila) y una tabla que dice cuál es la vigente. Sin DDL, pero todas las consultas tienen que filtrar por versión, los índices crecen con dos versiones y hay que borrar la vieja igual.
5. **Vistas que apuntan a la versión vigente** (`CREATE OR REPLACE VIEW`). Funciona, pero añade una capa y los planes de las vistas complican el diagnóstico. No aporta nada frente a renombrar el esquema.

## Consecuencias

- **No puede haber claves foráneas que crucen el límite del esquema `horario`**: al renombrarlo, apuntarían al esquema viejo y el `DROP … CASCADE` las borraría. Las tablas de las fases 3 y 6 (posiciones, retrasos) guardan `trip_id` y `stop_id` como texto. Dentro de `horario` tampoco hay claves foráneas (`LIKE` no las copia); en su lugar, la validación cuenta las horas de paso sin viaje o sin parada (0 en el horario real del 20-11).
- Hace falta espacio para dos horarios a la vez durante la importación (unos 400 MB con índices).
- El registro de importaciones (`importacion_horario`) está en `public`, fuera del cambio.
- La comprobación previa al cambio rechaza un horario vacío o con menos del 50 % de los viajes del actual (`trainwatch.horario.minimo-relativo`): un fichero cortado no sustituye a uno bueno.
