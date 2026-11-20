# Fase 3b · Correcciones de la ingesta: informe

**Fecha:** 20-11-2026. **Rama:** `fase-3b-correcciones`. **Resultado:** hecha. Los 7 puntos de la revisión de las fases 2 y 3 están corregidos, cada uno con su prueba, y también lo que encontró la revisión independiente de esta fase (una alta y tres medias). **94 pruebas** en el backend: 93 pasan y 1 se omite (la manual de la fase 2, que se ejecutó aparte con el ZIP real). Antes, 63 (62 y 1 omitida). Prueba real de la ingesta sin errores.

## 1. Especificación

Encargo: `docs/prompts/fase-3b.md`. **Entra:** los 7 hallazgos de la revisión del 20-11 (dos importantes, cinco menores), cada uno comprobado en el código antes de tocarlo, con una prueba que lo cubra; ADR para la decisión de las paradas saltadas; informe, revisión independiente, fusión, diario y apartado en la guía de defensa de la fase 3. **No entra:** la API de la fase 4, reconstruir el estado tras un reinicio, varias instancias.

**Aceptación:** `./mvnw verify` en verde con pruebas que demuestren cada arreglo; la importación real y la ingesta real siguen funcionando con los plazos nuevos.

## 2. Hallazgos, arreglos y pruebas

Todos se reprodujeron leyendo el código: ninguno resultó ser una falsa alarma.

| # | Hallazgo | Arreglo | Pruebas |
|---|---|---|---|
| 1 | **Fallo de la base de datos a mitad de una muestra:** cabecera y `ETag` ya avanzados, seguimiento movido, retrasos cerrados perdidos, posiciones aplicadas sin escribir | `EstadoDirecto` en tres pasos: *resolver* (un `try` por viaje; el que falla se salta, cuenta como visto y suma a `trainwatch.directo.viajes.sin.resolver`; tras el primer fallo ya no se pregunta a la base de datos en esa muestra), *calcular* sobre copias (también con un `try` por viaje) y *aplicar* al final. El detector de incompletas también en dos pasos (`evaluar`/`aceptar`). `ClienteFeeds` ya no guarda el `ETag` al descargar: la ingesta llama a `confirmar` tras procesar. Cola acotada también para `posiciones` (50.000); los avisos no se dan por recibidos hasta escribirlos. [ADR 0013](../adr/0013-calcular-primero-aplicar-al-final.md) | `FalloDeLaBaseDeDatosAMitadDeUnaMuestra` (3): viaje que no se puede buscar (sin cambios ni ausencias), el cierre que no se pierde al recuperarse, y un fallo inesperado a mitad que no deja nada aplicado y deja repetir la muestra. Integración: `conLaBaseDeDatosCaidaElDirectoSigueYLasFilasEsperan` (tabla `posiciones` y esquema `horario` renombrados a mitad de la ingesta) y `siElProcesadoFallaElEtagNoSeConfirmaYLaMuestraSeRepite`. Cliente: `sinConfirmarLaMuestraVuelveATraerse` |
| 1b | Sin tiempos máximos en la base de datos fuera de `local` | `application.properties` (todos los perfiles): Hikari `connection-timeout` 5 s; pgjdbc `connectTimeout` 5 s y `socketTimeout` 30 s; `statement_timeout` 15 s en cada sesión (`connection-init-sql`). La importación los amplía a 15 min solo en sus pasos pesados (`ImportacionJobConfig.sinPlazosCortos`: `SET LOCAL` y `setNetworkTimeout`, que Hikari restaura al devolver la conexión) | La importación real (abajo) y todas las de integración corren con ellos |
| 1c | `/api/directo/estado` daba la antigüedad del último ciclo | `ResumenDirecto.alInstante`: se calcula al responder | En `deLosPbGrabados…`: un minuto sin ciclos y la antigüedad sube 60 s |
| 2 | **Paradas saltadas** entre dos muestras y viajes que vuelven tras borrarse: sin fila ni cuenta | Se cierran de la *i* a la *j−1*; las intermedias con el último retraso y `inferido = true` (migración V4). Un viaje que desaparece a mitad se recuerda 3 h; si vuelve, lo que falta se cierra como inferido. Un inferido no pisa uno visto (`ON CONFLICT … WHERE`). Métrica `trainwatch.directo.retrasos.inferidos`. [ADR 0012](../adr/0012-paradas-saltadas-y-viajes-que-vuelven.md) | `ParadasSaltadas` (4): salto A→C, salto sin retraso conocido (suma 2 a `sinCerrar`), viaje que desaparece y vuelve, y que vuelve pasada la retención. Integración: `unCierreInferidoNoPisaUnoVisto` y la columna `inferido` del cierre real |
| 3a | Las pruebas de incompletas hacían **una** ausencia, que tampoco cerraría con una muestra completa | Ahora 3: `tresMuestrasIncompletasNoCierranLaUltimaParadaYTresCompletasSi`, `TresMuestrasIncompletas.noBorranUnTrenYTresCompletasSi`, la de las muestras reales (la incompleta tres veces y luego completas) y la de integración `tresMuestrasIncompletasSeguidasNoBorranLosTrenesQueFaltan` | — |
| 3b | El servidor de prueba dormía **antes** de las cabeceras | `ServidorFeeds.pausaEnCuerpoMs`: manda las cabeceras y medio cuerpo y se para | `unCuerpoQueSeParaAMitadTambienSeCortaPorTiempo` (corta en < 1,5 s con un plazo de 300 ms) |
| 3c | El reintento no comprobaba la espera | El servidor apunta la hora de cada petición | `reintentaConEsperaCrecienteYSeRecupera`: 200 ms y 400 ms entre intentos |
| 3d | Faltaban pruebas | Semiabierto: `trasLaEsperaElCircuitoDejaPasarUnaPruebaYSiVaBienSeCierra` (abierto → semiabierto → abierto si falla → semiabierto → cerrado). Concurrencia: `losLectoresNoVenNuncaUnTrenAMedias` (4 lectores, 1.500 muestras de 50 trenes). Base de datos caída y paradas saltadas: filas 1 y 2 | — |
| 4 | `DROP TABLE` de particiones desde el hilo de la ingesta y sin `lock_timeout` | `GestorParticiones` en su propia tarea programada (cada hora y al arrancar; tres hilos en el programador). Cada sentencia con `SET lock_timeout` (5 s). `DETACH PARTITION … CONCURRENTLY` y luego `DROP`; la pasada siguiente termina lo que quede (`FINALIZE` o la tabla separada). Cambio en el [ADR 0009](../adr/0009-particiones-diarias-y-mensuales.md) | `borrarUnaParticionConUnLectorAbiertoNoBloqueaLaIngestaYSeTerminaDespues`: con un lector abierto, una escritura de la ingesta tarda < 500 ms y la pasada siguiente borra. **Con el `DROP` de antes, la misma prueba falla: la escritura se quedó 14,7 s bloqueada** |
| 5 | Registro de la importación fuera de la transacción del cambio; `cargarViaje` con dos consultas sin foto común | `RegistroImportaciones.marcarCorrecta` dentro del paso `cambio`, en la misma transacción que el cambio de esquema. Si el trabajo falla después del cambio, `ImportadorHorario` no lo desdice (queda `CORRECTA`). `cargarViaje`: una sola consulta con paradas y recorrido | `ImportacionAtomicaTests` (3, nueva): si el cambio se deshace, el registro también; si falla la escritura final del registro, la importación ya es `CORRECTA` con sus cifras; si el trabajo falla en la limpieza, queda `CORRECTA` |
| 6 | Tamaños máximos comprobados después de bajarlo todo | `CuerpoLimitado`, un `BodyHandler` que rechaza un `Content-Length` excesivo sin leer y corta la lectura al pasar el máximo. En `ClienteFeeds` (memoria) y `DescargaHorario` (disco) | `unContentLengthDemasiadoGrandeSeRechazaSinLeer`, `sinContentLengthSeCortaLaLecturaAlPasarElMaximo`, `porTrozosYDentroDelMaximoSeLeeEntera` y las dos equivalentes del ZIP |
| 7 | `Throwable` convertido en `IOException`; errores sin traza | `ClienteFeeds.descargar` captura `Exception` y deja subir los `Error`. `IngestaDirecto.error` y `GestorParticiones` pasan la excepción como último argumento (SLF4J escribe la pila) | Sin prueba automática: provocar un `OutOfMemoryError` en el cliente no es fiable. Se comprueba leyendo el código |

### Decisiones tomadas sin consultar

- **Saltar un viaje que no se puede buscar, no rechazar la muestra.** El encargo pedía las dos cosas («un `try` por viaje» y «la muestra se puede reprocesar»). Se resuelve así: los fallos de un viaje se saltan (con la base de datos caída, casi todos los viajes están en caché y el mapa sigue vivo) y cualquier otro fallo del procesado no aplica nada y no confirma el `ETag`. Saltar no pierde datos gracias al punto 2.
- **El `ETag` se confirma aunque falle la escritura**: las filas quedan en la cola y el mapa sigue al día. Solo los avisos esperan a escribirse, porque no tienen cola.
- **Plazos:** 5 s de Hikari, 30 s de `socketTimeout` y 15 s de `statement_timeout`. La importación, 15 min solo en `indices`, `derivados`, `validar`, `cambio` y `limpieza`.
- **Retención de los viajes retirados: 3 h** (un viaje entero de Cercanías). Tras un reinicio no se infiere nada.
- **Crear particiones también con `lock_timeout`**: se crean con dos días de adelanto, así que un fallo se reintenta a la hora siguiente.

## 3. Cifras

### Pruebas

| | Antes (`main`) | Después |
|---|---:|---:|
| `EstadoDirectoTests` | 28 | 41 |
| `ClienteFeedsTests` | 4 | 10 |
| `DirectoIntegracionTests` | 2 | 9 |
| `DescargaYControladorTests` | 4 | 6 |
| `ImportacionAtomicaTests` | — | 3 |
| Resto (sin cambios) | 25 | 25 |
| **Total** | **63** (1 omitida) | **94** (1 omitida) |

`./mvnw verify` en verde, en unos 2 min. Con las pruebas que añadió la revisión (abajo): **94 pruebas, 93 pasan y 1 omitida** (`EstadoDirectoTests` 41, `DirectoIntegracionTests` 9).

### Importación real con los plazos nuevos

`ImportacionRealManualTests` con el ZIP de Renfe de `datos/gtfs` (15,6 MB): **CORRECTA en 16,4 s** con la base vacía y **16,0 s** con el horario cargado (fase 2: 15-17 s). Las mismas filas: 130.297 viajes y 1.793.333 horas de paso. Ningún paso choca con los plazos.

### Prueba real de la ingesta

`./mvnw spring-boot:run` contra los feeds de Renfe y la base local (con los datos de la fase 3), el 20-11 de 14:10 a 14:14, hora de Madrid. Fue con el commit `8d63d18`, antes de los arreglos de la revisión (§4), que no cambian el camino normal. Al arrancar, Flyway aplicó la V4 sobre `retrasos` ya llena sin problemas. Cifras a las 14:13:29 (unos 3 min de ciclos):

| Medida | Valor |
|---|---:|
| Descargas | 30 con 200 y 45 con 304; **0 errores** de descarga, procesado o base de datos. Los tres circuitos, cerrados |
| Avisos (`WARN`/`ERROR`) en el log | **0** |
| Trenes en memoria | 484 (antigüedad del último dato: 7 s) |
| Viajes sin resolver | 0 |
| Filas escritas | 1.633 en `posiciones`, 157 en `retrasos` (154 filas distintas en la tabla), 5.100 en `avisos` |
| Paradas cerradas como **inferidas** | **5** (el 3 % de los cierres), con el mismo retraso que la parada anterior; por ejemplo, las secuencias 20 y 21 del `1023V20804C5` (C5 de Madrid) |
| Paradas sin cerrar | 10 |
| Trenes desaparecidos | 9 |

Ya en 3 minutos aparecen paradas saltadas de verdad: el hueco del punto 2 no era teórico.

## 4. Revisión independiente

Un subagente sin contexto de la conversación revisó el diff contra el encargo y ejecutó `./mvnw verify`. Vio resueltos en lo esencial los puntos 1 a 6 y el 7 en parte, y encontró lo siguiente. **Todo lo de gravedad alta y media se corrigió**, con prueba.

| # | Hallazgo | Qué se hizo |
|---|---|---|
| A1 (alta) | Con la base de datos caída o colgada, `resolver` preguntaba viaje a viaje: cada uno esperaba su plazo (5-30 s) y, tras vaciar las cachés, una muestra con cientos de viajes nuevos pararía la ingesta durante minutos. La prueba de integración no lo veía porque el fallo de un esquema renombrado es instantáneo | **Corregido.** Tras el primer fallo, en esa muestra ya no se pregunta a la base de datos: se resuelven los viajes que están en caché (`FuenteHorario.enCache`) y el resto se da por no resuelto. Con la base de datos colgada, un ciclo espera como mucho un plazo por feed. Prueba: `trasElPrimerFalloNoSeVuelveAPreguntarALaBaseDeDatosEnEsaMuestra` (1 pregunta en vez de 20) |
| M1 (media) | Un fallo que se repite en el paso de cálculo bloquea el feed muestra tras muestra. Caso concreto: con un horario nuevo, el seguimiento guardaba índices del viaje viejo y `cierre` podía salirse de la lista o cerrar paradas que no eran | **Corregido.** Un `try` por viaje también en el cálculo (se salta y se cuenta). Si el viaje cambia de versión, el seguimiento se vuelve a situar por `stop_id` en la nueva. Pruebas: `unViajeQueCambiaDeVersionEnElHorarioSeVuelveABuscarPorParada` y `unTrenQueNoSePuedeCalcularSeSaltaSinCongelarElFeed` |
| M2 (media) | Una fila sin partición bloqueaba la cola entera, y los lotes de 500 en *autocommit* duplicaban posiciones al reintentar. Además, con la cabecera a 0 y un tren sin marca, la marca era 1970 | **Corregido.** `guardarPosiciones` y `guardarRetrasos` en una transacción (la cola entra entera o nada). Si falla por falta de partición (SQLSTATE 23514), se escribe de una en una y se apartan y cuentan las filas imposibles. La marca se acota también con la cabecera a 0, con la hora de la ingesta. Pruebas: `unaFilaSinParticionNoBloqueaLaColaNiDuplicaLasDemas` (501 buenas y una de 1999: 501 filas, sin duplicados) y `unaMarcaAbsurdaConLaCabeceraSinMarcaSeAcotaConLaHora` |
| M3 (media) | Los retirados que caducan perdían su parada sin rastro | **Corregido:** suman a `sinCerrar`. Prueba en `siVuelveDespuesDeLaRetencionEmpiezaDeCero` |
| B1 | Un `Error` como causa de la `ExecutionException` se envolvía en `IOException` | **Corregido** en `ClienteFeeds` y `DescargaHorario` |
| B2 | Un viaje visto pero sin resolver no ponía a cero sus ausencias | **Corregido.** Prueba: el tren con 2 ausencias que aparece sin resolver no desaparece |
| B3 | Tras muestras saltadas, la parada se cerraba como vista con un retraso viejo | **Corregido:** el seguimiento guarda la muestra en que se actualizó; si no es la anterior, el cierre es inferido |
| B4 | Circular que vuelve a la misma estación (índice 0 o final) | **Se deja y se documenta.** Solo afecta a la C4 de Sevilla en un caso muy concreto (retirado yendo a A que vuelve yendo a A) |
| B5 | `cargarViaje` trae siempre la geometría del recorrido | **Se deja:** es una vez por viaje y día. Se corrige el comentario. Las cachés de viajes y recorridos se vacían juntas, así que no se mezclan versiones salvo, como mucho, durante un ciclo |
| B6 | Flyway hereda el `statement_timeout` de 15 s | **Corregido:** `spring.flyway.init-sqls=SET statement_timeout = 0` |
| B7 | La antigüedad salía del último resumen calculado: si el resumen fallaba, parecía una ingesta parada | **Corregido:** se toma del estado en memoria al responder |
| B8 | `CREATE TABLE … PARTITION OF` pide un cerrojo exclusivo; si la pasada del arranque fallaba, no se reintentaba hasta 1 h después; solo un `DETACH` pendiente por tabla | La primera pasada programada es a los **5 min**. El `CREATE` con `lock_timeout` de 5 s (como mucho 5 s de espera de los `INSERT`, una vez al día) y lo del `DETACH` pendiente quedan **documentados** en el ADR 0009; `CREATE TABLE (LIKE …)` + `ATTACH PARTITION` es la mejora natural si hace falta |
| B9 | Si el trabajo fallaba tras el cambio con el contexto vacío, `terminar` borraba lo que apuntó `marcarCorrecta` | **Corregido:** en ese camino solo se apunta la hora de fin |
| T1-T6 | Plazos sin probar, prueba de concurrencia que también pasaría con el código antiguo, cotas de tiempo justas, «sin leerlo» sin comprobar | Prueba nueva de los plazos en el pool (`SHOW statement_timeout` = 15 s) y de A1; cotas del reintento más holgadas. La de concurrencia se queda como **prueba de regresión** (los lectores ya eran sin bloqueo: lo que se protege es que lo sigan siendo). Que no se lea el cuerpo con `Content-Length` excesivo se apoya en el código de `CuerpoLimitado` (cancela la suscripción al recibirla) |

## 5. Qué quedó fuera

- **El estado y las colas se pierden al reiniciar**, como en la fase 3 (ADR 0008).
- **B4 y B5 de la revisión** (circular que vuelve a la misma estación; geometría que se trae con cada viaje) y el `CREATE … PARTITION OF` con cerrojo exclusivo (B8), documentados.
- **Una muestra que lanza un `Error`** (no una excepción) en el cálculo se repite hasta que deje de hacerlo: los `Error` no se tragan a propósito.
- **Tras un reinicio no se infieren paradas** (ADR 0012).
- **Prueba del punto 7** (un `Error` que sube): solo por lectura de código.
- **«Pruebas primero»:** las pruebas se escribieron junto a cada arreglo, no antes. Las que usan API nueva (`confirmar`, `inferido`, `sinResolver`) no compilan contra el código antiguo; la de particiones sí se ejecutó contra el `DROP` de antes y falla (14,7 s bloqueada).
- **La captura de 24 h** sigue pendiente (fase 7).

## 6. Qué cambiaría del plan

- **Fase 4:** las consultas de la API tienen 15 s de `statement_timeout`. Si alguna necesita más, que lo amplíe en su transacción. La API puede usar `IngestaDirecto.resumen()`, que ya da la antigüedad al responder.
- **Fase 6 (estadísticas):** decidir si cuentan los retrasos `inferido`. Propuesta: publicar la cifra sin inferidos y enseñar cuántos hay.
- **Fase 7:** vigilar `trainwatch.directo.pendientes`, `filas.descartadas`, `viajes.sin.resolver` y `retrasos.inferidos`.
