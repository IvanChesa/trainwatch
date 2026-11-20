# ADR 0004 · PostGIS 18-3.7 en Docker y configuración por perfiles sin secretos

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 1

## Contexto

La fase 1 necesita una base de datos local, la misma en las pruebas y en la CI, y una configuración que no deje claves en el repo.

## Decisión

1. **Imagen `postgis/postgis:18-3.7`** (PostgreSQL 18.7 y PostGIS 3.7.0, comprobado en el contenedor), fijada en dos sitios: `infra/docker-compose.yml` y `TestcontainersConfiguration.IMAGEN_POSTGIS`. Es la imagen que se usa en local, en las pruebas y en la CI.
2. **Volumen con nombre** `datos-postgis` montado en `/var/lib/postgresql`. Desde PostgreSQL 18, la imagen oficial guarda los datos en `/var/lib/postgresql/18/docker`; montar en `/var/lib/postgresql/data`, como en las versiones anteriores, deja los datos fuera del volumen.
3. **Puerto solo en `127.0.0.1`**, para no abrir la base de datos a la red local.
4. **Perfiles de Spring:**
   - **sin perfil por defecto:** sin perfil no hay base de datos configurada y la aplicación no arranca, en vez de arrancar como `local` por error en un servidor. `./mvnw spring-boot:run` activa `local` (configurado en el `spring-boot-maven-plugin`);
   - `local`: lee la conexión de variables de entorno `TRAINWATCH_DB_*`, con valores por defecto para todo menos la clave. Importa el fichero `.env` de la raíz si existe (`spring.config.import=optional:file:../.env[.properties]`), así Compose y Spring leen el mismo fichero;
   - `test`: la conexión la pone Testcontainers con `@ServiceConnection`, sin URL ni clave en ningún fichero.
5. **`.env.example` en el repo, `.env` fuera** (`.gitignore`). La clave no tiene valor por defecto: si falta, Compose se para con un mensaje y Spring no conecta.
6. **Flyway activa PostGIS** con `CREATE EXTENSION IF NOT EXISTS postgis` en la primera migración, y no la imagen. La imagen `postgis/postgis` trae un script de arranque (`/docker-entrypoint-initdb.d/10_postgis.sh`) que crea `postgis`, `postgis_topology`, `fuzzystrmatch` y `postgis_tiger_geocoder` en la base de datos inicial. Se sustituye por uno vacío en Compose (`infra/postgis/10_postgis.sh`) y en Testcontainers, para que el esquema salga solo de las migraciones, como en cualquier otro PostgreSQL (por ejemplo, uno gestionado en la nube en la fase 7), y para que la prueba falle si la migración deja de activar PostGIS.

## Alternativas

- **`postgis/postgis:17-3.5`** u otra versión anterior: más rodada, pero sin ninguna ventaja para un proyecto nuevo. PostgreSQL 18 trae mejoras de E/S asíncrona que vienen bien a las cargas de la fase 2.
- **Etiqueta `latest`** o sin parche: se descarta, porque una actualización silenciosa podría cambiar la versión de PostGIS entre la CI y local.
- **`spring-boot-docker-compose`** (Spring arranca Compose solo): cómodo, pero esconde un paso que en una entrevista conviene saber explicar, y añade una dependencia que no pide el plan.
- **H2 en las pruebas:** se descarta porque no tiene PostGIS ni el mismo SQL; para eso está Testcontainers.

## Consecuencias

- Si se cambia la versión de la imagen, hay que cambiarla en los dos sitios.
- Sin Docker no se pueden ejecutar las pruebas de integración (ni en local ni en la CI). `ubuntu-latest` trae Docker, así que en GitHub Actions funciona sin configurar nada.
- `/actuator/health` enseña los componentes (`db`, `diskSpace`…) solo a usuarios autorizados (`when-authorized`); los perfiles `local` y `test` los enseñan a todos. Nunca enseña los detalles. Solo se exponen `health` e `info` por HTTP.
