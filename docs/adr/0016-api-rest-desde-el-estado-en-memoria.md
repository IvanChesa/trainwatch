# ADR 0016 · La API lee el directo de memoria y el resto de PostGIS, con records propios, ProblemDetail y OpenAPI

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 4 · **Se apoya en:** ADR 0003, 0005 y 0008

## Contexto

La fase 4 expone lo que recoge la ingesta: núcleos, trenes de un núcleo, ficha del tren, estaciones (ficha y cercanas), líneas y avisos. Es una API **pública y de solo lectura**, que consumirá el mapa de React (fase 5) desde `trainwatch.ivanchesa.com` y, en local, desde Vite (`localhost:5173`).

## Decisión

1. **Dos fuentes, según lo que se pide:**
   - **lo que está en directo** (trenes, posición, retraso, próxima parada, estado del núcleo) sale del **estado en memoria** (`EstadoDirecto`, ADR 0008): sin consultas, siempre igual que lo que manda el directo por SSE. `GET /api/nucleos/{id}/trenes` devuelve literalmente la última foto del difusor SSE (ADR 0015);
   - **lo demás** (estaciones, líneas con su recorrido, horario de una estación, avisos, evolución del retraso del viaje) sale de **PostgreSQL/PostGIS con SQL a mano** (`JdbcClient`, ADR 0003): `ST_DWithin` + `<->` sobre el índice `geography` para las cercanas (ADR 0005), `ST_AsGeoJSON` para el recorrido.
2. **Records propios para las respuestas** (`Respuestas`): nada de filas ni de `Tren` directamente. El contrato no cambia si cambia el esquema o el estado interno, y OpenAPI lo describe con sus `@Schema` en español.
3. **Horas en hora de Madrid con su desfase** (`2026-11-20T10:24:00+01:00`) y retrasos en **minutos enteros** (Renfe no da más precisión). Lo que no se sabe va a `null`; no se inventa (un tren sin horario no tiene destino ni próxima parada).
4. **Errores como `ProblemDetail` (RFC 9457)**, `application/problem+json`, con título en español: los propios (404, 400 con el parámetro, 503 del directo) son `ErrorResponseException`; los de Spring (parámetro que falta o de otro tipo, ruta inexistente) los traduce `ErroresApi`. El tipo de contenido se fija en la respuesta para que llegue también a un cliente que solo acepta `text/event-stream`.
5. **OpenAPI con springdoc 3.2.0** en `/api/docs` (Swagger UI en `/api/docs/ui`). Los ejemplos de respuesta son los ficheros de `docs/api/` (Maven los copia al classpath): un solo ejemplo por endpoint, que es a la vez el contrato para las pruebas del frontend y lo que enseña Swagger. Una prueba comprueba que cada ejemplo encaja con su record.
6. **Caché HTTP según lo que cambia:** líneas, 1 min y `ETag` (el recorrido y las paradas solo cambian cada noche y se guardan en memoria por versión del horario; los avisos, sí, de ahí que no sea más); estaciones cercanas, 1 h y `ETag`; ficha de estación, 15 s; avisos, 30 s; núcleos, 10 s; trenes, 5 s. Todo `public`: no hay datos personales.
7. **CORS** solo para `https://trainwatch.ivanchesa.com` y `http://localhost:5173`, solo `GET`/`HEAD`/`OPTIONS`, sin credenciales; admite `Last-Event-ID` para el directo. Configurable (`trainwatch.api.origenes-permitidos`).
8. **Sin autenticación:** no hay nada privado ni nada que escribir. El único endpoint que escribe (la importación manual, fase 2) sigue con su token.

## Alternativas

- **Leer el directo de la tabla `posiciones`:** más lento, con hasta un ciclo de retraso, y la ficha y el SSE podrían no coincidir.
- **Spring Data REST o exponer entidades:** menos código, pero el contrato quedaría pegado al esquema y no habría sitio para lo calculado (próxima parada, hora estimada, situación de cada parada).
- **GraphQL:** el frontend tiene pocas pantallas y bien conocidas; REST con OpenAPI es más sencillo de cachear (CDN, `ETag`) y de explicar.
- **Errores con un JSON propio:** RFC 9457 es el estándar y Spring lo trae hecho.

## Consecuencias

- La ficha de un tren solo existe mientras está en directo (404 después). Un histórico por tren sería otra cosa (fase 6 u 8).
- La «hora estimada» de las paradas que faltan es la programada **más el retraso actual**: no es una predicción (Renfe solo da el retraso para la próxima parada). Lo dice la documentación.
- La extensión de cada núcleo (centro y zoom) recorre las horas de paso: ~1 s la primera vez tras cada horario nuevo, luego en memoria.
- Los núcleos que solo están en el feed (sin horario) salen en `/api/nucleos` sin centro ni zoom.
