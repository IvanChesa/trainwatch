# Diario de fases

Una línea por fase cerrada: fecha · fase · resultado · commit.

- 20-11-2026 · Fase 0 (prueba técnica) · Sigue con cambios: Rodalies y RL4 al 100 % en el horario; Bilbao y Cádiz sin directo; posiciones pegadas a estaciones · commit directo en `main` (fase sin rama, por indicación de la tarea)
- 20-11-2026 · Fase 1 (cimientos) · Hecha: Spring Boot 4.2.0 + Java 25, PostGIS 18-3.7 con Flyway y Testcontainers (8 pruebas), Angular 22 con Vitest (3), CI escrita y validada en local, pendiente del primer push · fusión `fb7e7c0`
- 20-11-2026 · Fase 2 (GTFS estático) · Hecha: importación del ZIP real en 15-17 s con las filas de la fase 0 (1.793.333 horas de paso), cambio atómico de esquema en 4 ms, 93,5 % de los viajes de tren encajan en su recorrido, 29 pruebas (1 manual) · fusión `787b4da`
- 20-11-2026 · Fase 3 (ingesta en directo) · Hecha: feeds cada 7 s con 304 y Resilience4j, estado en memoria de ~400 trenes, retraso final por parada, particiones diarias y mensuales, posición estimada con rumbo, 63 pruebas (1 manual); prueba real de 10 min sin errores (3.830 posiciones, 423 retrasos; Bilbao y Cádiz ya emiten) · fusión `465daf9`
