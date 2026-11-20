Eres la sesión de programación de **TrainWatch**, la fase 3 («Ingesta en directo»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0» (el punto 6 es la especificación de esta fase), §3 y la fase 3 en §6;
- `docs/informes/fase-0.md`, `fase-1.md` y `fase-2.md`.

Construye sobre lo fusionado en `main`. Si la fase 2 no está fusionada o `./mvnw verify` falla, **para** y explica por qué en `docs/informes/fase-3.md`: esta fase depende del horario importado.

## Objetivo

Leer en continuo los tres feeds de Renfe, saber en cada momento qué trenes hay y dónde están, y guardar el historial que necesitarán el mapa y las estadísticas.

**Feeds:** `https://gtfsrt.renfe.com/vehicle_positions.pb`, `trip_updates.pb` y `alerts.pb`. Lo que sabemos de ellos (fase 0):
- **Ritmo:** se renuevan cada ~20 s y mandan `ETag`, `Last-Modified` y `Cache-Control: max-age=30`.
- **Posiciones:** van casi siempre pegadas a las estaciones. El `stop_id` es la parada **actual o la última**, no la próxima.
- **Retraso:** viene **en minutos enteros** y solo para la **próxima** parada, en `trip_updates`.
- **Trenes en origen:** el 38,5 % de lo que llega son trenes esperando en origen (unos 25 min antes de salir).
- **Muestras incompletas:** a veces llega una muestra con un tercio de trenes menos.
- **Sin coordenadas:** un 0,5 % de las posiciones, con `stop_id` «00000».
- **Núcleos sin directo:** Bilbao y Cádiz no emiten nada.

## Qué construir

1. **Cliente de los feeds:**
   - `@Scheduled` cada 5-10 s con **peticiones condicionales** (`If-None-Match` o `If-Modified-Since`): un 304 no descarga nada;
   - decodificación con `gtfs-realtime-bindings`;
   - **Resilience4j** (reintento con espera creciente y circuit breaker) y tiempos de espera cortos.
2. **Estado en memoria** de cada tren: un mapa concurrente cruzado con el horario importado (viaje, línea, núcleo, paradas en orden y proyección sobre el recorrido).
   - **Estados:** `EN_ORIGEN` (todavía no ha salido), `EN_MARCHA`, `TERMINADO` o `CANCELADO` (solo con `CANCELED` explícito).
   - **Un tren desaparece** solo tras faltar en **3 muestras nuevas seguidas**; una muestra incompleta no cuenta como desaparición.
   - **Trenes sin viaje en el horario:** se guardan con lo que traigan.
3. **Posición estimada** (cambio 1 tras la fase 0): con la última parada y la siguiente, sus horas programadas más el retraso y la proyección sobre el `shape` (fase 2).
   - Calcula la fracción del recorrido, las **coordenadas estimadas** y el **rumbo**, que se guarda para la futura vista 3D.
   - Cuando llega una posición nueva del feed, corrige.
   - Sin recorrido, usa la posición del feed tal cual.
4. **Persistencia** (Flyway):
   - **`posiciones`:** una fila **solo cuando cambia algo**, con particiones **diarias** y un proceso que borra las de más de **48 h** (`DROP` de partición, no `DELETE`);
   - **`retrasos`:** el **retraso final por parada**, que es el último retraso conocido cuando `trip_updates` pasa a la siguiente. Con fecha de servicio, viaje, línea, núcleo, parada, secuencia y minutos. Particiones **mensuales**;
   - **`avisos`:** el estado actual, que sustituye al anterior en cada muestra, colgado de la línea o de la estación.
5. **Estado por núcleo:**
   - trenes en directo frente a trenes que el horario da en marcha;
   - cobertura en %;
   - «sin datos en directo» cuando un núcleo no emite (hoy, Bilbao y Cádiz).
6. **Métricas** (Micrometer y Actuator):
   - antigüedad del último feed bueno;
   - descargas con 200 y con 304;
   - errores;
   - trenes por estado;
   - trenes sin viaje en el horario;
   - filas escritas.

   Y un `GET /api/directo/estado` con la hora del último dato, el número de trenes y la cobertura por núcleo.
7. **Pruebas:**
   - **Muestras reales:** copia a `src/test/resources/gtfsrt/` 4-6 ficheros `.pb` de `spike/datos/`. Son datos CC BY 4.0 de pocos KB; cita la fuente en un README de esa carpeta. Elige una secuencia que tenga un tren cambiando de parada y una muestra incompleta.
   - **Unitarias:** estados, tolerancia a muestras incompletas, cierre del retraso final por parada, posición estimada y rumbo.
   - **Integración con Testcontainers:** de un `.pb` grabado a filas en `posiciones` y `retrasos`, con particiones creadas y borradas.
   - **Cliente:** un 304 no procesa nada, y el circuit breaker se abre tras varios fallos (con un servidor HTTP simulado).
8. **Prueba real corta:** arranca la aplicación contra los feeds de verdad unos **10 minutos**. Apunta en el informe los trenes por estado, las filas escritas, la cobertura por núcleo y lo que haya fallado.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-3-ingesta`;
  - especificación en el informe;
  - pruebas;
  - informe `docs/informes/fase-3.md`;
  - **revisión independiente** con un subagente sin contexto, sobre todo de la concurrencia y del cierre del retraso;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - **guía de defensa** `docs/defensa/fase-3.md`.

  La guía es especialmente importante en esta fase, porque Iván no la ha escrito él. Tiene que poder explicar el estado en memoria, la concurrencia, las peticiones condicionales, Resilience4j, las particiones y cómo se calcula el retraso final.
- **ADR:** uno por decisión. Al menos: estado en memoria frente a base de datos, la estrategia de particiones y la estimación de la posición.
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`). Docker Desktop arrancado.
- **Límites:** no toques nada fuera de esta carpeta. Nada de pago. Sin push.
- **Presupuesto limitado.**
  - Si no llegas, este es el orden de prioridad: cliente con 304 y Resilience4j; estado en memoria con la tolerancia; `retrasos`; `posiciones` con particiones; posición estimada; métricas; prueba real.
  - Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - las cifras de la prueba real;
  - commit de fusión;
  - pruebas;
  - lo que queda.
