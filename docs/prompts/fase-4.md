Eres la sesión de programación de **TrainWatch**, la fase 4 («API y directo»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0», §2, §3 y la fase 4 en §6;
- los informes `docs/informes/fase-0.md` a `fase-3.md`, y `fase-3b.md` (correcciones de la ingesta).

Construye sobre lo fusionado en `main`. Si las fases 3 y 3b no están fusionadas o `./mvnw verify` falla, **para** y explica por qué en `docs/informes/fase-4.md`.

## Objetivo

Exponer lo que ya recoge la ingesta con una **API REST documentada** y un **canal en directo (SSE)** que el mapa de la fase 5 consumirá.

## Qué construir

1. **API REST** (Spring Web) con **OpenAPI** (springdoc), documentada en `/api/docs` y con ejemplos en español:
   - `GET /api/nucleos`: núcleos con nombre, centro y zoom del mapa, estado («con directo» o «sin datos en directo») y cobertura;
   - `GET /api/nucleos/{id}/trenes`: trenes actuales del núcleo, con id, línea, estado (`EN_ORIGEN`, `EN_MARCHA`…), posición estimada, rumbo, retraso, próxima parada y hora de salida si está en origen;
   - `GET /api/trenes/{id}`: la ficha, con línea, origen y destino, paradas con hora programada y estimada, retraso actual y su evolución en el viaje;
   - `GET /api/estaciones/{id}`: próximos trenes con su retraso, líneas y avisos de la estación;
   - `GET /api/estaciones?cerca=lat,lon&radio=…`: las estaciones más cercanas, con PostGIS;
   - `GET /api/lineas/{nucleo}/{linea}`: la línea con su recorrido (GeoJSON), paradas y avisos;
   - `GET /api/avisos?nucleo=&linea=&estacion=`.

   Los errores, con `ProblemDetail` (RFC 9457).
2. **Directo con SSE:** `GET /api/directo?nucleo=51`.
   - Al conectarse, una **instantánea** de los trenes del núcleo; después, **solo los cambios** en cada actualización de la ingesta (altas, cambios y bajas).
   - Un latido cada ~15 s.
   - Soporta `Last-Event-ID`, o explica en el ADR por qué basta con reenviar la instantánea.
   - El ADR de SSE frente a WebSocket ya lo pide el plan (§3).
3. **CORS** solo para `https://trainwatch.ivanchesa.com` y `http://localhost:5173`, el frontend de React con Vite. Cabeceras de caché razonables en lo que no cambia, como las líneas y las estaciones.
4. **Límites:**
   - un máximo de conexiones SSE configurable, con un rechazo claro si se pasa;
   - tiempos de espera.

   Sin autenticación: es una API pública de solo lectura.
5. **Pruebas:**
   - de cada endpoint (contrato, errores y casos sin datos), con la ingesta alimentada con los `.pb` de prueba de la fase 3;
   - de SSE: instantánea y después cambios;
   - **de carga en local:** **200 clientes SSE** a la vez durante 1-2 min, con memoria, CPU y latencia de los mensajes apuntadas en el informe. Puede ser un test JUnit o un programa pequeño; nada de herramientas nuevas de pago.
6. **Contrato para el frontend:** un JSON de ejemplo por endpoint en `docs/api/`, que la fase 5 usará para sus pruebas con datos simulados.
7. **Hora programada en `retrasos`.** Es un cambio pequeño en la ingesta, que pide la fase 6 (estadísticas).
   - **El problema:**
     - las estadísticas por franja horaria («a las 8:00 llega con 4 min») necesitan la hora **programada** de cada parada;
     - `retrasos` no la guarda, y el horario de ese día se sustituye por la noche;
     - `registrado` no sirve: es cuándo se cerró el retraso, no cuándo tocaba pasar.
   - **El arreglo:**
     - una migración de Flyway con la hora programada de llegada a la parada (por ejemplo, `llegada_programada TIMESTAMPTZ`), calculada con el horario del viaje y su `fecha_servicio`, como ya hace `Viaje` (cuidado con las horas de más de 24:00 y con el cambio de hora);
     - la rellena la ingesta al cerrar cada parada, también las `inferido`;
     - `NULL` en los trenes sin horario y en las filas antiguas.
   - **Pruebas:**
     - que el cierre normal y el inferido guardan la hora correcta;
     - un viaje que pasa la medianoche;
     - un tren sin horario.

   Explícalo en el informe y en un ADR corto. La fase 6 hará los agregados.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-4-api`;
  - especificación en el informe;
  - pruebas;
  - informe `docs/informes/fase-4.md`;
  - **revisión independiente** con un subagente sin contexto;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - guía de defensa `docs/defensa/fase-4.md`: REST, OpenAPI, SSE, el control de la carga, y cómo escalaría a más usuarios (varias instancias y un bus).
- **ADR:** los que hagan falta.
- **Entorno:** `JAVA_HOME` al JDK 25. Docker Desktop arrancado.
- **Límites:** no toques nada fuera de esta carpeta. Nada de pago. Sin push.
- **Presupuesto limitado.**
  - Si no llegas, este es el orden de prioridad: la hora programada en `retrasos` (punto 7: es pequeña y cuanto antes entre, antes se recogen datos completos); SSE con instantánea y cambios; trenes por núcleo; ficha del tren; estaciones; OpenAPI; prueba de carga; el resto.
  - Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - cifras de la prueba de carga;
  - commit de fusión;
  - pruebas;
  - lo que queda.
