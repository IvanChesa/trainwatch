# TrainWatch

Mapa con los trenes de Cercanías y Rodalies moviéndose en directo, con su retraso y estadísticas propias de puntualidad por línea, por hora y por día.

> **Proyecto personal, no oficial.** No tiene relación con Renfe ni con Adif.

> **Estado:** en desarrollo. Están hechas la prueba técnica de los datos (fase 0), los cimientos del proyecto (fase 1: backend, frontend, PostGIS, pruebas y CI), la importación nocturna del horario de Renfe (fase 2) y la ingesta en directo (fase 3). El frontend pasó de Angular a React (fase 4r). El plan está en [`docs/PLAN.md`](docs/PLAN.md) y los informes de cada fase, en [`docs/informes/`](docs/informes/).

## Arquitectura (prevista)

```
Renfe GTFS-RT (cada ~20 s) ──► Ingesta (Spring Boot, @Scheduled + Resilience4j)
                                   │  decodifica protobuf, cruza con el GTFS estático
                                   ├─► estado actual en memoria ──► SSE ──► navegador (React + MapLibre)
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
| `frontend/` | React 19 con Vite 8 y TypeScript estricto. Pruebas con Vitest y Testing Library; lint con oxlint |
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

# 4. Frontend en http://localhost:5173 (en otra terminal); /api pasa por el proxy de Vite al backend
cd frontend
npm ci
npm run dev
```

Para comprobar que funciona:

- `http://localhost:8080/api/estado` → `{"aplicacion":"TrainWatch","version":"0.0.1-SNAPSHOT","hora":"…"}`
- `http://localhost:8080/actuator/health` → `"status":"UP"`, con `db` en `UP`.
- `http://localhost:5173` → la página «en construcción» con la versión y la hora del backend, o «Backend no disponible» si no está arrancado.

Al arrancar con la base de datos vacía, el backend descarga e importa el horario de Renfe en segundo plano (unos 15 s). Después lo hace cada noche a las 04:30 y a las 06:30, hora de Madrid, solo si el fichero ha cambiado. El estado del horario:

- `http://localhost:8080/api/horario/estado` → fecha de publicación, días que cubre, filas por tabla y último intento.
- Importación a mano, si `TRAINWATCH_ADMIN_TOKEN` tiene valor en el `.env`: `curl -X POST -H "X-Trainwatch-Token: <token>" "http://localhost:8080/api/horario/importaciones?forzar=true"`.

La ingesta en directo empieza 10 s después de arrancar: cada 7 s pide a Renfe los tres feeds GTFS Realtime (posiciones, retrasos y avisos) con peticiones condicionales, guarda el estado de cada tren en memoria y escribe en `posiciones` (48 h), `retrasos` y `avisos`. Para verla:

- `http://localhost:8080/api/directo/estado` → hora del último dato, trenes por estado y cobertura por núcleo.
- `http://localhost:8080/actuator/metrics/trainwatch.directo.descargas` (y `.trenes`, `.filas`, `.errores`, `.antiguedad`), solo en el perfil `local`.
- Para desactivarla: `TRAINWATCH_DIRECTO_ACTIVADA=false`.

En Windows, usa `mvnw.cmd` en vez de `./mvnw` si no trabajas con Git Bash, y asegúrate de que `JAVA_HOME` apunta al JDK 25.

## Pruebas

```bash
cd backend && ./mvnw verify                       # unitarias + integración con Testcontainers (necesita Docker)
cd frontend && npm run lint                       # oxlint
cd frontend && npm test                           # Vitest y Testing Library
cd frontend && npm run build                      # tsc y Vite
```

Prueba manual con el ZIP real de Renfe (no corre en CI; escribe las cifras en `backend/target/importacion-real.txt`):

```bash
curl -o datos/gtfs/fomento_transit.zip https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip
cd backend && ./mvnw test -Dtest=ImportacionRealManualTests -Dhorario.zip=../datos/gtfs/fomento_transit.zip
```

La prueba de integración levanta `postgis/postgis:18-3.7` con Testcontainers, aplica las migraciones de Flyway y comprueba PostGIS, `/actuator/health` y `/api/estado`. Las de la importación del horario usan un GTFS pequeño de ejemplo (`backend/src/test/resources/gtfs/ejemplo/`) servido por un servidor HTTP local. La CI de GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) ejecuta lo mismo en cada push y pull request.

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
