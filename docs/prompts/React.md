Eres la sesión de programación de **TrainWatch**, una tarea corta: **cambiar el frontend de Angular a React**. Trabajas sin nadie al otro lado: no hagas preguntas. Decide con criterio y deja apuntado lo que decidas.

**Lee primero:**
- `CLAUDE.md`;
- `docs/PLAN.md`: «Cambios tras la fase 0» y el cambio de stack;
- `docs/informes/fase-1.md`, que es donde se montó el esqueleto de Angular;
- el `README.md`.

## La decisión

Iván, el 20-11: quiere el stack **React + Java**. El **backend no cambia**: sigue siendo Java 25 y Spring Boot, con todo lo hecho en las fases 1-3. **Solo cambia el frontend**, que hoy es un esqueleto de Angular 22 con una página «en construcción»: aún no hay mapa ni lógica.

## Qué hacer

1. **Rama** `fase-4r-react`, creada desde `main`, que tiene que estar en verde.
2. **Sustituye `frontend/`** por un proyecto de React. Borra el de Angular (`node_modules`, `dist` y la configuración de Angular) y crea el nuevo:
   - **React 19** (la estable más reciente) con **Vite** y **TypeScript estricto**: `npm create vite@latest` con la plantilla `react-ts`;
   - **pruebas:** **Vitest** y **Testing Library**;
   - **calidad:** ESLint, el que trae la plantilla.

   Nada de librerías de estado ni de interfaz todavía: llegarán en la fase 5 si hacen falta.
3. **La misma página** «TrainWatch · en construcción» que había en Angular, con:
   - el aviso de **proyecto no oficial**;
   - «Datos: Renfe (CC BY 4.0)»;
   - una llamada a `GET /api/estado` del backend que enseña su versión y su hora, o «backend no disponible». Usa el proxy de Vite hacia `localhost:8080` en desarrollo.

   Una prueba de Vitest del componente y otra de la llamada, simulada.
4. **CI:** ajusta el trabajo del frontend en `.github/workflows/ci.yml` (Node 24, `npm ci`, lint, pruebas y build). El del backend no se toca.
5. **Puertos y CORS:** el frontend de desarrollo pasa a `http://localhost:5173`. Cambia en el backend el origen permitido de `4200` a `5173`, si ya existe en la configuración. Si no existe todavía, apúntalo para la fase 4.
6. **Documentación:**
   - un **ADR nuevo**, «React en lugar de Angular», que **sustituye** al de Angular: márcalo como sustituido, sin borrarlo. Contexto: decisión de Iván por el mercado de trabajo, React + Java; Angular ya está en sus proyectos Torno y Sistema Solar;
   - el `README.md` al día (cómo arrancar el frontend);
   - el plan, en `docs/PLAN.md`: §4 Herramientas y las fases 4 y 5, donde ponga Angular, pasan a decir React;
   - una nota en `docs/prompts/fase-4.md` y `fase-5.md` si nombran Angular o el puerto 4200;
   - el `CLAUDE.md`, si nombra Angular.
7. **Comprueba:** `npm run lint`, `npm test` y `npm run build` en verde, y `./mvnw verify` del backend sigue en verde.

## Cómo trabajar

- **Ciclo corto:**
  - informe breve en `docs/informes/fase-4r.md`;
  - **revisión independiente** con un subagente sin contexto (rápida: es una tarea pequeña);
  - fusión `--no-ff` en `main`;
  - línea en `DIARIO.md`;
  - una sección breve en una guía de defensa nueva, `docs/defensa/fase-4r.md`: por qué React y Vite, y en qué se diferencia de Angular, porque te lo preguntarán.
- **Límites:** no toques nada fuera de esta carpeta. Nada de pago. Sin push.
- **Presupuesto muy limitado:** ve al grano.
- **Al terminar:** resumen de 6 líneas como máximo: versiones, pruebas y commit de fusión.
