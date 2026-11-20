# ADR 0002 · Spring Boot 4.2.0 con Java 25

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 1

## Contexto

El plan (§4) pide Java 25 (LTS) y la versión estable más reciente de Spring Boot, con Spring Boot 3.5 como plan B si alguna librería no está lista.

Comprobado el 20-11-2026:

- **Maven Central** (`org/springframework/boot/spring-boot/maven-metadata.xml`): `<release>4.2.0</release>`, publicada el 19-11-2026. Las anteriores, 4.1.3 y 4.0.10.
- **start.spring.io:** la versión por defecto es `4.2.0.RELEASE` y genera proyectos con `java.version` 25.
- Spring Boot 4.x se apoya en Spring Framework 7, que soporta Java 17 a 25. La prueba de integración pasa con Temurin 25.0.5.

Versiones que fija el BOM de Spring Boot 4.2.0 y que usa la fase 1: Spring Batch 6.1.0, Flyway 12.11.0, driver de PostgreSQL 42.7.14, Testcontainers 2.0.5, Jackson 3.1.8.

## Decisión

**Spring Boot 4.2.0** sobre **Java 25** (Eclipse Temurin), con el `spring-boot-starter-parent` como padre del `pom.xml`. El proyecto se generó con start.spring.io para partir de la estructura y los starters oficiales.

## Alternativas

1. **Spring Boot 4.1.3**, la última de la rama anterior, con más parches. Se descarta: 4.1 deja de tener soporte gratuito antes, y para un proyecto que empieza no hay código que migrar. Si 4.2.0 diera un fallo propio de una `.0`, bajar a 4.1.3 es cambiar una línea del `pom.xml`.
2. **Spring Boot 3.5** (plan B del plan). No hace falta: todo lo que necesita la fase funciona en 4.2.
3. **Java 21.** Se descarta: el plan pide 25 (la LTS actual) y el equipo ya lo tiene instalado.

## Consecuencias

- Spring Boot 4 trae starters más pequeños: `spring-boot-starter-webmvc` (antes `-web`), `spring-boot-starter-flyway`, y starters de prueba por tecnología (`-webmvc-test`, `-flyway-test`…). Los tutoriales de Spring Boot 3 no se pueden copiar tal cual.
- Jackson 3 (paquetes `tools.jackson`) en lugar de Jackson 2.
- **Spring Batch 6 sin base de datos:** `spring-boot-starter-batch` arranca con un repositorio de trabajos en memoria y no crea tablas `BATCH_*`. Si la fase 2 quiere guardar el historial de importaciones en PostgreSQL, tendrá que añadir `spring-boot-starter-batch-jdbc`.
- Una `.0` recién publicada puede tener fallos que se corrijan en la `.1`; hay que actualizar a las versiones de parche cuando salgan.
