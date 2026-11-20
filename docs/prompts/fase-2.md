Eres la sesión de programación de **TrainWatch**, la fase 2 («GTFS estático»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0», §3 y la fase 2 en §6;
- `docs/informes/fase-0.md` y `docs/informes/fase-1.md`.

Construye sobre lo que dejó la fase 1. Si la fase 1 no está fusionada en `main` o no pasa `./mvnw verify`, arréglalo primero y apúntalo.

## Objetivo de la fase 2

Importar cada noche el horario de Renfe (`https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip`, unos 15,6 MB comprimido y 295 MB sin comprimir) a PostgreSQL con PostGIS. Tiene que ser fiable y reproducible, y dejar los datos listos para estimar la posición de los trenes (fase 3).

**Lo que sabemos del fichero** (fase 0):
- **Ficheros:** `stops` (1.167), `routes` (277: 222 de tren y 55 de autobús), `trips` (130.297), `stop_times` (1.793.333; 258 MB), `shapes` (123.734 puntos, 144 recorridos por línea y núcleo, como `10_C1` y `51_R4`), `calendar` (450, un `service_id` por núcleo y día, 30 días) y `transfers`. No trae `calendar_dates`.
- **Formato:** todos los campos van **rellenos de espacios**; hay que recortarlos.
- **Autobuses de sustitución:** `route_type` 3. Se importan, pero se marcan para excluirlos del directo y de la puntualidad.

## Qué construir

1. **Esquema** (Flyway): tablas de `stops` (con un `geography(Point)` o `geometry(Point,4326)`; justifica la elección en un ADR), `routes` (con su núcleo), `trips`, `stop_times`, `calendar` y `shapes` (un `LineString` por recorrido). Índices pensados para lo que viene:
   - próximas salidas de una estación;
   - paradas de un viaje en orden;
   - estaciones cercanas a un punto.
2. **Importación con Spring Batch:**
   - descarga con `ETag` o `Last-Modified`: si el fichero no ha cambiado, no se importa;
   - lectura del ZIP **en streaming**, sin descomprimirlo a disco;
   - carga eficiente: `stop_times` con `COPY` o por lotes, nunca fila a fila.
3. **Cambio atómico:** se carga en un esquema o en tablas de staging y, al terminar bien, se cambia de golpe en una transacción. Si falla a medias, **la aplicación sigue con el horario anterior**. Cuenta la alternativa en el ADR.
4. **Paradas proyectadas sobre el recorrido** (lo que pide el cambio 1 tras la fase 0): para cada viaje, la posición relativa de cada parada sobre el `shape` de su línea (0..1, con `ST_LineLocatePoint` o lo que mejor encaje).
   - Los recorridos van por línea y no por variante, así que comprueba que el orden de las paradas avanza sobre el recorrido.
   - Mide cuántos viajes encajan bien y apúntalo.
5. **Programación:**
   - cada noche (por ejemplo, a las 04:30 hora de Madrid, con zona horaria explícita);
   - un disparo manual: un endpoint protegido o un comando, a tu criterio, explicado;
   - y al arrancar, si la base está vacía.
6. **Métricas y registro:** duración, filas por tabla, fecha del horario y resultado, expuestos en Actuator o en un endpoint `GET /api/horario/estado`.
7. **Pruebas:**
   - un **GTFS pequeño de ejemplo** en `src/test/resources`, hecho a mano o recortado del real, con los espacios de relleno, un autobús y un recorrido;
   - pruebas de integración con Testcontainers: importación completa, idempotencia (repetirla no duplica nada), fallo a media importación que no rompe el horario anterior, y proyección de las paradas;
   - **además, una prueba manual con el ZIP real** (no en CI): cuánto tarda y las filas por tabla, comparadas con las de la fase 0. Apúntalo en el informe.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-2-gtfs-estatico`;
  - especificación breve en el informe;
  - pruebas;
  - informe `docs/informes/fase-2.md`, con cifras reales del ZIP de hoy;
  - **revisión independiente** con un subagente sin contexto, y corrección de lo que encuentre;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - guía de defensa en `docs/defensa/fase-2.md`, sobre todo para explicar Spring Batch, el cambio atómico y la proyección sobre el recorrido;
  - ADRs.
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`). Docker Desktop tiene que estar arrancado para Testcontainers y PostGIS.
- **Límites:**
  - no toques nada fuera de esta carpeta;
  - nada de pago;
  - sin push;
  - el ZIP descargado no va al repo: añádelo al `.gitignore`.
- **Presupuesto limitado** (unos 12 $).
  - Si no llegas, este es el orden de prioridad: esquema e importación completa con el cambio atómico; pruebas; programación; proyección sobre el recorrido; métricas.
  - Haz commits frecuentes.
- **Al terminar:** resumen de 10 líneas como máximo:
  - qué funciona;
  - tiempo de importación y filas;
  - commit de fusión;
  - pruebas;
  - lo que queda.
