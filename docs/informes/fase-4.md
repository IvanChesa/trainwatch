# Fase 4 · API y directo — informe

**Estado: parada antes de empezar** (20-11-2026). No se ha escrito código ni se ha creado la rama `fase-4-api`.

## 1. Por qué se para

### 1.1 La fase 3 no está fusionada

El encargo dice: «Si la fase 3 no está fusionada o `./mvnw verify` falla, **para** y explica por qué». Comprobado en `main` (`d95edb8`):

- el último merge es el de la fase 2 (`787b4da`); el `DIARIO.md` acaba en la fase 2;
- no existe `docs/informes/fase-3.md` ni ninguna rama `fase-3-*`;
- en `backend/src/main` no hay cliente de los feeds, ni estado en memoria, ni tablas `posiciones`, `retrasos` o `avisos`.

La fase 4 expone lo que recoge la ingesta (trenes por núcleo, ficha del tren, SSE con cambios, avisos, cobertura). Sin la fase 3 no hay nada que exponer, y las pruebas que pide el encargo («con la ingesta alimentada con los `.pb` de prueba de la fase 3») no se pueden escribir. No se ha ejecutado `./mvnw verify`: la primera condición ya basta para parar.

### 1.2 El objetivo del encargo no cuadra con el plan

El objetivo de esta sesión no es el de `docs/prompts/fase-4.md` (API REST y SSE), sino: «Pasar todo el código hecho hasta ahora a React, revisar bien el contexto». Revisado el contexto, no se hace, por tres motivos:

1. **El backend no se puede «pasar» a React.** React es una librería de interfaz para el navegador. Lo hecho hasta ahora es casi todo Java (25 ficheros, unas 1.600 líneas en `backend/src/main`: Spring Boot, Spring Batch, Flyway, PostGIS). Ese código no tiene equivalente en React; reescribirlo en otro lenguaje de servidor contradiría el propósito del proyecto («Proyecto Java de portfolio», PLAN §0).
2. **Cambiar Angular por React es una decisión de Iván, no de una sesión de programación.** El plan elige Angular a propósito («el stack que más piden las consultoras en España», PLAN §0 y §4), y la fase 1 lo dejó montado (Angular 22.3, ADR y CI). `CLAUDE.md`: «Iván decide, la sesión de estrategia planifica». Un cambio así necesita un ADR y actualizar el plan.
3. **Hoy apenas hay frontend que migrar.** `frontend/src` son 135 líneas: la página «en construcción» con el aviso de no oficial y la cita de los datos. El mapa llega en la fase 5. Si se cambia a React, conviene decidirlo **antes de la fase 5**, y la migración en sí costaría menos de una hora.

## 2. Qué haría falta para seguir

1. Programar y fusionar la **fase 3** (`docs/prompts/fase-3.md`).
2. Lanzar la **fase 4** con su encargo original (`docs/prompts/fase-4.md`).
3. Si Iván quiere React en el frontend:
   - lo decide y lo apunta la sesión de estrategia (cambio en PLAN §0, §4 y fase 5);
   - un ADR nuevo, «React en vez de Angular», con el motivo (por ejemplo, las ofertas a las que apunte) y lo que se pierde (el argumento de las consultoras);
   - la migración de la página actual (Vite + React + Vitest, mismos textos legales, CI del frontend ajustada), como tarea corta antes de la fase 5 o dentro de ella. El backend Java no cambia.

## 3. Qué se ha tocado

Solo este informe. Ni código, ni ramas, ni `DIARIO.md` (la fase no se ha cerrado). `docs/prompts/React.md` sigue sin seguimiento en git, como estaba.
