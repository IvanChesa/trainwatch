# Fase 1 · Cimientos: guía de defensa

Lo que tienes que saber explicar de esta fase en una entrevista. No hay lógica de negocio: lo que se evalúa aquí es el criterio para montar un proyecto Java moderno.

## 1. La foto en 30 segundos

> «Es un monorepo con un backend en Spring Boot 4.2 y Java 25, un frontend en Angular 22 y la infraestructura en Docker Compose. La base de datos es PostgreSQL 18 con PostGIS; el esquema lo lleva Flyway. Las pruebas de integración levantan un PostGIS de verdad con Testcontainers, y GitHub Actions ejecuta las pruebas del backend y del frontend en cada push.»

## 2. Decisiones y por qué

| Decisión | Por qué | Alternativa que descarté |
|---|---|---|
| **Monorepo**, un wrapper por proyecto ([ADR 0001](../adr/0001-monorepo-con-un-maven-wrapper-por-proyecto.md)) | Un enlace, una CI, API y front en el mismo commit. `spike/` es un experimento cerrado y no debe romper la CI | `pom.xml` agregador en la raíz; repos separados |
| **Spring Boot 4.2.0 + Java 25** ([ADR 0002](../adr/0002-spring-boot-4-2-con-java-25.md)) | La última estable (Maven Central, 19-11-2026) y la LTS de Java. Proyecto nuevo: no hay nada que migrar | 4.1.3 (más parches) o 3.5 |
| **Spring Data JDBC, no JPA** ([ADR 0003](../adr/0003-spring-data-jdbc-en-vez-de-jpa.md)) | Es una tubería de datos: cargas de millones de filas, agregaciones y funciones PostGIS. El ORM estorba | JPA/Hibernate, jOOQ |
| **Flyway** | El esquema versionado en SQL, revisable en el repo y aplicado igual en local, pruebas y producción | `ddl-auto` de Hibernate (no apto para producción), Liquibase (XML/YAML, más pesado) |
| **Testcontainers, no H2** | H2 no tiene PostGIS ni el mismo SQL: una prueba que pasa en H2 puede fallar en PostgreSQL | H2 o una base de datos compartida |
| **PostGIS 18-3.7 fijado** ([ADR 0004](../adr/0004-postgis-en-docker-y-configuracion-por-perfiles.md)) | La misma imagen en local, pruebas y CI; sin sorpresas por `latest` | `latest`, versiones anteriores |
| **Perfiles `local` y `test`, secretos fuera** | La clave sale de variables de entorno o de un `.env` ignorado por git; en las pruebas la pone Testcontainers | Claves en `application.properties` |

## 3. Cómo funciona por dentro (lo que te pueden pedir que enseñes)

- **`@ServiceConnection`** (en `TestcontainersConfiguration`): Spring Boot lee del contenedor la URL, el usuario y la clave y configura el `DataSource`. Antes se hacía con `@DynamicPropertySource`.
- **`asCompatibleSubstituteFor("postgres")`**: le dice a Testcontainers que la imagen `postgis/postgis` se comporta como `postgres`, para poder usar `PostgreSQLContainer` con ella.
- **Orden de arranque:** Spring crea el `DataSource`, Flyway aplica `V1__…sql` antes de que nada use la base de datos y Actuator añade el indicador `db`, que hace una consulta de validación. Si la base de datos cae, `/actuator/health` pasa a `DOWN` (HTTP 503).
- **`/api/estado`** saca la versión de `META-INF/build-info.properties`, que genera el objetivo `build-info` del `spring-boot-maven-plugin`. La hora sale de un `Clock` inyectado, en `Europe/Madrid`, para poder probarla con un reloj fijo (`EstadoControllerTests`).
- **Sin perfil por defecto:** el JAR sin perfil no tiene base de datos y no arranca («Failed to configure a DataSource»). Es a propósito: mejor fallar que arrancar en un servidor con la configuración de local. `./mvnw spring-boot:run` activa `local` (en el `pom.xml`); las pruebas usan `@ActiveProfiles("test")`; producción (fase 7) tendrá su perfil, activado con `SPRING_PROFILES_ACTIVE`.
- **El script de arranque de la imagen, desactivado:** `postgis/postgis` crea PostGIS y tres extensiones más al iniciar la base de datos. Se sustituye por un script vacío (en Compose y en Testcontainers) para que PostGIS lo active Flyway. Comprobado: si se quita el `CREATE EXTENSION` de la migración, la prueba falla con `type "geometry" does not exist`.
- **PostgreSQL 18 y el volumen:** desde la 18, los datos van en `/var/lib/postgresql/18/docker`. Por eso el volumen se monta en `/var/lib/postgresql` y no en `.../data`. Es un fallo típico al actualizar.
- **Batch sin tablas:** en Spring Boot 4, `spring-boot-starter-batch` usa un repositorio de trabajos en memoria. Para guardar el historial en PostgreSQL hace falta `spring-boot-starter-batch-jdbc` (decisión de la fase 2).

## 4. Preguntas probables

- **«¿Por qué no JPA, si es lo estándar?»** Porque aquí el trabajo es cargar y agregar volúmenes grandes y usar funciones espaciales: con JPA acabaría escribiendo SQL nativo y peleándome con el contexto de persistencia. JPA lo elegiría para un CRUD con muchas entidades relacionadas, como un back-office.
- **«¿Qué diferencia hay entre una prueba unitaria y una de integración aquí?»** `EstadoControllerTests` crea el controlador a mano con un reloj fijo, sin Spring: milisegundos. `CimientosIntegracionTests` arranca la aplicación entera en un puerto aleatorio, con un PostGIS real: unos 40 s la primera vez (descarga de la imagen) y pocos segundos después.
- **«¿Qué pasa si alguien sube una migración que ya estaba aplicada, modificada?»** Flyway guarda el checksum de cada migración en `flyway_schema_history` y se niega a arrancar si no coincide. Las migraciones aplicadas no se tocan: se añade una nueva.
- **«¿Por qué la migración hace `CREATE EXTENSION IF NOT EXISTS` si la imagen ya trae PostGIS activado?»** Porque en producción la base de datos puede no ser esta imagen (un PostgreSQL gestionado), y el esquema tiene que ser reproducible solo con las migraciones.
- **«¿Cómo evitas meter secretos en el repo?»** `.env` está en `.gitignore`; el repo solo tiene `.env.example` con un valor de ejemplo. La clave no tiene valor por defecto en Spring ni en Compose, así que si falta se nota enseguida. En producción irán en variables de entorno del servidor o en un gestor de secretos.
- **«¿Por qué Actuator solo expone `health` e `info`?»** Porque endpoints como `env`, `beans` o `heapdump` filtran información interna. Por defecto `health` solo da el estado global a quien no esté autorizado (`show-components=when-authorized`); en local y en pruebas enseña también cada componente. Nunca los detalles (versión de la base de datos, rutas de disco).
- **«¿Cómo corre Testcontainers en GitHub Actions?»** Los runners `ubuntu-latest` traen Docker; Testcontainers lo detecta por el socket y no hay que configurar nada.
- **«¿Por qué SSE y no WebSocket?»** No es de esta fase, pero te lo preguntarán al ver la arquitectura: el navegador solo recibe, SSE es HTTP normal, se reconecta solo y pasa sin problemas por Cloudflare.

## 5. Lo que no está y por qué

- **Sin lógica de negocio** ni tablas del GTFS: llegan en la fase 2. La tabla `prueba_cimientos` solo demuestra que PostGIS funciona y se borrará con una migración nueva.
- **Sin imagen Docker del backend ni despliegue:** fase 7.
- **Sin OpenAPI, SSE ni Resilience4j:** fases 3 y 4. Solo están las dependencias que pide esta fase.
