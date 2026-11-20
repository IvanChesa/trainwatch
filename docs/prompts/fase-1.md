Eres la sesión de programación de **TrainWatch**, la fase 1 («Cimientos»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`, con las reglas de trabajo, que son obligatorias;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0», §3, §4 y la fila de la fase 1 en §6;
- `docs/informes/fase-0.md`.

## Objetivo de la fase 1

Dejar el proyecto listo para construir encima. Todavía no hay lógica de negocio.

1. **Estructura de monorepo:**
   - `backend/`: Spring Boot;
   - `frontend/`: Angular;
   - `infra/`: Docker Compose ahora; Terraform en la fase 7;
   - `spike/`: se queda como está, es la prueba de la fase 0.

   Mueve lo que haga falta para que el Maven Wrapper de la raíz o el del backend tenga sentido, y explica la elección en un ADR.
2. **Backend:**
   - **Base:** Java 25 y la **última versión estable de Spring Boot que soporte Java 25**. Compruébalo en spring.io o en Maven Central y apunta la versión y el porqué en un ADR. Maven con Wrapper.
   - **Dependencias:** solo las que el plan necesita en esta fase:
     - Web;
     - Actuator;
     - Data JPA o JDBC (justifica cuál en el ADR);
     - Flyway;
     - el driver de PostgreSQL;
     - Testcontainers;
     - Spring Batch, que ya entra en la fase 2;
     - la validación.
   - **Lo mínimo que tiene que funcionar:**
     - `GET /api/estado` con versión y hora;
     - `/actuator/health` con la comprobación de la base de datos;
     - la primera migración de Flyway, que activa PostGIS (`CREATE EXTENSION postgis`) y crea una tabla de prueba.
   - **Configuración por perfiles:** `local` y `test`. Sin secretos en el repo: las variables de entorno van con un `.env.example`.
3. **Base de datos local:** `infra/docker-compose.yml` con PostgreSQL y PostGIS (imagen oficial `postgis/postgis`, con la versión fijada) y un volumen.
   - Docker Desktop está instalado pero puede estar parado. Arráncalo si hace falta (`Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`) y espera a que responda `docker info`.
4. **Pruebas:** una prueba de integración con **Testcontainers** que levante PostGIS, aplique Flyway y compruebe que la extensión está activa y que `/actuator/health` da UP.
5. **Frontend:**
   - un esqueleto de Angular, la última versión estable, con `npx @angular/cli new`, standalone y con signals;
   - una página «TrainWatch · en construcción», con el aviso de **proyecto no oficial** y «Datos: Renfe (CC BY 4.0)»;
   - Vitest si la plantilla lo trae (si no, el que traiga), y que `npm run build` y las pruebas pasen.

   Nada de mapa todavía: llega en la fase 5.
6. **CI:** `.github/workflows/ci.yml` con dos trabajos:
   - backend: JDK 25 Temurin, `./mvnw verify` con Testcontainers, que funciona en `ubuntu-latest`;
   - frontend: Node 24, `npm ci`, pruebas y build.

   No hagas push: el repo de GitHub lo crea Iván. Deja el workflow listo y comprobado en lo posible: sintaxis y comandos que pasan en local.
7. **README:** cómo arrancar en local (`docker compose up`, `./mvnw spring-boot:run`, `npm start`), el esquema de la arquitectura del plan, el aviso de no oficial y la cita de los datos.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-1-cimientos`;
  - especificación breve al principio del informe;
  - pruebas;
  - informe en `docs/informes/fase-1.md`, con versiones exactas, comandos y lo que quedó fuera;
  - **revisión independiente** con un subagente sin contexto, y corrección de lo que encuentre;
  - fusión `--no-ff` en `main`;
  - línea en `docs/informes/DIARIO.md`;
  - guía de defensa en `docs/defensa/fase-1.md`;
  - ADRs en `docs/adr/`.
- **Entorno:** el `java` del `PATH` es el 8. Usa `JAVA_HOME` del JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) en cada comando de Maven.
- **Límites:**
  - no toques nada fuera de esta carpeta;
  - nada de pago;
  - no instales software de sistema; las dependencias de Maven y npm, sí;
  - no hagas push.
- **Presupuesto limitado** (unos 12 $): ve al grano.
  - Si ves que no llegas, este es el orden de prioridad: backend con Flyway, PostGIS y la prueba de Testcontainers; docker-compose; CI; frontend; README.
  - Haz commits frecuentes, para que no se pierda nada si se corta.
- **Al terminar:** responde con un resumen de 10 líneas como máximo:
  - qué funciona;
  - versiones;
  - commit de fusión;
  - pruebas;
  - lo que queda para Iván.
