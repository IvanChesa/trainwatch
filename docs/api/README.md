# Contrato de la API para el frontend

Un JSON de ejemplo por endpoint, sacado de la aplicación en marcha con los feeds reales de Renfe el 20-11-2026 a las 14:57 (hora de Madrid) y recortado (listas de 2-5 elementos; el recorrido de la línea, con uno de cada 25 puntos). **Datos: Renfe (CC BY 4.0).**

Para qué sirven:

- **el frontend (fase 5)** los usa como datos simulados en sus pruebas;
- **OpenAPI** los enseña como ejemplo de cada respuesta en `/api/docs` (Maven los copia al classpath);
- **`EjemplosApiTests`** comprueba que cada uno se lee con su record y vuelve a escribirse igual: si cambia el contrato, la prueba falla hasta que se actualice el ejemplo.

La documentación completa (campos, parámetros y errores) está en `GET /api/docs` (OpenAPI 3) y `GET /api/docs/ui` (Swagger UI).

| Fichero | Endpoint | Record |
|---|---|---|
| `nucleos.json` | `GET /api/nucleos` | `List<Nucleo>`. `limites` es el rectángulo donde está el servicio del núcleo, para encuadrar el mapa (ADR 0023; recalculado el 21-11-2026 con el horario real) |
| `trenesDeNucleo.json` | `GET /api/nucleos/51/trenes` | `List<TrenMapa>`: uno en marcha, uno en origen (con `salida`) y uno cancelado |
| `fichaTren.json` | `GET /api/trenes/{id}` | `FichaTren`: un R1 con 27 min de retraso y la evolución en el viaje |
| `fichaEstacion.json` | `GET /api/estaciones/71801` (Barcelona-Sants) | `FichaEstacion` |
| `estacionesCercanas.json` | `GET /api/estaciones?cerca=41.3870,2.1700&radio=3000&limite=5` | `List<EstacionCercana>` |
| `linea.json` | `GET /api/lineas/51/R4` | `Linea` (recorrido en GeoJSON) |
| `avisos.json` | `GET /api/avisos?nucleo=51` | `List<Aviso>` |
| `directo.json` | `GET /api/directo?nucleo=51`, evento `instantanea` | `Instantanea`. Con `nucleo=todos` (fase 5b, ADR 0024), lo mismo con los trenes de todos los núcleos y `"nucleo": null` |
| `directo-cambios.json` | evento `cambios` | `Cambios` |
| `directo-latido.json` | evento `latido` | `Latido` |
| `error.json` | cualquier error (aquí, un 404) | `ProblemDetail` (RFC 9457) |
| `puntualidadNucleo.json` | `GET /api/estadisticas/nucleos/51` (fase 6) | `PuntualidadNucleo`: ranking de las líneas (recortado) |
| `puntualidadLinea.json` | `GET /api/estadisticas/lineas/51/{linea}` (fase 6) | `PuntualidadLinea`: por hora (recortado), día, semana y tramos |
| `puntualidadEstacion.json` | `GET /api/estadisticas/estaciones/71801` (fase 6) | `PuntualidadEstacion` |

Los tres de estadísticas salen de la aplicación en local con unas horas de ingesta real del 21-11-2026 (con `muestra-minima` de 30, casi todo sale «insuficiente»: es lo que verá la web con pocos datos). Las cifras **no son publicables**: en producción la API no da ninguna antes del 19-12-2026 (`publicacion.publicado = false`).

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

- **Todos los núcleos a la vez** («Toda España»): `new EventSource("/api/directo?nucleo=todos")`. Los mismos eventos, con `nucleo: null` en la instantánea y los cambios (cada tren lleva el suyo). Pesa más: con los feeds del 21-11, una instantánea de ~140 KB (327 trenes) y cambios de 50-80 KB cada ~7 s. `nucleo` solo admite un código o `todos`: lo demás, 400.
- Al reconectarse (el navegador lo hace solo), llega otra `instantanea`: se sustituye todo.
- Si la API está llena, responde **503** con `Retry-After`; `EventSource` no reintenta un 503, así que el frontend tiene que volver a abrirlo pasado ese tiempo.
- Las horas van en hora de Madrid con su desfase; los retrasos, en minutos enteros; lo que no se sabe, `null`.
