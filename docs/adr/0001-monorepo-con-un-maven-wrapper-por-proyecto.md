# ADR 0001 · Monorepo con un Maven Wrapper por proyecto

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 1

> **Nota (20-11, fase 4r):** el frontend es ahora React con Vite ([ADR 0011](0011-react-en-lugar-de-angular.md)). La decisión del monorepo no cambia.

## Contexto

El repositorio tiene que alojar el backend (Spring Boot), el frontend (Angular), la infraestructura (Docker Compose y, en la fase 7, Terraform) y la prueba técnica de la fase 0 (`spike/`), que ya tiene su propio `pom.xml` y su Maven Wrapper (Maven 3.9.16). No hay Maven instalado en el equipo: todo va por el wrapper.

## Decisión

- Un solo repositorio con cuatro carpetas: `backend/`, `frontend/`, `infra/` y `spike/`.
- **Cada proyecto Maven lleva su propio wrapper:** `backend/mvnw` (Maven 3.10.1, el que trae Spring Initializr) y `spike/mvnw` (se queda como estaba). En la raíz no hay `pom.xml` ni wrapper.
- La CI ejecuta cada trabajo en su carpeta (`working-directory: backend` o `frontend`).

## Alternativas

1. **`pom.xml` agregador en la raíz** con `backend` y `spike` como módulos, y un único wrapper. Se descarta: `spike` es un experimento cerrado que no debe compilarse ni romper la CI, y el frontend no es Maven, así que el agregador solo uniría un módulo útil. Añade herencia y ciclo de vida compartido sin ganar nada.
2. **Repositorios separados** para backend y frontend. Se descarta: para una persona sola y un portfolio, un repo con todo junto se revisa, se versiona y se enseña mejor (un solo enlace, una sola CI, cambios de API y de front en el mismo commit).
3. **Mover el wrapper de `spike/` a la raíz.** Se descarta: rompería los comandos documentados de la fase 0 sin ningún beneficio.

## Consecuencias

- Los comandos del backend se lanzan desde `backend/` (`cd backend && ./mvnw verify`). El README lo dice.
- Dos versiones de Maven en el repo (3.9.16 en `spike/`, 3.10.1 en `backend/`). Es aceptable porque `spike/` no evoluciona.
- Si más adelante aparece un segundo servicio Java, se puede añadir entonces un `pom.xml` agregador en la raíz sin mover nada.
