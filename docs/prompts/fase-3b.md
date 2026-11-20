Eres la sesión de programación de **TrainWatch**, la fase 3b («Correcciones de la ingesta»). Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero:**
- `CLAUDE.md`;
- `docs/PLAN.md` («Cambios tras la fase 0»);
- `docs/informes/fase-2.md` y `fase-3.md`.

Trabaja sobre `main`, que tiene que estar en verde.

## Contexto

La sesión de estrategia hizo el 20-11 una **revisión independiente** del backend de las fases 2 y 3. No encontró nada bloqueante, y la concurrencia en memoria está bien resuelta. Sí encontró fallos que **sesgarían las estadísticas** o **congelarían la ingesta**. Antes de construir la API (fase 4), corrígelos. Comprueba cada uno en el código; si alguno no se reproduce, explícalo en el informe.

## Qué corregir

### Importantes
1. **Fallo de la base de datos a mitad de una muestra.**
   - **El problema:** `horario.viaje()` consulta la base de datos dentro del bucle de `EstadoDirecto` (alrededor de `:129-131`, `:147`, `:171`, `:176`, `:217-219`, `:236`, `:280`). Si falla a mitad:
     - la cabecera y el ETag ya han avanzado (`IngestaDirecto` `:179-186`, `ClienteFeeds` `:167`) y el seguimiento de los viajes anteriores ya se ha movido;
     - la lista local `cerrados` se pierde: esos retrasos finales no llegan nunca a `retrasosPendientes`;
     - los cambios de posición ya aplicados no se escriben.
   - **Arreglo:**
     - resolver primero los viajes que hagan falta, con un `try` por viaje (el que falle se salta y se cuenta);
     - **mutar el estado y avanzar la cabecera y el ETag solo al final**, cuando todo ha ido bien;
     - si algo falla, la muestra se puede reprocesar.
   - **Además:**
     - **tiempos máximos cortos** en Hikari (`connection-timeout`) y en el driver de PostgreSQL (`socketTimeout`, y `statement_timeout` en la sesión), en el perfil normal y no solo en local;
     - **`/api/directo/estado` calcula la antigüedad al responder**, no la del último ciclo (`IngestaDirecto` `:250`), para que una ingesta congelada se note.
2. **Paradas saltadas que se pierden sin dejar rastro** (`EstadoDirecto` `:169-175`).
   - **El problema:**
     - si entre dos muestras la próxima parada pasa del índice *i* a *i+2* o más, solo se cierra *i*, y las intermedias no dejan fila ni suman a `sinCerrar`;
     - lo mismo si el viaje se borra tras 3 ausencias (`:181-197`) y luego vuelve: `previo` es `null`.
   - **Arreglo:**
     - cerrar las paradas de *i* a *índice-1* con el último retraso conocido, con una columna `inferido` (migración de Flyway) para poder filtrarlas en las estadísticas;
     - contarlas en una métrica;
     - pensar qué hacer cuando el viaje vuelve tras borrarse. Por ejemplo, conservar el seguimiento un tiempo más que el tren visible, o cerrar como inferidas las paradas que faltan al volver.

     Decide y explica en un ADR.

### Menores
3. **Pruebas que no prueban lo que dicen.**
   - **Ausencias:**
     - **Qué pasa:** `EstadoDirectoTests` `:363-368` (`unaMuestraIncompletaNoCierraLaUltimaParada`), `:558` y `DirectoIntegracionTests` `:209-213` hacen una sola ausencia. Una muestra completa tampoco cerraría nada con una sola, así que en realidad solo comprueban `completa()==false`.
     - **Arreglo:** que demuestren que **3 muestras incompletas** no borran ni cierran, y que 3 completas sí.
   - **Tiempo de respuesta:**
     - **Qué pasa:** en `ClienteFeedsTests` `:101-108`, el servidor simulado duerme antes de mandar las cabeceras, así que no se prueba el plazo con el cuerpo incluido.
     - **Arreglo:** que se pare a mitad del cuerpo.
   - **Reintentos:** que `reintentaConEsperaYSeRecupera` compruebe también la espera.
   - **Pruebas que faltan:**
     - el paso de semiabierto a cerrado del circuito;
     - la base de datos caída a mitad de una muestra (punto 1);
     - las paradas saltadas (punto 2);
     - una prueba de lectores concurrentes mientras escribe la ingesta.
4. **Particiones.**
   - **Qué pasa:** `GestorParticiones` `:106` borra particiones (`DROP`) desde el hilo de la ingesta (`IngestaDirecto` `:109`) y sin `lock_timeout`. Eso bloquea `posiciones` entera y podría congelar la ingesta cuando haya lectores (fase 4).
   - **Arreglo:** una **tarea programada aparte**, con `SET lock_timeout` y `DETACH PARTITION … CONCURRENTLY` antes del `DROP`. Si falla, se reintenta en la siguiente pasada.
5. **Registro de la importación.**
   - **Qué pasa:** `ImportadorHorario` `:216` escribe el resultado fuera de la transacción del cambio de esquema. Si falla después del cambio, la fila queda en curso con el horario nuevo ya en uso, y `HorarioDirectoJdbc` (`:52`) no vacía cachés.
   - **Arreglo:**
     - escribir la versión **dentro** de la misma transacción del cambio;
     - `cargarViaje` (`:111-144`) hace dos consultas sin una foto común: que sea una sola, o una transacción `REPEATABLE READ`.
6. **Tamaños máximos.**
   - **Qué pasa:** se comprueban después de bajar todo, en `ClienteFeeds` `:158` (en memoria) y `DescargaHorario` `:98` (a disco).
   - **Arreglo:** mirar `Content-Length` y cortar la lectura al pasar el límite.
7. **Errores.**
   - `ClienteFeeds` `:126-130` convierte cualquier `Throwable`, incluido `OutOfMemoryError`, en `IOException`. Solo debe capturar `Exception` y dejar pasar los `Error`.
   - `IngestaDirecto` `:340-341` registra `e.toString()` sin la traza. Tiene que registrar la excepción con su pila.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:**
  - rama `fase-3b-correcciones`;
  - pruebas primero en lo posible: una prueba que falle por cada fallo, y luego el arreglo;
  - informe `docs/informes/fase-3b.md`, con una tabla de hallazgo, arreglo y prueba;
  - **revisión independiente** con un subagente sin contexto;
  - fusión `--no-ff`;
  - línea en `DIARIO.md`;
  - un apartado en `docs/defensa/fase-3.md`: «Lo que se corrigió tras la revisión y por qué». Es muy buen material para una entrevista: «encontré este fallo y lo arreglé así».
- **Entorno:** `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`). Docker Desktop arrancado.
- **Límites:** no toques nada fuera de la carpeta `trainwatch`. Nada de pago. Sin push.
- **Si el presupuesto es limitado,** este es el orden de prioridad: 1, 2, 3, 4, 5, 6, 7.
- **Al terminar:** resumen de 8 líneas como máximo:
  - qué se corrigió;
  - pruebas (antes y después);
  - commit de fusión;
  - lo que queda.
