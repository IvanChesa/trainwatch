# ADR 0024 · «Toda España»: el directo de todos los núcleos es un canal más del difusor (`nucleo=todos`)

**Fecha:** 21-11-2026 · **Estado:** aceptada · **Fase:** 5b · **Lo pedía:** `docs/prompts/fase-5b.md` (Iván, 21-11)

## Contexto

Iván quiere ver a la vez los trenes de todos los núcleos (unos 330-400). El directo (ADR 0015) es por núcleo: `GET /api/directo?nucleo=51`, con una foto y una secuencia por canal, una cola acotada y un hilo virtual por cliente, latido, `Last-Event-ID` (que da una instantánea nueva), máximo de conexiones y desconexión del cliente lento. Lo nuevo tiene que conservar todo eso.

## Decisión

1. **Backend: un canal más.** `DifusorDirecto` tiene un canal `todos` aparte del mapa de núcleos (así no cuenta como núcleo: `/api/nucleos/0/trenes` sigue siendo 404). En cada ciclo de la ingesta, se calcula la foto de cada núcleo y la de todos con los **mismos objetos** `TrenMapa`, y el canal `todos` pasa por el mismo método `difundir` que los demás: los cambios se calculan y se serializan **una vez por ciclo**, no una por cliente. Las conexiones de `todos` usan el mismo `Suscriptor` (cola, hilo virtual, marca de fin) y cuentan para el mismo máximo.
2. **API:** `GET /api/directo?nucleo=todos`. `nucleo` pasa a ser texto con el patrón `^([0-9]+|todos)$` en OpenAPI; cualquier otro valor da 400 `ProblemDetail` con `parametro: nucleo`. Sin el parámetro sigue siendo 400: un olvido no abre el flujo más pesado. En los eventos de `todos`, `nucleo` es `null`; cada tren lleva el suyo.
3. **No hay REST de todos los trenes:** la web solo usa el SSE (la instantánea es la foto entera). Si hiciera falta, sería `GET /api/trenes` sobre `DifusorDirecto.trenes` del canal `todos`.
4. **Web:**
   - «Toda España» es la primera opción del selector y se enlaza con `?nucleo=todos`; Rodalies sigue por defecto;
   - el mapa encuadra el rectángulo que contiene los `limites` de todos los núcleos (la península);
   - **el filtro de línea se desactiva**, con una nota que dice por qué: los nombres se repiten entre núcleos (C1 en Madrid, Valencia, Asturias, Sevilla…). Agruparlo por núcleo obligaría a llevar el núcleo en el filtro, en la URL y en la consulta del recorrido, para un caso (ver una línea concreta) que ya resuelve elegir su núcleo;
   - la lista se agrupa por núcleo, con un encabezado por grupo.
5. **La RL4 ya no se destaca** (Iván, 21-11), ni con un núcleo ni con toda España.

## Alternativas

- **Que la web abra un SSE por núcleo (15 conexiones):** 15 veces el máximo de conexiones por persona y el límite de 6 conexiones HTTP/1.1 por dominio del navegador.
- **`/api/directo` sin parámetro = todos:** cómodo, pero un cliente que se olvide del parámetro recibe el flujo más pesado sin pedirlo.
- **Un canal que reenvía los eventos de cada núcleo** (sin foto propia): la instantánea sería la suma de 15, con 15 secuencias distintas; el cliente no podría detectar huecos.
- **Un máximo de conexiones aparte para `todos`:** se deja para cuando haga falta (ver consecuencias); hoy el límite es el ancho de banda, no la CPU ni la memoria.

## Consecuencias

- Medido el 21-11 con los feeds reales (327 trenes, informe de la fase 5b): **instantánea de 140 KB** y cambios de 50-83 KB por ciclo, frente a 31 y 19 KB de un núcleo. **100 clientes de `todos` durante 60 s**: 0 errores, 0 huecos, latencia p99 50 ms, heap 57-75 MB, CPU < 7 %; **~14 KB/s por cliente** (1,4 MB/s con 100).
- El coste está en la salida de red: un cliente de «Toda España» gasta unas 4-5 veces lo que uno de Rodalies. Si la máquina se queda corta, lo primero es un máximo aparte para `todos` o mandar solo los campos que cambian (ADR 0015).
- El mapa con 327 trenes va igual de fluido que con 87 (sin tareas largas; `frontend/scripts/rendimiento.mjs`).
