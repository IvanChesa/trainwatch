# ADR 0008 · El estado del directo vive en memoria; la base de datos guarda el historial

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 3

## Contexto

Cada ~20 s Renfe publica unas 360 posiciones y otros tantos retrasos. La ingesta tiene que saber en cada momento qué trenes hay, en qué estado y dónde, y la API (fase 4) y el SSE tienen que leerlo muchas veces por segundo. Además, para decidir hay que comparar con la muestra anterior: si un tren ha cambiado de parada, si el retraso ha pasado a la parada siguiente, cuántas muestras lleva sin aparecer.

## Decisión

- **El estado actual vive en memoria**, en `EstadoDirecto`: un `ConcurrentHashMap<String, Tren>` con un `Tren` **inmutable** por viaje, que se **sustituye entero** en cada muestra.
- **Un solo escritor:** el hilo del `@Scheduled` de la ingesta (`fixedDelay`: dos ciclos nunca se solapan). Los métodos que procesan muestras son además `synchronized`, por si alguien los llama desde otro hilo.
- **Lectores sin bloqueo:** la API, las métricas y el resumen leen el mapa. Pueden ver unos trenes de la muestra anterior y otros de la nueva mientras se procesa, pero nunca un tren a medias. El resumen por núcleo se calcula al final del ciclo y se publica de golpe en un `AtomicReference`.
- **La base de datos guarda el historial:** `posiciones` (solo los cambios, 48 h), `retrasos` (el retraso final por parada, para siempre) y `avisos` (el estado actual, para las fichas). El horario se lee de PostgreSQL una vez por viaje y se cachea.

## Alternativas

- **Leer y escribir el estado en PostgreSQL en cada muestra** (una tabla `trenes_ahora` con `UPSERT`): sobrevive a un reinicio y lo pueden leer varias instancias, pero cada lectura de la API sería una consulta, cada muestra serían ~360 escrituras aunque no cambie nada, y comparar con la muestra anterior obligaría a leerla primero.
- **Redis:** rápido y compartido, pero es otro servicio que desplegar en un servidor pequeño, para un estado que se reconstruye solo en 20 s.
- **Mapa mutable con `synchronized` en cada lectura:** más simple de escribir, pero los lectores bloquearían al escritor y al revés.

## Consecuencias

- **Un reinicio pierde el estado:** en la primera muestra todos los trenes son nuevos y se guarda una fila de cada uno. Los retrasos pendientes (la parada a la que iba cada tren) se pierden: esas paradas no tendrán retraso final. Es un hueco de una parada por tren y reinicio.
- **Una sola instancia:** con dos, cada una tendría su estado y las dos escribirían las mismas filas (`retrasos` tiene clave primaria y `ON CONFLICT`, pero `posiciones` se duplicaría). Para la v1 basta una.
- **Memoria:** ~400 trenes y los viajes del día cacheados, unos pocos MB.
