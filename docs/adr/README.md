# Decisiones de arquitectura (ADR)

Un fichero por decisión: `NNNN-titulo-corto.md`, con contexto, decisión, alternativas y consecuencias. Índice:

- [0001 · Monorepo con un Maven Wrapper por proyecto](0001-monorepo-con-un-maven-wrapper-por-proyecto.md)
- [0002 · Spring Boot 4.2.0 con Java 25](0002-spring-boot-4-2-con-java-25.md)
- [0003 · Spring Data JDBC (y SQL a mano) en vez de JPA](0003-spring-data-jdbc-en-vez-de-jpa.md)
- [0004 · PostGIS 18-3.7 en Docker y configuración por perfiles sin secretos](0004-postgis-en-docker-y-configuracion-por-perfiles.md)
- [0005 · `geometry(…, 4326)` para estaciones y recorridos, con índice sobre `geography`](0005-geometry-4326-con-indice-geography.md)
- [0006 · Cambio atómico del horario renombrando esquemas](0006-cambio-atomico-del-horario-por-esquemas.md)
- [0007 · Spring Batch con `COPY` por lotes, repositorio de trabajos en memoria y disparo manual con token](0007-spring-batch-con-copy-y-repositorio-en-memoria.md)
- [0008 · El estado del directo vive en memoria; la base de datos guarda el historial](0008-estado-del-directo-en-memoria.md)
- [0009 · Particiones diarias para `posiciones` y mensuales para `retrasos`, gestionadas por la aplicación](0009-particiones-diarias-y-mensuales.md)
- [0010 · La posición del tren se estima con el horario, el retraso y el recorrido](0010-posicion-estimada-sobre-el-recorrido.md)
