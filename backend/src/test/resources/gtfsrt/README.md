# Muestras grabadas de GTFS Realtime

**Datos: Renfe (CC BY 4.0).** Grabadas el 20-11-2026 de `https://gtfsrt.renfe.com/` durante la prueba técnica (fase 0). Se usan solo como datos de prueba; el proyecto no es oficial.

| Fichero | Hora (UTC) | Qué tiene |
|---|---|---|
| `20261120T092815Z_*` | 09:28:15 | 337 posiciones y 342 retrasos |
| `20261120T092835Z_*` | 09:28:35 | 338 y 343; 42 trenes cambian de parada o de estado |
| `20261120T092855Z_*` | 09:28:55 | 340 y 345; el `4023V24219C1` (C1 de Valencia) pasa de la 69103 a la 69104 en `trip_updates` |
| `20261120T092915Z_*` | 09:29:15 | **Muestra incompleta:** 242 posiciones y 244 retrasos (un tercio menos) |
| `20261120T092915Z_alerts.pb` | 09:29:15 | 71 avisos |

Cada muestra son dos ficheros: `vehicle_positions.pb` y `trip_updates.pb`.
