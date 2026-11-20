# ADR 0017 · Una máquina pequeña para el backend y PostgreSQL Flexible Server, dentro de la oferta gratuita

**Fecha:** 20-11-2026 · **Estado:** aceptada (a falta de confirmar el coste real en la parte B) · **Fase:** 7

## Contexto

TrainWatch tiene que recoger datos las 24 horas: la ingesta lee Renfe cada 7 s, guarda en PostgreSQL con PostGIS y sirve la API y el directo. El crédito es de **100 $ para un año** (Azure for Students, hasta el 20-11-2027), así que el objetivo es **≤ 8 $ al mes de media**. Solo se puede crear en algunas regiones; se usa `spaincentral`.

### Lo que consume (medido en local el 20-11-2026, informe §3)

La imagen de producción y PostGIS 18-3.7 con los límites de las máquinas candidatas (1 vCPU para cada uno; 640 MiB para el backend y 2 GiB para PostGIS), base de datos vacía, importación del horario al arrancar y 25 min de ingesta real (~465 trenes):

| | Backend (contenedor) | PostGIS (contenedor) |
|---|---:|---:|
| Memoria en marcha, con el heap al 60 % (primera medición) | ~550 MiB (heap usado 40-140 MiB, comprometido 365 MiB; no-heap ~100 MiB) | ~300-350 MiB |
| Memoria en la importación del horario (36 s) | **559 MiB** como mucho (heap usado ≤ 141 MiB) | **495 MiB** como mucho |
| CPU en marcha | mediana 0,1 % de una vCPU (media 3,4 %) | mediana 0,2 % (media 3 %) |
| CPU en la importación | 60-100 % de una vCPU durante ~40 s | 100 % durante ~40 s |
| Sin importación (arranque con el horario ya cargado) | 283 MiB | — |
| **Configuración final** (compose de producción: solo lectura, `/tmp` en memoria, heap al **50 %**) | **415 MiB** en la primera importación y **430 MiB** en la nocturna; 34 s cada una | — |

Y el disco: `posiciones` ronda 0,3-0,5 M filas al día y se guarda 48-72 h; `retrasos`, ~51.000 al día para siempre (~19 M al año). Con índices, unos pocos GB al año (informe §3).

### Precios en `spaincentral` (API pública `prices.azure.com`, USD, 730 h)

| Pieza | Precio |
|---|---:|
| Máquina B1s (1 vCPU, 1 GiB) · B2ats_v2 (2 vCPU AMD, 1 GiB) | 8,32 $ · 8,91 $ |
| Máquina B1ms (1 vCPU, 2 GiB) · B2als_v2 (2 vCPU, 4 GiB) | 16,72 $ · 35,70 $ |
| PostgreSQL Flexible B1ms (1 vCore, 2 GiB) + 32 GB | 14,53 $ + 4,38 $ |
| Disco P6 (Premium, 64 GB) · E4 (Standard SSD, 32 GB) | 11,23 $ · 2,40 $ |
| IP pública estática Standard · zona DNS privada | 3,65 $ · 0,50 $ |
| Container Apps, 0,5 vCPU y 1 GiB siempre activos (34 µ$/vCPU·s y 4 µ$/GiB·s) | ~55 $ |
| App Service B1 Linux | 13,14 $ |

### La oferta gratuita de Azure for Students (comprobada el 20-11 en las páginas de Microsoft)

Incluye **12 meses** de servicios gratis: **750 h/mes de B1s, B2pts_v2 y B2ats_v2** (las páginas no dejan claro si son 750 h por tamaño o en total; una máquina usa 730 h), **dos discos P6**, **750 h/mes de PostgreSQL Flexible Server B1ms con 32 GB** y 32 GB de copias, **5 GB de Blob Storage**, 10.000 operaciones de Key Vault y 100 GB de salida. Se aplica en cualquier región. La IP pública **no** entra.

### Lo que hay en esta suscripción (`az`, solo lectura, 20-11-2026)

- Regiones permitidas: `italynorth`, `spaincentral`, `switzerlandnorth`, `francecentral` y `denmarkeast`.
- En `spaincentral`, **B1s y B1ms no están disponibles** para la suscripción (`NotAvailableForSubscription`) y B2pts_v2 no existe. **B2ats_v2 sí**, salvo en la zona 1.
- PostgreSQL Flexible Server en `spaincentral`: versiones 11 a **18** y Burstable **B1ms** y B2s.

## Decisión

**El backend en una máquina B2ats_v2 (2 vCPU, 1 GiB, Ubuntu 24.04, disco P6) con Docker Compose (backend y Caddy), y la base de datos en Azure Database for PostgreSQL Flexible Server B1ms (PostgreSQL 18 con PostGIS 3.6, 32 GB), en una red privada.**

- **Coste:** ~**4,2 $/mes** con la oferta gratuita (IP pública y zona DNS privada; lo demás, 0 $). ~50 $ en el año.
- **Memoria del backend:** contenedor limitado a **640 MiB** y heap al **50 %** del límite (~310 MiB, Serial GC; el usado no pasa de ~140 MiB). Con el 60 %, la primera importación llegaba a 599 MiB contando el ZIP en `/tmp` (en memoria): demasiado justo. Con el 50 %, 430 MiB como mucho. La máquina tiene 1 GiB: el sistema y Docker (~250 MB, estimación), Caddy (12 MiB) y el backend (≤ 430 MiB) caben, con **2 GB de swap** para los picos.
- **B2ats_v2:** es la única de las tres gratuitas disponible aquí (B1s no lo está para la suscripción y está en fin de vida), y tiene 2 vCPU para la importación. La máquina se crea **sin zona** (la zona 1 no la admite).
- **PostgreSQL gestionado:** copias automáticas con restauración a un instante (14 días), parches y TLS sin hacer nada, y la memoria de PostGIS fuera de la máquina de 1 GiB.

## Alternativas

| Opción | Coste/mes con la oferta | Sin la oferta | Por qué no |
|---|---:|---:|---|
| **A. Una máquina con Compose (backend + PostGIS)** en B2ats_v2 (1 GiB) | ~3,7 $ | ~24 $ | Backend (~550 MiB) y PostGIS (~350 MiB) no caben en 1 GiB sin vivir en el swap; las copias y los parches de PostgreSQL, a mano |
| A con una máquina de 4 GiB (B2als_v2) | ~39 $ | ~51 $ | La máquina no entra en la oferta: el crédito duraría 2,5 meses |
| **B. Máquina B2ats_v2 + Flexible Server B1ms** (elegida) | **~4,2 $** | ~43 $ | — |
| C. Container Apps (0,5 vCPU y 1 GiB siempre activos) + Flexible Server | ~55 $ de lista | ~74 $ | La ingesta no deja nunca la réplica en reposo: se paga como activa todo el mes |
| D. App Service B1 Linux + Flexible Server | ~13 $ | ~32 $ | No cabe en 8 $/mes |

## Consecuencias

- **Todo depende de que la oferta gratuita se aplique a esta suscripción.** Se comprueba en la parte B, a las 24-48 h, en Cost Management. Si no se aplica, B cuesta ~43 $/mes y no cabe: se para y se decide con Iván (docs/despliegue.md, «Si la oferta gratuita no se aplica»).
- La oferta dura 12 meses, como el crédito: al acabar, se migra (D2).
- 1 GiB es justo. Si el backend crece (fase 6, estadísticas), hay margen bajando el heap (el usado no pasa de ~140 MiB) o subiendo a B2als_v2 (fuera de la oferta).
- La base de datos está en otra máquina: cada consulta cruza la red (dentro de la misma región, ~1 ms). La ingesta escribe por lotes y la API lee el directo de memoria (ADR 0016), así que no se nota.
- PostgreSQL 18 con PostGIS 3.6.4 en Azure, frente a 3.7 en local y en las pruebas: las migraciones solo usan funciones básicas de PostGIS. Se comprueba en el primer arranque (Flyway).
