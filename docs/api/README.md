# Contrato de la API para el frontend

Un JSON de ejemplo por endpoint, sacado de la aplicación en marcha con los feeds reales de Renfe el 20-11-2026 a las 14:57 (hora de Madrid) y recortado (listas de 2-5 elementos; el recorrido de la línea, con uno de cada 25 puntos). **Datos: Renfe (CC BY 4.0).**

Para qué sirven:

- **el frontend (fase 5)** los usa como datos simulados en sus pruebas;
- **OpenAPI** los enseña como ejemplo de cada respuesta en `/api/docs` (Maven los copia al classpath);
- **`EjemplosApiTests`** comprueba que cada uno se lee con su record y vuelve a escribirse igual: si cambia el contrato, la prueba falla hasta que se actualice el ejemplo.

La documentación completa (campos, parámetros y errores) está en `GET /api/docs` (OpenAPI 3) y `GET /api/docs/ui` (Swagger UI).

| Fichero | Endpoint | Record |
|---|---|---|
| `nucleos.json` | `GET /api/nucleos` | `List<Nucleo>` |
| `trenesDeNucleo.json` | `GET /api/nucleos/51/trenes` | `List<TrenMapa>`: uno en marcha, uno en origen (con `salida`) y uno cancelado |
| `fichaTren.json` | `GET /api/trenes/{id}` | `FichaTren`: un R1 con 27 min de retraso y la evolución en el viaje |
| `fichaEstacion.json` | `GET /api/estaciones/71801` (Barcelona-Sants) | `FichaEstacion` |
| `estacionesCercanas.json` | `GET /api/estaciones?cerca=41.3870,2.1700&radio=3000&limite=5` | `List<EstacionCercana>` |
| `linea.json` | `GET /api/lineas/51/R4` | `Linea` (recorrido en GeoJSON) |
| `avisos.json` | `GET /api/avisos?nucleo=51` | `List<Aviso>` |
| `directo.json` | `GET /api/directo?nucleo=51`, evento `instantanea` | `Instantanea` |
| `directo-cambios.json` | evento `cambios` | `Cambios` |
| `directo-latido.json` | evento `latido` | `Latido` |
| `error.json` | cualquier error (aquí, un 404) | `ProblemDetail` (RFC 9457) |

## Cómo se consume el directo

```js
const fuente = new EventSource("/api/directo?nucleo=51");
const trenes = new Map();
fuente.addEventListener("instantanea", (e) => {
  const { trenes: lista } = JSON.parse(e.data);
  trenes.clear();
  lista.forEach((t) => trenes.set(t.id, t));
});
fuente.addEventListener("cambios", (e) => {
  const { altas, cambios, bajas } = JSON.parse(e.data);
  [...altas, ...cambios].forEach((t) => trenes.set(t.id, t));
  bajas.forEach((id) => trenes.delete(id));
});
fuente.addEventListener("latido", (e) => {
  const { antiguedadS } = JSON.parse(e.data); // «datos de hace X s»
});
```

- Al reconectarse (el navegador lo hace solo), llega otra `instantanea`: se sustituye todo.
- Si la API está llena, responde **503** con `Retry-After`; `EventSource` no reintenta un 503, así que el frontend tiene que volver a abrirlo pasado ese tiempo.
- Las horas van en hora de Madrid con su desfase; los retrasos, en minutos enteros; lo que no se sabe, `null`.
