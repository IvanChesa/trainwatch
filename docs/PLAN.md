# TrainWatch: plan del proyecto

Estado: **aprobado para empezar** (20-nov-2026). Lo ha escrito la sesión de estrategia. Nombre y dirección decididos por Iván: **TrainWatch**, en **`trainwatch.ivanchesa.com`**. Las demás decisiones del §9 (D2-D5) se cierran tras la fase 0.

## Cambio de stack (Iván, 20-11): React + Java

El frontend pasa de Angular a **React** (React 19, Vite y TypeScript). El backend sigue en Java 25 y Spring Boot. Lo ejecutó la tarea `docs/prompts/React.md` (fase 4r, [ADR 0011](adr/0011-react-en-lugar-de-angular.md)), antes de la fase 4. El §0 conserva «Angular» como registro de la idea original.

## Orden de las fases (Iván, 20-11)

Tras la fase 4: **7 (despliegue en Azure) → 5 (mapa) → 6 (estadísticas) → hito de portfolio**.
- El despliegue va antes para que la ingesta empiece a recoger datos cuanto antes: las estadísticas necesitan semanas de datos.
- La fase 7 tiene una parte A, que la sesión hace sola, y una parte B con Iván delante: inicios de sesión, push y su «sí» al gasto.
- El hito de portfolio (README final, vídeo, ficha en ivanchesa.com y LinkedIn) sale de la fase 7 a un encargo propio, al final, después de revisar la aplicación en marcha.

## Cambios tras la fase 0 (20-11, `docs/informes/fase-0.md` §7; prevalecen sobre el resto del plan)

1. **Movimiento:** la posición del feed salta de estación en estación (el 78 % está a menos de 50 m de una). La posición en el mapa se **estima** con la última parada, la siguiente, sus horas programadas más el retraso y `shapes.txt`, y el feed la corrige. Por eso la fase 2 proyecta las paradas sobre el recorrido.
2. **Trenes en origen** (el 38,5 % del feed): se pintan aparte («sale a las 10:25») y no cuentan como en marcha.
3. **Puntualidad:** solo con trenes vistos en directo. Un tren que no aparece no cuenta como cancelado; solo los `CANCELED` explícitos.
4. **Núcleos:** todos, con un estado por núcleo. «Sin datos en directo» hoy para Bilbao y Cádiz, y cobertura visible (Sevilla, 31 %). Nada de prometer «todos los trenes de España».
5. **Importación:** los autobuses de sustitución (`route_type` 3) se importan, pero quedan fuera del directo y de la puntualidad. Los campos vienen rellenos de espacios y hay que recortarlos.
6. **Ingesta:**
   - peticiones condicionales con `ETag` cada 5-10 s;
   - un tren no desaparece hasta faltar en 2-3 muestras seguidas;
   - el `stop_id` de las posiciones es la parada actual o la última, no la próxima;
   - retraso final por parada = último retraso conocido cuando `trip_updates` pasa a la siguiente parada;
   - una captura de 24 h antes de dar los volúmenes por buenos.
7. **Datos:**
   - posiciones guardadas **solo cuando cambian** (unas 0,26 M filas al día) y 48 h, en particiones diarias;
   - retrasos (unas 51.000 filas al día) en particiones mensuales.
8. **Estadísticas:**
   - el retraso llega en minutos enteros, así que se publican medias y porcentajes («% con menos de 3 min»);
   - nada público hasta tener semanas de datos.
9. **Riesgos:** baja el de los «huecos en Cataluña» (Rodalies, 100 %). Entra uno nuevo, núcleos que no emiten en directo.
10. **Créditos de la API** (Iván, 20-11): las fases 1 a 5 se programan con el Agent SDK, para gastar los créditos promocionales antes del 26-11. Los encargos están en `docs/prompts/fase-N.md`. Por eso no hay modo tutor (D4): su papel lo cubren las **guías de defensa**, más detalladas en las fases 3 y 4, que Iván repasa antes de las entrevistas.

## 0. Qué es y para qué

**Un mapa con los trenes de Cercanías y Rodalies moviéndose en directo**, con su retraso, y estadísticas propias de puntualidad por línea, por hora y por día. Se abre en el navegador y se entiende en 5 segundos.

**Para qué sirve:**
- **Proyecto Java de portfolio** para los puestos Full Stack y Cloud. Usa Java y Spring Boot en el backend, Angular en el frontend (el stack que más piden las consultoras en España), PostgreSQL, Docker, CI/CD y despliegue en la nube.
- **Lo que lo hace vistoso:** son datos reales y locales (Rodalies de Lleida, la RL4), y genera **cifras propias** que nadie más tiene. Por ejemplo, «la R2 Sud llega con 4 minutos de media a las 8:00». Sirven para la entrevista y para publicarlas en LinkedIn.
- **No es un negocio** ni lleva anuncios: es portfolio, como Torno y Sistema Solar.

## 1. Los datos (comprobados el 20-11-2026)

| Fuente | Qué da | Formato | Estado |
|---|---|---|---|
| `gtfsrt.renfe.com/vehicle_positions.pb` (y `.json`) | Posición de cada tren (latitud, longitud), línea y número, próxima parada y estado | GTFS Realtime | ✅ 364 trenes en ese momento, entre ellos R1, R2 Nord/Sud, R3, R4, R11, R13-R17, **RL4 (Lleida)**, RG1, y C1-C10 de los demás núcleos |
| `gtfsrt.renfe.com/trip_updates.pb` | Retraso de cada tren en sus paradas | GTFS Realtime | ✅ |
| `gtfsrt.renfe.com/alerts.pb` | Avisos e incidencias | GTFS Realtime | ✅ |
| `ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip` | Horarios, paradas, líneas y recorridos (GTFS estático) | ZIP de CSV, unos 15,5 MB | ✅ Se actualiza a diario (última, 20-11 a las 03:09) |
| Posiciones de AV, larga y media distancia (data.renfe.com) | Trenes de largo recorrido | Por ver | Ampliación opcional (fase 8) |

**Licencia:** Creative Commons BY 4.0 (data.renfe.com). Obliga a citar la fuente: «Datos: Renfe (CC BY 4.0)».

**Problema conocido:** hay usuarios que avisan en datos.gob.es de que el GTFS de Cercanías **tiene huecos en Cataluña**: faltan circulaciones de algunas líneas, como la RG1 y la RT2. La fase 0 mide cuánto afecta. La aplicación tiene que funcionar aunque un tren en directo no tenga su viaje en el horario: se pinta igual, con la información que haya.

## 2. Qué hace la versión 1

1. **Mapa en directo.**
   - Todos los trenes del núcleo elegido (Rodalies, Madrid, Valencia…), que se mueven sin saltos entre una actualización y la siguiente.
   - Color por retraso.
   - Filtro por línea.
2. **Ficha del tren:** línea, origen y destino, próxima parada, retraso actual y su evolución durante el viaje.
3. **Ficha de la estación:** próximos trenes con su retraso, y la puntualidad de la estación.
4. **Estadísticas propias,** recogidas desde el primer día en marcha:
   - puntualidad por línea, por franja horaria y por día de la semana;
   - las líneas y los tramos con más retraso;
   - la evolución semanal.

   Una página por línea, con la RL4 de Lleida destacada.
5. **Avisos de Renfe** sobre el mapa y en las fichas.
6. **Estado de los datos:** cuándo llegó la última actualización. Si Renfe deja de enviar, la aplicación lo dice («datos de hace 6 min») en vez de enseñar trenes parados.
7. **Calidad:**
   - sin cookies ni analítica;
   - accesible (WCAG 2.1 AA), con vista de lista para quien no use el mapa;
   - en español;
   - aviso de que no es un servicio oficial de Renfe.

**Fuera de la v1:** cuentas de usuario, avisos por correo o notificaciones, app móvil, predicción de retrasos con IA y largo recorrido (fase 8).

## 3. Arquitectura

```
Renfe GTFS-RT (cada ~20 s) ──► Ingesta (Spring Boot, @Scheduled + Resilience4j)
                                   │  decodifica protobuf, cruza con el GTFS estático
                                   ├─► estado actual en memoria ──► SSE ──► navegador (React + MapLibre)
                                   └─► PostgreSQL + PostGIS
                                         · posiciones (48 h, particionadas por día)
                                         · retraso final de cada tren en cada parada (histórico)
                                         · agregados por línea, hora y día (para siempre)
Renfe GTFS estático (cada noche) ──► Importación (Spring Batch) ──► paradas, líneas, viajes, horarios y recorridos

API REST (OpenAPI) ──► fichas de tren y estación, estadísticas
Front: trainwatch.ivanchesa.com (Cloudflare Pages) · API: trainwatch-api.ivanchesa.com (servidor en la nube, detrás de Cloudflare)
```

**Decisiones que hay que saber defender** (cada una en un ADR):
- **SSE en vez de WebSocket para el directo.** El navegador solo recibe y no envía, así que SSE basta, es HTTP normal, se reconecta solo y pasa sin problemas por Cloudflare.
- **Qué se guarda y cuánto tiempo.** Las posiciones sin procesar son ~1 M filas al día y solo se guardan 48 h, en particiones diarias que se borran enteras. De cada tren se guarda el retraso final en cada parada, mucho menos volumen, y de ahí salen los agregados, que se guardan siempre. Así la base de datos no crece sin control en un servidor pequeño.
- **PostGIS** para lo espacial: la estación más cercana, los trenes dentro del mapa visible y el recorrido de cada línea.
- **Resiliencia:**
  - si el feed falla, se reintenta con espera creciente y un circuit breaker (Resilience4j);
  - las métricas de la ingesta (retraso del feed, errores, trenes sin viaje en el horario) se ven en Actuator y Micrometer.
- **Importación idempotente del GTFS estático:** el ZIP de cada noche se carga en tablas nuevas y se cambia de golpe al terminar. Si una importación falla, la aplicación sigue con la anterior.
- **Movimiento suave:** el navegador interpola la posición entre una actualización y la siguiente, siguiendo el recorrido de la línea si el GTFS lo trae.

## 4. Herramientas

| Para qué | Herramienta |
|---|---|
| Lenguaje y framework | **Java 25 (LTS)** y **Spring Boot**: la versión estable más reciente, Spring Boot 4 o 3.5 si alguna librería aún no está lista |
| Tiempo real de Renfe | `gtfs-realtime-bindings` (MobilityData, la librería oficial del formato) |
| Base de datos | PostgreSQL con PostGIS; migraciones con Flyway |
| Procesos programados | Spring Batch (GTFS estático) y `@Scheduled` (tiempo real) |
| Resiliencia y métricas | Resilience4j, Spring Boot Actuator y Micrometer |
| API | Spring Web con OpenAPI (springdoc), y SSE |
| Frontend | **React 19** con **Vite** y TypeScript, y **MapLibre GL** y mapas de OpenFreeMap (gratis, sin clave; se cita OpenStreetMap) |
| Pruebas | JUnit 5, **Testcontainers** (PostgreSQL de verdad en las pruebas), feeds grabados como datos de prueba, Vitest y Playwright en el frontend |
| Contenedores | Docker y Docker Compose (en local y en el servidor) |
| CI/CD | GitHub Actions: compilar, probar, crear la imagen en GHCR y desplegar |
| Infraestructura | Terraform para el servidor y su red (D2) |
| Front y DNS | Cloudflare Pages y DNS, como Torno y Sistema Solar |
| Desarrollo | Claude Code con SDD: especificación y plan por fase, revisión independiente y guía de defensa |

## 5. Dónde se publica

| Qué | Dónde |
|---|---|
| Web | `trainwatch.ivanchesa.com` (Cloudflare Pages), como los otros proyectos |
| API y directo | `trainwatch-api.ivanchesa.com`, un servidor en la nube (D2) detrás de Cloudflare |
| Código | GitHub `IvanChesa/trainwatch`, público y con licencia MIT, como Torno y Sistema Solar |
| Portfolio | Ficha en ivanchesa.com, LinkedIn y CV |

## 6. Fases

Mismo ciclo que en Torno y en ivanchesa.com: especificación, rama, pruebas, revisión independiente, fusión y guía de defensa. Lo programa una sesión nueva por bloque; esta sesión planifica y revisa.

| Fase | Qué | Aceptación |
|---|---|---|
| **0 · Prueba técnica** (2-3 días) | Leer los tres feeds en Java, cruzarlos con el GTFS estático y medir la calidad: qué porcentaje de trenes encuentra su viaje en el horario, por núcleo, con Rodalies y la RL4 aparte | Informe con cifras. Si en Rodalies falta más del 30 %, se replantea cómo enseñarlos |
| **1 · Cimientos** | Monorepo (`backend/`, `frontend/`, `infra/`), Java 25 y Spring Boot, Docker Compose con PostGIS, CI y el primer ADR | Proyecto vacío con pruebas en CI |
| **2 · GTFS estático** | Importación nocturna con Spring Batch, idempotente y con cambio de golpe | Carga completa en menos de X min; pruebas con un GTFS pequeño de ejemplo |
| **3 · Ingesta en directo** | Lectura cada ~20 s, cruce con el horario, estado en memoria, posiciones y retrasos en base de datos, resiliencia y métricas | 24 h seguidas sin errores; reintentos probados simulando caídas del feed |
| **4 · API y directo** | REST con OpenAPI (núcleos, trenes, estaciones) y SSE; CORS para la web y `localhost:5173` (Vite) | Pruebas de los endpoints; el SSE aguanta 200 clientes en local |
| **5 · El mapa** | React y MapLibre: trenes moviéndose, colores, fichas, filtro de línea, vista de lista y accesibilidad | e2e con Playwright; Lighthouse ≥ 90 en móvil (el mapa pesa) |
| **6 · Estadísticas** | Agregados, páginas por línea y RL4 destacada | Cifras comprobadas a mano contra los datos sin procesar en una muestra |
| **7 · Despliegue y portfolio** | Servidor con Terraform, dominio, HTTPS, despliegue automático, copias de la base de datos y alertas. Después, el **hito de portfolio**: README con el esquema, vídeo de 60 s, ficha en ivanchesa.com y publicación en LinkedIn | La aplicación en marcha 7 días seguidos en producción |
| **8 · Opcional** | AV, larga y media distancia en el mapa; repetición de un día pasado | — |
| **Próximas versiones: vista 3D** (Iván, 20-11: «lo haremos luego, en próximas versiones») | Mapa inclinado con relieve y edificios; trenes como modelos 3D (Three.js dentro de MapLibre, mallas instanciadas) siguiendo la vía; cámara que sigue al tren; botón 2D/3D (2D en móvil y con movimiento reducido). Necesita el trazado de las vías: los `shapes` del GTFS o las vías de OpenStreetMap | Unos 60 fps con todos los trenes en un portátil normal; Lighthouse sin empeorar en 2D |

**Para dejarlo preparado en la v1:** el modelo de datos guarda el rumbo y la posición sobre la línea de cada tren, y el frontend separa la capa de trenes del resto. Así la vista 3D se añade después sin rehacer nada.

**Calendario orientativo:** 6-8 semanas de trabajo efectivo. El histórico de puntualidad empieza a valer cuando lleve unas semanas recogiendo, así que conviene desplegar la ingesta pronto (fase 3), aunque el mapa llegue después.

## 7. Riesgos

| Riesgo | Cómo se cubre |
|---|---|
| Huecos del horario en Cataluña | Medidos en la fase 0; los trenes sin viaje se pintan igual, con lo que se sepa; se enseña el dato de cobertura |
| Renfe cambia o corta el feed | Resiliencia, estado de los datos visible y feeds grabados para las pruebas y la demo |
| La base de datos crece sin control | Retención de 48 h de las posiciones, particiones diarias y agregados |
| Coste del servidor | D2: opción gratuita o de estudiante; alarmas de gasto |
| Que parezca oficial | Nombre sin «Renfe», sin su logo ni sus colores como identidad, y aviso «no oficial» visible |
| Que Iván no pueda defender el código | Guía de defensa en cada fase y modo tutor (D4) en la ingesta y las estadísticas |

## 8. Lo legal

- **Datos de Renfe con CC BY 4.0:** se cita «Datos: Renfe (CC BY 4.0)» en la web y en el README.
- **Mapa:** se cita «© OpenStreetMap» y el proveedor de los mapas, como piden sus condiciones.
- **Sin datos personales, sin cookies ni analítica:** no hace falta banner. El aviso legal y la privacidad mínima pueden enlazar a los de ivanchesa.com.

## 9. Decisiones para Iván

| # | Decisión | Recomendación |
|---|---|---|
| D1 | **Nombre y dirección** | ✅ **Decidido (Iván, 20-11): TrainWatch, en `trainwatch.ivanchesa.com`** (la API, en `trainwatch-api.ivanchesa.com`) |
| D2 | **Dónde va el backend** (Java necesita un servidor encendido) | ✅ **Decidido (Iván, 20-11): Azure for Students**, activado con el correo de UNIR: 100 $ de crédito sin tarjeta, que caducan el **20-11-2027**. Al acabar el crédito o los estudios, se migra a Oracle Always Free o AWS con Terraform y las copias de la base de datos. Opciones que se valoraron: 1) **Azure for Students**, si tu correo de ILERNA o UNIR lo admite: unos 100 $ de crédito al año sin tarjeta, y suma Azure al CV. 2) Si no, **Oracle Cloud Always Free** (0 €; ojo, Oracle puede recuperar las máquinas que considera ociosas). 3) **AWS**, unos 12-15 €/mes con un servidor pequeño. Lo comprobamos en la fase 0 |
| D3 | **Alcance** | Todos los núcleos de Cercanías y Rodalies desde el principio: cuesta lo mismo y la demo luce más. La RL4 y Rodalies, destacadas |
| D4 | **Modo tutor** | Que escribas tú, con la sesión como profesora, la ingesta (fase 3) y el cálculo de las estadísticas (fase 6). Es lo que más preguntarán en una entrevista de Java |
| D5 | **Repo** | Público con licencia MIT, como los otros proyectos |
