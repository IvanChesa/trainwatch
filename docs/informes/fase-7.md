# Fase 7 · Despliegue: informe

**Fecha:** 20-11-2026. **Rama:** `fase-7-despliegue`. **Resultado:** **parte A hecha; parte B pendiente** (necesita a Iván: inicios de sesión, tokens y su «sí» al gasto). La arquitectura elegida es una **máquina B2ats_v2 con el backend y Caddy en Docker Compose y PostgreSQL Flexible Server B1ms**, en `spaincentral`, detrás de Cloudflare: **~4,2 $/mes** con la oferta gratuita de Azure for Students (~43 $/mes sin ella). Todo está escrito como código (Terraform, compose, scripts y workflows) y comprobado en local; no se ha creado nada en Azure, Cloudflare ni GitHub.

## 1. Especificación

Punto de partida: `docs/prompts/fase-7.md`, la fase 7 del §6 del plan, el §5 (direcciones) y la decisión D2 (Azure for Students).

**Entra (parte A):**

1. **Arquitectura con cifras** y ADR: consumo medido en local (memoria y CPU del backend y de PostGIS, en marcha y en la importación del horario), al menos dos opciones comparadas, la oferta gratuita y los precios de `spaincentral` de la API pública, ≤ 8 $/mes de media y el disco.
2. **Terraform** en `infra/terraform/`: `azurerm` con versiones fijadas, variables y salidas; estado remoto en Azure Storage con el código para crearlo; red y grupo de seguridad (443 solo desde Cloudflare, sin SSH); identidad administrada; etiquetas `proyecto=trainwatch`; `fmt` y `validate` en la CI.
3. **Imagen y arranque:** `Dockerfile` multietapa sin root con JRE 25 y memoria ajustada; compose de producción; HTTPS «Full (strict)»; SSE sin búferes a través de Cloudflare; rotación de registros.
4. **Despliegue automático** con GitHub Actions: compilar, probar, imagen en GHCR y desplegar al fusionar en `main`; OIDC con identidad administrada federada, sin secretos de larga duración; la web a Cloudflare Pages (y una página provisional, porque la fase 5 no está).
5. **Copias:** automáticas con retención y un procedimiento de restauración probado en local con una copia real.
6. **Vigilancia gratis:** un workflow cada 15 min que falla si la API no responde o los datos tienen más de 5 min; Actuator no público; qué mirar cada día.
7. **Seguridad básica:** Actuator y gestión no públicos, cabeceras, límites de las fases 3b y 4 activos, secretos solo en Key Vault o en GitHub.
8. **Guía de la parte B** (`docs/despliegue.md`) con coste y desmontaje.

**No entra:** crear nada en Azure, Cloudflare o GitHub, `terraform apply`, push, ni la parte B. El hito de portfolio (encargo aparte).

**Aceptación de la parte A:**
- `./mvnw verify` y `npm test` en verde, con pruebas nuevas de lo que cambia en el código (perfil `prod`, cabeceras del SSE, estado de los datos en la web);
- `terraform fmt -check` y `terraform validate` en verde en las dos raíces;
- la imagen se construye y arranca con los límites de producción; Caddy, el compose y los scripts, comprobados en local;
- una copia real restaurada en local;
- revisión independiente, sobre todo de seguridad y coste.

La aceptación del plan (**7 días seguidos en marcha**) es de la parte B.

## 2. Qué se hizo

| Pieza | Detalle |
|---|---|
| **Arquitectura** | [ADR 0017](../adr/0017-maquina-pequena-y-postgresql-gestionado.md): máquina **B2ats_v2** (2 vCPU AMD, 1 GiB, disco P6) con Caddy y el backend en Docker Compose, y **PostgreSQL Flexible Server B1ms** (PostgreSQL 18, PostGIS, 32 GB) en una subred delegada sin IP pública. Cuatro opciones comparadas con precios de la API y la oferta gratuita comprobada |
| **Imagen** | `backend/Dockerfile`: tres etapas (JDK 25 para compilar, capas de Spring Boot, **JRE 25** para ejecutar), usuario `trainwatch` (uid 10001) sin privilegios, `MaxRAMPercentage=60`, Serial GC, `ExitOnOutOfMemoryError`. Se construye desde la raíz (el `pom.xml` copia `docs/api/`); `.dockerignore` deja fuera todo menos `backend/` y `docs/api/`. 532 MB |
| **Perfil `prod`** | `application-prod.properties`: conexión por variables (`TRAINWATCH_DB_*`, con `sslmode=verify-full`), pool de 6, `forward-headers-strategy=native` (detrás de Caddy), apagado ordenado de 20 s y **Actuator en el puerto 8081**, que no se publica, con `health`, `info` y `metrics` y la sonda `readiness` |
| **SSE** | `DirectoSseController` manda `Cache-Control: no-cache, no-transform` y `X-Accel-Buffering: no`; Caddy, `flush_interval -1`; latido de 15 s frente a los 100 s de Cloudflare |
| **Producción** (`infra/produccion/`) | `compose.yaml` (Caddy 2.11.9 y el backend por digest, con límites de memoria, sistema de ficheros de solo lectura, sin capacidades, `no-new-privileges` y registros rotados), `Caddyfile` (certificado de origen, **Authenticated Origin Pulls** de `trainwatch-api` con CA propia, cabeceras de seguridad, solo `GET`/`HEAD`/`OPTIONS` y solo `/api/*`), `desplegar.sh` (secretos de Key Vault, arranque, espera a la sonda y **vuelta a la imagen anterior** si falla), `copia.sh` con su temporizador y `revisar.sh` (lo que hay que mirar cada día) |
| **Terraform** (`infra/terraform/`) | `estado/` (cuenta de almacenamiento del estado, sin claves compartidas, con versionado y cerrojo) y `produccion/` (43 recursos: red, grupo de seguridad, IP, máquina con cloud-init, PostgreSQL, Key Vault, dos identidades administradas, credencial federada, rol propio, cuenta de copias y Cloudflare). `azurerm` 5.14, `cloudflare` 5.29, `.terraform.lock.hcl` en el repo. Claves de PostgreSQL y del token de administración **efímeras** y de solo escritura: no pasan por el estado. [ADR 0019](../adr/0019-terraform-con-estado-remoto-en-azure.md) |
| **HTTPS** | [ADR 0018](../adr/0018-https-con-cloudflare-y-caddy.md): certificado de origen de Cloudflare (15 años) pedido por Terraform y guardado en Key Vault; certificado de cliente de Authenticated Origin Pulls solo para `trainwatch-api`, firmado por una CA propia; grupo de seguridad con el 443 solo desde los rangos de Cloudflare (leídos de su API); sin SSH (puerto cerrado y servicio apagado) |
| **Despliegue** | [ADR 0020](../adr/0020-despliegue-con-oidc-y-run-command.md): `despliegue.yml` en cada push a `main`: `ci.yml` (reutilizado), imagen a GHCR, la API por **OIDC con identidad administrada federada** y `az vm run-command`, y la web con **wrangler** a Cloudflare Pages. Los trabajos de Azure y Pages se saltan mientras no haya variables |
| **CI** | `ci.yml` añade el trabajo «Infraestructura»: `terraform fmt -check` y `validate` de las dos raíces, ShellCheck de los scripts, `caddy validate` con un certificado de usar y tirar y `docker compose config`. Ya no corre en push a `main` (lo hace el despliegue) |
| **Vigilancia** | `vigilancia.yml` cada 15 min con `infra/vigilancia/comprobar.sh`: falla si la API no responde, si la ingesta lleva más de 5 min sin terminar un ciclo o si el último dato tiene más de 5 min (de 01:00 a 05:00 solo avisa). Se activa con `VIGILANCIA_ACTIVA=true` |
| **Copias** | [ADR 0021](../adr/0021-copias-gestionadas-y-pg-dump-nocturno.md): las gestionadas de PostgreSQL (14 días) y `pg_dump` cada noche a las 03:15 a Blob Storage (30 días), sin los datos de `posiciones` |
| **Web** | La página provisional enseña también el **estado de los datos** (`/api/directo/estado`: «Último dato de Renfe hace 12 s: 404 trenes en 2 núcleos», o que no están al día). `VITE_API_URL` para la API en producción; `public/_headers` con CSP y demás cabeceras para Pages |
| **Medición** | `infra/medicion/` (compose con los límites de producción y `medir.sh`) para repetir las cifras del §3 |
| **Guía** | [`docs/despliegue.md`](../despliegue.md): la parte B paso a paso, el coste, las comprobaciones, qué mirar cada día, cómo restaurar, cómo desmontarlo y qué hacer cuando se acabe el crédito |
| **Herramientas** | Terraform 1.17.1 instalado con `winget` (gratis). La CLI de Azure no hace falta hasta la parte B |

## 3. Cifras

### Pruebas

| Comando | Resultado |
|---|---|
| `./mvnw verify` | **125 pruebas, 0 fallos, 1 omitida** (la manual de la fase 2). Antes, 121. Nuevas: `ProduccionIntegracionTests` (4: Actuator no está en el puerto público y sí en el de gestión, con `readiness` y métricas pero sin `env` ni `heapdump`; las cabeceras `X-Forwarded-*` dan `https://trainwatch-api.ivanchesa.com` en OpenAPI; pool de 6) y, en `ApiIntegracionTests`, las cabeceras `Cache-Control: no-cache, no-transform` y `X-Accel-Buffering: no` del SSE |
| `npm run lint` · `npm test` · `npm run build` | En verde; **13 pruebas** (antes, 9): el estado de los datos en la página y `directo.test.ts` (antigüedad, aviso a los 5 min, sin datos). Con `VITE_API_URL`, el build llama a `https://trainwatch-api.ivanchesa.com` |
| `terraform fmt -check` y `validate` | En verde en `estado/` y `produccion/` |
| `terraform plan` de solo lectura | **43 recursos que crear, 0 cambios**, contra la suscripción real (con el `az login` de Iván), con estado local, sin registrar proveedores y con un token de Cloudflare falso, en una copia en la carpeta temporal; el plan se borró. Sin errores de validación del proveedor (PostgreSQL 18, B1ms, B2ats_v2, nombres) |
| ShellCheck · actionlint | Sin avisos en los 5 scripts y en los 3 workflows (`rhysd/actionlint` 1.7.8) |
| `caddy validate` | Configuración válida (Caddy 2.11.9) |
| `comprobar.sh` (vigilancia) | Contra el backend local: pasa (último ciclo hace 5 s, último dato hace 9 s, 446 trenes); falla con un máximo de 1 s; falla si la API no responde |

### Consumo medido en local (20-11-2026, de 18:29 a 18:57)

`infra/medicion/compose.yaml`: la **imagen de producción** (perfil `prod`, 640 MiB y 1 vCPU) y `postgis/postgis:18-3.7` (2 GiB y 1 vCPU, como un B1ms), base de datos vacía y los **feeds reales de Renfe**. `docker stats` cada ~7 s y Actuator cada ~40 s durante 25 min (`medir.sh`); después, una importación forzada con el horario anterior cargado (como la de cada noche), con muestras cada 2 s.

| Medida | Backend | PostGIS |
|---|---:|---:|
| Arranque | 11,5 s (con 1 vCPU) | — |
| Importación al arrancar (base vacía) | 36,9 s | — |
| Importación nocturna (con el horario anterior) | **35,8 s** | — |
| Memoria del contenedor en marcha (mediana · máximo) | **545 · 554 MiB** | **303 · 374 MiB** |
| Memoria en la importación nocturna (máximo) | **559 MiB** | **495 MiB** |
| Heap usado (mediana · máximo) · comprometido · no-heap | 88 · 141 MiB · 365 MiB · 103 MiB | — |
| Memoria con el horario ya cargado (sin importación) | 283 MiB | — |
| CPU en marcha (mediana · media) | **0,09 % · 2,5 %** de una vCPU | **0,17 % · 2,4 %** |
| CPU en la importación | 60-100 % durante ~40 s | 100 % durante ~40 s |
| Errores en 25 min | **0** `ERROR` en el registro; circuitos cerrados; antigüedad máxima del dato, 25 s | — |
| Trenes en memoria | 455-475 | — |
| Caddy (en la prueba del compose de producción) | 12 MiB | — |

**Lectura:** el heap usado no pasa de ~140 MiB, pero la importación hace crecer el comprometido a ~365 MiB y Serial GC no lo devuelve; por eso el contenedor se queda en ~550 MiB después de la primera importación. Con 640 MiB de límite hay ~80 MiB de margen. En una máquina de 1 GiB caben el sistema y Docker (~250 MB, estimación), Caddy (12 MiB) y el backend (~560 MiB), con 2 GB de swap por si acaso. Si hiciera falta, bajar `MaxRAMPercentage` a 45 (~290 MiB de heap) seguiría dejando el doble del máximo usado.

PostGIS (495 MiB como mucho) cabe holgado en los 2 GiB de un B1ms. En un B2ats_v2 junto al backend (opción A) serían ~1,05 GiB solo entre los dos: no cabe.

### Disco

Tamaños de la base de la medición a las 18:47 (25 min de datos), en PostgreSQL:

| Tabla | Tamaño con índices | Filas | Por fila | Al día (estimado) |
|---|---:|---:|---:|---:|
| `horario` (esquema) | 313 MB (626 MB mientras se cambia) | 1,8 M horas de paso | — | — |
| `posiciones` | 1,75 MB | 7.093 | ~250 B | 0,3-0,5 M filas → 75-125 MB; con 72 h, **< 400 MB** |
| `retrasos` | 0,26 MB | 927 | ~285 B | 51.000 filas → ~15 MB; **~5 GB al año** |

Con tablas tan pequeñas, el tamaño por fila es aproximado: la captura de 24 h de la parte B lo afina. En cualquier caso, el primer año cabe en los **32 GB** del B1ms con margen (~6-7 GB).

### Copia y restauración (probadas en local con la base de la medición)

| Paso | Resultado |
|---|---|
| `pg_dump` con las opciones de `copia.sh` (`-Fc --compress=9 --no-owner --no-privileges --exclude-table-data='public.posiciones*'`, cliente `postgres:18-alpine`) | **15 MB** (el horario es casi todo) |
| `pg_restore --exit-on-error` en un PostGIS 18-3.7 **nuevo y vacío**, sin la extensión creada y con otro usuario | **5,4 s**, sin errores. Crea PostGIS, las tablas, las particiones y los datos |
| Recuento original · restaurada | horas de paso 1.793.333 · 1.793.333; avisos 530 · 530; importaciones 2 · 2; Flyway versión 5 · 5; PostGIS 3.7.0 · 3.7.0; `posiciones` 11.296 · 0 (a propósito); `retrasos` 1.500 · 1.482 (la ingesta siguió escribiendo 18 filas después de la copia) |
| El compose de producción contra la base restaurada | Flyway valida las 5 migraciones («Schema is up to date»), arranca en 5 s y la ingesta sigue escribiendo |

Treinta copias de ~15 MB más lo que crezca `retrasos` caben en los 5 GB gratis de Blob Storage.

### El compose de producción, en local

`infra/produccion/compose.yaml` con la imagen nueva, un certificado de servidor autofirmado y una **CA falsa de *origin pull*** con su certificado de cliente (simulando a Cloudflare), contra la base restaurada:

| Prueba | Resultado |
|---|---|
| TLS sin certificado de cliente · con uno que no es de la CA | **Sin conexión** en los dos (curl 56). Repetido tras pasar a la CA propia por nombre de host (en `/etc/caddy/tls/clientes-ca.pem`): igual, y con el certificado de la CA el TLS pasa |
| Con el certificado de la CA | `200` en `/api/estado` |
| Cabeceras de `/api/nucleos` | HSTS, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`, CSP `default-src 'none'`; sin `Server` |
| `POST /api/horario/importaciones` · `/actuator/health` | **405** · **404** |
| Swagger UI (`/api/docs/ui` → `/api/docs/swagger-ui/index.html`) | 200 con su CSP propia; el JS se sirve. La primera versión del Caddyfile pisaba esta CSP con la general: corregido con dos matchers excluyentes |
| OpenAPI | `servers[0].url` = `https://trainwatch-api.ivanchesa.com` |
| **SSE a través de Caddy**, 25 s | `instantanea` al conectar, `cambios` a los 5, 12 y 19 s y `latido` a los 9 y 24 s: cada evento llega cuando sale, sin búfer. Cabeceras `no-cache, no-transform` y `X-Accel-Buffering: no` |
| Backend con el sistema de ficheros de **solo lectura** (y `/tmp` en memoria) | Arranca y funciona |

### Coste

Precios de la API pública (`prices.azure.com`, `spaincentral`, USD, consultados el 20-11-2026) y oferta gratuita de Azure for Students (páginas de Microsoft del mismo día). Detalle en el [ADR 0017](../adr/0017-maquina-pequena-y-postgresql-gestionado.md) y en [`docs/despliegue.md`](../despliegue.md).

| | Con la oferta gratuita | Sin ella |
|---|---:|---:|
| Máquina B2ats_v2 + disco P6 | 0 $ | 20,14 $ |
| PostgreSQL B1ms + 32 GB | 0 $ | 18,91 $ |
| IP pública Standard | 3,65 $ | 3,65 $ |
| Zona DNS privada | 0,50 $ | 0,50 $ |
| Blob, Key Vault, diagnóstico, salida | céntimos | céntimos |
| **Total al mes** | **~4,2 $** | **~43 $** |
| **Un año** | **~50 $ de los 100 $** | se acaba en ~2,3 meses |

**Salida de datos:** el directo manda ~3 KB/s por cliente conectado (fase 4): ~7,9 GB al mes por cada cliente que esté conectado *todo* el mes. Los 100 GB gratis dan para ~13 clientes permanentes; con visitas de unos minutos, sobra. La ingesta (Renfe) y las imágenes (GHCR) son tráfico de entrada: gratis.

## 4. Revisión independiente

PENDIENTE

## 5. Decisiones tomadas sin consultar

- **Arquitectura B** (máquina + PostgreSQL gestionado) y no A (todo en una máquina): con la oferta gratuita cuestan casi lo mismo (4,2 $ frente a 3,7 $), pero en 1 GiB no caben el backend y PostGIS (§3).
- **B2ats_v2**: de las tres máquinas gratuitas es la única disponible para esta suscripción en `spaincentral` (B1s da `NotAvailableForSubscription` y B2pts_v2 no existe allí), y tiene 2 vCPU. Se crea **sin zona**, porque la zona 1 no la admite.
- **PostgreSQL 18** también en Azure (versión estable allí, con PostGIS 3.6.4), para no tener dos versiones mayores entre local, pruebas y producción.
- **El presupuesto, fuera de Terraform** ([ADR 0019](../adr/0019-terraform-con-estado-remoto-en-azure.md)): que un `destroy` no lo borre.
- **«Full (strict)» con una regla de configuración solo para `trainwatch-api`**, creada a mano en la parte B: la zona está en «Full» y es compartida, y una zona solo admite un conjunto de reglas de configuración (si lo creara Terraform, se adueñaría del de toda la zona).
- **Cloudflare en Terraform** (DNS, certificado de origen, Authenticated Origin Pulls y el proyecto de Pages), además de Azure: el encargo lo deja abierto y así el montaje y el desmontaje son un solo `apply` y un solo `destroy`.
- **Authenticated Origin Pulls por nombre de host, con una CA propia** que crea Terraform (el encargo no lo pedía): con el grupo de seguridad solo se sabe que la conexión viene de *alguna* cuenta de Cloudflare. Primero se hizo con el ajuste de zona y el certificado compartido de Cloudflare; la sesión de control avisó de que la zona `ivanchesa.com` es compartida y no se pueden tocar ajustes de toda la zona, así que se cambió a uno por nombre de host, que además distingue nuestra zona de las de los demás.
- **Sin SSH**: ni regla en el grupo de seguridad ni servicio. Administración con `az vm run-command` y la consola serie.
- **Swagger UI sigue público** (la fase 4 lo dejó para esta fase): la API es pública y su documentación también. Caddy le da una CSP propia.
- **Caddy no deja pasar `POST`**: la importación manual del horario se hace desde la máquina (`curl -X POST 127.0.0.1:8080/...` con el token de Key Vault).
- **La vigilancia tolera la noche** (01:00-05:00): si Renfe no publica muestras sin trenes, el último dato envejece sin que nada falle. Se confirma con la captura de 24 h.
- **El trabajo de la CI ya no corre en push a `main`**: lo ejecuta `despliegue.yml` (con `workflow_call`), para no repetirlo.
- **Copias lógicas a las 03:15** de Madrid, con 30 días; las gestionadas, 14 días (gratis hasta 32 GB).
- **2 GB de swap** en la máquina, con `swappiness` 10.
- **Contenedor del backend con 640 MiB** y heap al 60 % (§3).
- **La web**: además de la página provisional que pedía el encargo, enseña el estado de los datos con el mismo umbral de 5 min que la vigilancia.

## 6. Parte B (pendiente)

Se hace con Iván delante, siguiendo [`docs/despliegue.md`](../despliegue.md). Lo que necesita de él:

1. **`az login`** con la cuenta de UNIR (la sesión instala antes la CLI de Azure con `winget`).
2. **Dos tokens de Cloudflare** con el mínimo de permisos: `trainwatch-terraform` (DNS, SSL y certificados de la zona, y Pages; caduca en 1 día) y `trainwatch-pages-ci` (solo Pages, para GitHub). Y el *Zone ID* y el *Account ID*.
3. **Su «sí»** a los dos `terraform apply` (estado y producción), después de ver el plan y el coste.
4. **Comprobar el modo SSL** de la zona `ivanchesa.com` (y, si no es «Full (strict)», una regla solo para `trainwatch-api`).
5. **Hacer público el paquete de GHCR** después del primer despliegue (un clic en GitHub).
6. **A las 24-48 h, mirar con la sesión el coste real** en Cost Management: la máquina y PostgreSQL deben salir a 0 $.

Pendiente de comprobar en la parte B, porque solo se ve con Azure y Cloudflare de verdad:
- que Authenticated Origin Pulls por nombre de host funciona en el plan Free con el certificado de cliente propio (si no, Cloudflare da 525 o 526 y se quita `client_auth` de Caddy hasta resolverlo);
- que `B2ats_v2` y `B1ms` estén disponibles en `spaincentral` con cuota, y la lista completa de regiones permitidas;
- que la identidad de GitHub pueda hacer `run-command` con el rol propio (si falta una acción, se añade al rol);
- que el `pg_dump` de la máquina llegue a Blob Storage por el punto de conexión de servicio;
- que PostgreSQL 18 con PostGIS 3.6.4 de Azure acepte las migraciones V1-V5 (en local se usa 3.7);
- la captura de 24 h pendiente desde la fase 3 (volúmenes, errores, la noche, Bilbao y Cádiz) y los **7 días seguidos** de la aceptación.

## 7. Qué cambiaría del plan

- **§4 (herramientas):** Caddy delante del backend y PostgreSQL gestionado (Azure Database for PostgreSQL Flexible Server) en producción, en vez de Compose con PostGIS en el servidor.
- **§5:** la API sale por `trainwatch-api.ivanchesa.com` con Cloudflare en «Full (strict)» y certificado de origen; la web publica una página provisional con el estado de los datos hasta la fase 5.
- **§6, fase 5:** la CSP de la web (`frontend/public/_headers`) solo deja conectar con la API; el mapa tendrá que añadir el proveedor de teselas (OpenFreeMap). Y el frontend lee la URL de la API de `VITE_API_URL` (`src/api/base.ts`).
- **§7 (riesgos):** «Coste del servidor» depende ahora de que la oferta gratuita de 12 meses se aplique a la suscripción; se comprueba en la parte B. Y aparece uno nuevo: **el ancho de banda del directo** (~3 KB/s por cliente, fase 4) consume los 100 GB gratis de salida con ~13 clientes conectados todo el mes; con visitas normales no se nota, pero hay que mirarlo si la web se difunde.
- **D2:** cuando se acabe el crédito, la migración es reescribir la parte de Azure del Terraform y restaurar el `pg_dump` (`docs/despliegue.md`).
