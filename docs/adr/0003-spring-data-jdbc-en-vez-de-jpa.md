# ADR 0003 · Spring Data JDBC (y SQL a mano) en vez de JPA

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 1

## Contexto

La aplicación es, sobre todo, una tubería de datos:

- la importación nocturna del GTFS estático mete unos **1,8 M de filas** de `stop_times` en tablas nuevas y las cambia de golpe (fase 2);
- la ingesta guarda **~0,26 M posiciones** y **~51.000 retrasos al día** en tablas particionadas (fase 3);
- las estadísticas son agregaciones SQL (medias, percentiles, porcentajes por línea y franja) y las consultas espaciales usan funciones de PostGIS (`ST_DWithin`, `ST_LineLocatePoint`…).

No hay un modelo de dominio rico con relaciones que navegar ni formularios que editen entidades.

## Decisión

**Spring Data JDBC** (`spring-boot-starter-data-jdbc`), que trae también `JdbcTemplate` y `JdbcClient`:

- `JdbcClient` / `JdbcTemplate` y SQL escrito a mano para las cargas masivas (inserción por lotes o `COPY`), las consultas espaciales y las agregaciones;
- repositorios de Spring Data JDBC solo donde haya un agregado simple que guardar y leer (por ejemplo, el estado de una importación);
- el esquema lo manda **Flyway**, nunca el ORM.

## Alternativas

1. **Spring Data JPA (Hibernate).** Se descarta porque:
   - el contexto de persistencia y el *dirty checking* estorban en cargas de millones de filas (hay que vaciar la sesión cada lote y aun así va más lento que `COPY` o `batchUpdate`);
   - las consultas espaciales y las agregaciones acabarían en SQL nativo de todos modos;
   - las tablas particionadas y el cambio de tablas de golpe se llevan mal con entidades mapeadas a un nombre de tabla fijo;
   - el *lazy loading* y los N+1 son una fuente de errores que aquí no aporta nada.
2. **jOOQ.** SQL con tipos, muy bueno para este caso, pero añade generación de código en el build y una dependencia más que defender. Se puede reconsiderar si las consultas de la fase 6 crecen mucho.
3. **JDBC puro sin Spring Data.** Casi lo mismo que la decisión; Spring Data JDBC añade repositorios sencillos sin coste cuando hagan falta.

## Consecuencias

- Más SQL escrito a mano, que se prueba contra un PostgreSQL de verdad con Testcontainers (nada de H2).
- Spring Batch usa JDBC por debajo (`JdbcBatchItemWriter`), así que encaja sin capas extra.
- En una entrevista hay que saber explicar cuándo sí elegiría JPA: un dominio con muchas entidades relacionadas y operaciones CRUD, como un back-office.
