# TrainWatch · reglas de trabajo

Proyecto Java de portfolio de Iván Chesa: mapa con los trenes de Cercanías y Rodalies en directo, con su retraso y estadísticas propias de puntualidad. El plan está en `docs/PLAN.md`; **Iván decide**, la sesión de estrategia planifica y revisa, y las sesiones de programación ejecutan una fase cada vez.

## Ciclo de cada fase

1. **Especificación** antes de programar: qué entra, qué no y cómo se acepta (la tabla del §6 del plan es el punto de partida).
2. **Rama** `fase-N-nombre` (por ejemplo, `fase-2-gtfs-estatico`), salvo que la tarea diga otra cosa.
3. **Pruebas** que cubran la aceptación.
4. **Informe** en `docs/informes/fase-N.md`: qué se hizo, cifras, qué quedó fuera y qué cambiaría del plan.
5. **Revisión independiente** con un subagente **sin contexto** de la conversación: se le da el código y la especificación, no las conclusiones. Lo que encuentre se corrige o se justifica en el informe.
6. **Fusión** en `main` con `git merge --no-ff` y **una línea** en `docs/informes/DIARIO.md` (fecha, fase, resultado, commit).
7. **Guía de defensa** de la fase en `docs/defensa/fase-N.md`: lo que Iván tiene que saber explicar en una entrevista (por qué así, qué alternativas había, qué preguntarán).

**Decisiones:** un ADR por decisión en `docs/adr/NNNN-titulo.md` (contexto, decisión, alternativas, consecuencias).

## Textos públicos

- En **español de España**. Sin inventar nada: cifras, logros y experiencia, solo los comprobados.
- Nunca «junior» ni «formación».
- Nada de nombre, logo ni colores de Renfe como identidad del proyecto. Aviso visible de que es un proyecto **no oficial**.
- Cita de la fuente en la web y en el README: **«Datos: Renfe (CC BY 4.0)»**. Los mapas citan «© OpenStreetMap» y al proveedor.

## Límites

- **Nada que cueste dinero sin preguntar** a Iván antes (servidores, dominios, servicios de pago, planes de prueba que piden tarjeta).
- No se hace push ni se crean repos en GitHub sin que Iván lo pida.
- Los datos crudos grabados (`spike/datos/`, `.pb`, el ZIP del GTFS) no van al repo.

## Git

- Commits **en español**, en imperativo o descriptivos y cortos («Añade la importación del GTFS estático»).
- Rama por fase y fusión `--no-ff`; `main` siempre compila y pasa las pruebas.

## Entorno

- JDK 25 (Eclipse Temurin 25 en `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y **Maven Wrapper** (`./mvnw`); no hay Maven instalado. El Java del `PATH` es el 8: exporta `JAVA_HOME` al JDK 25 antes de usar `./mvnw`.
- Docker Desktop está instalado (para PostGIS y Testcontainers), pero hay que arrancarlo.
