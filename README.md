# TrainWatch

Mapa con los trenes de Cercanías y Rodalies moviéndose en directo, con su retraso y estadísticas propias de puntualidad por línea, por hora y por día.

> **Proyecto personal, no oficial.** No tiene relación con Renfe ni con Adif.

> **Estado:** en desarrollo. Están hechas la prueba técnica de los datos (fase 0) y los cimientos del proyecto (fase 1): backend y frontend vacíos, base de datos con PostGIS, pruebas y CI. El plan está en [`docs/PLAN.md`](docs/PLAN.md) y los informes de cada fase, en [`docs/informes/`](docs/informes/).

## Arquitectura (prevista)

```
Renfe GTFS-RT (cada ~20 s) ──► Ingesta (Spring Boot, @Scheduled + Resilience4j)
                                   │  decodifica protobuf, cruza con el GTFS estático
                                   ├─► estado actual en memoria ──► SSE ──► navegador (Angular + MapLibre)
                                   └─► PostgreSQL + PostGIS
                                         · posiciones (48 h, particionadas por día)
                                         · retraso final de cada tren en cada parada (histórico)
                                         · agregados por línea, hora y día (para siempre)
Renfe GTFS estático (cada noche) ──► Importación (Spring Batch) ──► paradas, líneas, viajes, horarios y recorridos

API REST (OpenAPI) ──► fichas de tren y estación, estadísticas
```

Las decisiones de arquitectura están en [`docs/adr/`](docs/adr/).

## Estructura

| Carpeta | Qué hay |
|---|---|
| `backend/` | Java 25 y Spring Boot 4.2 (Web, Actuator, Spring Data JDBC, Flyway, Spring Batch, validación). Maven con wrapper |
| `frontend/` | Angular 22, standalone y con signals. Pruebas con Vitest |
| `infra/` | Docker Compose con PostgreSQL 18 y PostGIS 3.7. Terraform llegará en la fase 7 |
| `spike/` | Prueba técnica de la fase 0 (Java sin Spring) |
| `docs/` | Plan, ADR, informes de cada fase y guías |

## Arrancar en local

Hace falta **JDK 25**, **Node 24** y **Docker**. Maven no: lo trae el wrapper.

```bash
# 1. Variables de entorno: copia la plantilla y cambia la clave
cp .env.example .env

# 2. Base de datos (PostgreSQL + PostGIS) en localhost:5432
docker compose --env-file .env -f infra/docker-compose.yml up -d

# 3. Backend en http://localhost:8080 (spring-boot:run activa el perfil local, que lee el .env de la raíz)
cd backend
./mvnw spring-boot:run

# 4. Frontend en http://localhost:4200 (en otra terminal)
cd frontend
npm ci
npm start
```

Para comprobar que funciona:

- `http://localhost:8080/api/estado` → `{"aplicacion":"TrainWatch","version":"0.0.1-SNAPSHOT","hora":"…"}`
- `http://localhost:8080/actuator/health` → `"status":"UP"`, con `db` en `UP`.

En Windows, usa `mvnw.cmd` en vez de `./mvnw` si no trabajas con Git Bash, y asegúrate de que `JAVA_HOME` apunta al JDK 25.

## Pruebas

```bash
cd backend && ./mvnw verify                       # unitarias + integración con Testcontainers (necesita Docker)
cd frontend && npm test -- --watch=false          # Vitest
cd frontend && npm run build
```

La prueba de integración levanta `postgis/postgis:18-3.7` con Testcontainers, aplica las migraciones de Flyway y comprueba PostGIS, `/actuator/health` y `/api/estado`. La CI de GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) ejecuta lo mismo en cada push y pull request.

## La prueba técnica (`spike/`)

Programa Java sin Spring que lee los feeds GTFS Realtime con `gtfs-realtime-bindings` (MobilityData) y los cruza con el GTFS estático. Resultados en [`docs/informes/fase-0.md`](docs/informes/fase-0.md).

```bash
cd spike
./mvnw package
# Graba los tres feeds cada 20 s durante 30 min en spike/datos/
java -cp "target/classes:target/lib/*" com.ivanchesa.trainwatch.spike.Captura datos 30 20
# Analiza la captura contra el GTFS estático (fomento_transit.zip, descargado aparte)
java -Xmx3g -cp "target/classes:target/lib/*" com.ivanchesa.trainwatch.spike.Analisis datos fomento_transit.zip resultados.md
```

En Windows, el separador del classpath es `;` en vez de `:`.

## Datos

**Datos: Renfe (CC BY 4.0)**, de [data.renfe.com](https://data.renfe.com/): feeds GTFS Realtime de Cercanías (`gtfsrt.renfe.com`) y GTFS estático de Cercanías.

## Licencia

Código con licencia [MIT](LICENSE).
