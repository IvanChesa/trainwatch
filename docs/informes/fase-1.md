# Fase 1 · Cimientos: informe

**Fecha:** 20-11-2026. **Rama:** `fase-1-cimientos`. **Resultado:** hecha. El proyecto vacío compila, arranca contra PostGIS en local y pasa las pruebas (8 en el backend y 3 en el frontend). La CI está escrita y validada, pero no se ha ejecutado en GitHub porque el repo aún no existe.

## 1. Especificación

**Entra:**

- **Monorepo** con `backend/`, `frontend/` e `infra/`; `spike/` sin tocar.
- **Backend** con Java 25 y la última Spring Boot estable, Maven Wrapper y solo estas dependencias: Web, Actuator, Data JDBC, Flyway, el driver de PostgreSQL, Testcontainers, Spring Batch y la validación.
  - `GET /api/estado` con versión y hora.
  - `/actuator/health` con la comprobación de la base de datos.
  - Migración V1, que activa PostGIS y crea una tabla de prueba.
  - Perfiles `local` y `test`, sin secretos en el repo y con un `.env.example`.
- **`infra/docker-compose.yml`** con `postgis/postgis` (versión fijada) y un volumen.
- **Frontend** con Angular (la última estable): página «en construcción», aviso de no oficial y cita de los datos. Las pruebas y el build tienen que pasar.
- **CI** con dos trabajos (backend y frontend), README y ADR.

**No entra:** lógica de negocio, tablas del GTFS, OpenAPI, SSE, Resilience4j, imagen Docker del backend, despliegue, mapa y Playwright. Llegan en las fases 2 a 7.

**Aceptación:**

1. `./mvnw verify` pasa con una prueba de integración. Esa prueba levanta PostGIS con Testcontainers, aplica Flyway y comprueba que la extensión está activa y que `/actuator/health` da UP.
2. `docker compose up` y `./mvnw spring-boot:run` responden en local.
3. `npm test` y `npm run build` pasan.
4. El workflow es válido y sus comandos pasan en local.

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| Estructura | `backend/` con su propio `mvnw`; no hay `pom.xml` en la raíz ([ADR 0001](../adr/0001-monorepo-con-un-maven-wrapper-por-proyecto.md)) |
| Backend | Generado con start.spring.io. `EstadoController` (versión de `build-info`, hora con un `Clock` en Europe/Madrid), `RelojConfig` y `V1__postgis_y_tabla_de_prueba.sql`. La tabla `prueba_cimientos` lleva un `geometry(Point, 4326)` y la fila de Lleida Pirineus |
| Configuración | `application.properties` común, `application-local.properties` (variables `TRAINWATCH_DB_*` e importación del `.env` de la raíz) y `application-test.properties`. Sin perfil por defecto; `spring-boot:run` activa `local` |
| Base de datos | `infra/docker-compose.yml` con `postgis/postgis:18-3.7`, el volumen `datos-postgis` en `/var/lib/postgresql`, el puerto solo en `127.0.0.1` y un healthcheck. El script de arranque de la imagen se sustituye por uno vacío (`infra/postgis/10_postgis.sh`) |
| Pruebas del backend | `CimientosIntegracionTests` (6 pruebas, con PostGIS en Testcontainers): migración aplicada, PostGIS activo, PostGIS creado por Flyway y no por la imagen, geometría en WGS 84 (distancia Lleida-Sants entre 120 y 140 km), health UP con `db` UP y `/api/estado`. `EstadoControllerTests` (2 pruebas unitarias con reloj fijo) |
| Frontend | `npx @angular/cli@22.3.0 new frontend` (standalone por defecto, sin SSR ni router). Componente `App` con signals y `OnPush`, `lang="es"`. 3 pruebas con Vitest |
| CI | `.github/workflows/ci.yml`. Backend con Temurin 25 y `./mvnw -B -ntp verify`; frontend con Node 24, `npm ci`, `npm test -- --watch=false` y `npm run build`. Se lanza en push a `main` y `fase-*`, en pull requests y a mano |
| Documentación | README (arranque, esquema, aviso y cita), 4 ADR y la guía de defensa |

## 3. Versiones exactas

| Qué | Versión | Cómo se comprobó |
|---|---|---|
| Spring Boot | **4.2.0** (19-11-2026) | Maven Central (`<release>4.2.0</release>`) y start.spring.io (por defecto, `4.2.0.RELEASE`) |
| Java | Temurin **25.0.5+8** | `JAVA_HOME` local |
| Maven (backend) | 3.10.1, Wrapper 3.3.4 | El wrapper que genera start.spring.io |
| Spring Batch · Flyway · driver PG · Testcontainers · Hibernate Validator | 6.1.0 · 12.11.0 · 42.7.14 · 2.0.5 · 9.1.6.Final | `spring-boot-dependencies-4.2.0.pom` |
| PostgreSQL · PostGIS | **18.7 · 3.7.0** (`postgis/postgis:18-3.7`) | `SELECT version()` y `extversion` en el contenedor |
| Angular · CLI | **22.3.0** | `registry.npmjs.org/@angular/cli/latest` |
| TypeScript · Vitest · jsdom | ~6.0.2 · 5.1.1 · 31 | `package.json` y la salida de las pruebas |
| Node · npm (local) | 24.22.1 · 11.19.1 | `node -v`; en el contenedor `node:24`, 24.22.3 |
| Acciones de GitHub | checkout v7, setup-java v6, setup-node v7, upload-artifact v7 | API de GitHub, `releases/latest` |
| Docker Desktop (motor) | 29.6.1 | `docker info` |

## 4. Cifras y comandos

| Comando | Resultado |
|---|---|
| `cd backend && ./mvnw -B -ntp verify` | BUILD SUCCESS, **8 pruebas**, 0 fallos. La primera vez, unos 40 s de arranque (descarga de la imagen, 239 MB); después, ~8 s la clase de integración |
| Mutación: quitar el `CREATE EXTENSION` de V1 | La prueba **falla** (`type "geometry" does not exist`). Después se restauró el fichero |
| `docker compose --env-file .env -f infra/docker-compose.yml up -d --wait` | Contenedor `Healthy`. En la base de datos, solo `plpgsql` y `postgis` |
| `./mvnw spring-boot:run` | Perfil `local`. `/api/estado` → `{"aplicacion":"TrainWatch","version":"0.0.1-SNAPSHOT","hora":"2026-11-20T10:41:38…+01:00"}`; `/actuator/health` → `UP` con `db` en `UP` |
| `java -jar` sin perfil | No arranca: «Failed to configure a DataSource» (a propósito) |
| `npx ng test --watch=false` · `npm run build` | **3 de 3** · bundle inicial de 97,3 kB (29,6 kB transferidos) |
| CI del frontend en `node:24` (Linux, Docker) | `npm ci`, pruebas y build pasan |
| `actionlint` (imagen `rhysd/actionlint`) | 0 errores |

## 5. Revisión independiente

Un subagente sin contexto revisó el código contra la especificación. Ejecutó `./mvnw verify`, las pruebas y el build del frontend, y comprobó en git los modos y los fines de línea. **Nada grave.** Lo que encontró y qué se hizo:

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 1 (importante) | La prueba de PostGIS pasaba aunque la migración no activara la extensión: el script `10_postgis.sh` de la imagen ya la crea | **Corregido.** El script se sustituye por uno vacío en Testcontainers y en Compose. Hay una prueba nueva (solo `postgis` entre las extensiones) y la mutación lo confirma |
| 2 | La base de datos local tenía, además, `postgis_topology`, `fuzzystrmatch` y `tiger_geocoder` | **Corregido** con lo mismo |
| 3 | `spring.profiles.default=local`: un servidor sin perfil arrancaría como local | **Corregido.** Ya no hay perfil por defecto; `spring-boot:run` activa `local` en el `pom.xml`. Comprobado que sin perfil no arranca |
| 4 | `.env` se lee como `.properties`: las comillas y `export` darían valores malos | **Avisado** en `.env.example` |
| 5 | `show-components=always` enseña los componentes a cualquiera | **Corregido.** `when-authorized` en la configuración común y `always` en `local` y `test` |
| 6 | La CI no se lanzaba en push a ramas `fase-*` | **Corregido** |
| 7 | Los logs del Agent SDK sin seguimiento podían colarse en un commit | **Corregido** con `*.log` en `.gitignore` |
| 8 | Una línea en blanco con espacios en `app.config.ts` | **Corregido** |
| 9 | Mockito se carga como agente y el JDK 25 avisa | **Se deja.** No se usa Mockito en esta fase y no falla. Si molesta, se declara como `-javaagent` en Surefire |

## 6. Qué quedó fuera o pendiente

- **La CI no se ha ejecutado en GitHub.** Para eso hace falta el repo, y lo crea Iván. El trabajo del backend no se ha probado en Linux (Testcontainers dentro de Docker en Windows no es comparable), pero `ubuntu-latest` trae Docker y es el caso típico de Testcontainers. Al hacer el primer push, conviene mirar que los dos trabajos salgan en verde.
- **Spring Batch sin tablas:** en Spring Boot 4, `spring-boot-starter-batch` usa un repositorio de trabajos en memoria. Para el historial de importaciones en PostgreSQL, la fase 2 tendrá que añadir `spring-boot-starter-batch-jdbc` ([ADR 0002](../adr/0002-spring-boot-4-2-con-java-25.md)).
- **npm 11** avisa en `npm ci` de que hay *install scripts* sin aprobar. No impide nada (las pruebas y el build pasan), pero conviene revisarlo con `npm install-scripts ls` cuando se añadan dependencias.
- **Spring Boot 4.2.0 salió ayer.** Si aparece un fallo de la `.0`, el plan B es la 4.1.3, cambiando una línea.
- **Dos ficheros en `docs/informes/agent-sdk-*.log`**, que no son de esta fase: no se han tocado y ahora quedan ignorados.

## 7. Qué cambiaría del plan

- **§4, Herramientas:** concretar «Spring Boot 4.2 (Spring Framework 7, Jackson 3)», «Spring Data JDBC (no JPA)» y «PostgreSQL 18 con PostGIS 3.7».
- **§6, fase 2:** añadir `spring-boot-starter-batch-jdbc` si se quiere el historial de trabajos en la base de datos. Además, la primera migración de la fase 2 debería borrar `prueba_cimientos`.
- **§6, fase 7:** la imagen del backend tendrá que activar su perfil (`SPRING_PROFILES_ACTIVE=prod`), porque ya no hay perfil por defecto.
