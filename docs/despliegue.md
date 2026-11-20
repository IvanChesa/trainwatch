# TrainWatch · guía de despliegue (fase 7, parte B)

Pasos exactos para montar TrainWatch en Azure y Cloudflare, en orden. **Iván** hace lo que necesita sus inicios de sesión y da el «sí» a cada gasto; **la sesión** (Claude) hace el resto. Las razones de cada pieza están en los ADR [0017](adr/0017-maquina-pequena-y-postgresql-gestionado.md) a [0021](adr/0021-copias-gestionadas-y-pg-dump-nocturno.md) y en el [informe de la fase 7](informes/fase-7.md).

```
Navegador ──HTTPS──► Cloudflare ──HTTPS (Full strict + certificado de cliente)──► Caddy :443 ─► backend :8080
  trainwatch.ivanchesa.com (Pages)        trainwatch-api.ivanchesa.com                   │ (máquina B2ats_v2)
                                                                                         └─► PostgreSQL Flexible B1ms
GitHub Actions ──OIDC──► Azure ──run-command──► desplegar.sh ──► Key Vault (secretos)        (red privada)
                                                                  Blob Storage (pg_dump nocturno)
```

## Coste mensual calculado

Precios de la API pública de Azure (`prices.azure.com`, `spaincentral`, en dólares, 20-11-2026) y la oferta gratuita de 12 meses de Azure for Students (detalle en el informe, §3).

| Recurso | Precio de lista | Con la oferta gratuita |
|---|---:|---:|
| Máquina B2ats_v2 (2 vCPU, 1 GiB), 730 h | 8,91 $ | **0 $** (750 h/mes) |
| Disco del sistema P6 (Premium SSD, 64 GB) | 11,23 $ | **0 $** (2 discos P6) |
| PostgreSQL Flexible Server B1ms (1 vCore, 2 GiB), 730 h | 14,53 $ | **0 $** (750 h/mes) |
| Disco de PostgreSQL, 32 GB | 4,38 $ | **0 $** (32 GB) |
| Copias gestionadas de PostgreSQL (≤ 32 GB) | 0 $ | 0 $ |
| IP pública estática Standard | **3,65 $** | **3,65 $** (no entra en la oferta) |
| Zona DNS privada de PostgreSQL | **0,50 $** | **0,50 $** |
| Blob Storage: estado de Terraform y copias lógicas (14 días; de ~0,3 GB hoy a unos pocos GB a final de año) | céntimos | 0 $ casi todo el año (5 GB) |
| Key Vault (unas decenas de operaciones al mes) | < 0,01 $ | 0 $ (10.000 operaciones) |
| Diagnóstico de arranque (almacenamiento gestionado, unos MB) | céntimos | céntimos |
| Tráfico de salida (< 100 GB/mes) | 0 $ | 0 $ |
| **Total** | **~43 $/mes** | **~4,2 $/mes (~50 $ al año)** |

- Con la oferta gratuita, el gasto cabe en el objetivo de **8 $ al mes** y los 100 $ duran el año.
- **Sin la oferta gratuita, no cabe:** ~43 $/mes acabarían el crédito en algo más de dos meses. Por eso, el paso 9 comprueba en Cost Management, a las 24-48 h, que la máquina y PostgreSQL salen a 0 $. Si no, se para todo y se decide con Iván (ver «Si la oferta gratuita no se aplica»).
- El presupuesto `trainwatch-gasto-mensual` (10 $/mes, avisos al 50, 80 y 100 %) ya existe y **no lo gestiona Terraform** ([ADR 0019](adr/0019-terraform-con-estado-remoto-en-azure.md)): sigue ahí aunque se destruya todo.
- Cloudflare (DNS, proxy, Pages) y GitHub (Actions en un repo público, GHCR público): 0 €.

## 0 · Herramientas (sesión)

Terraform 1.17 (`winget install Hashicorp.Terraform`) y la CLI de Azure ya están instalados; los dos son gratis. La CLI está en `C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin\az.cmd`: desde Git Bash falla por los espacios de la ruta, así que se usa desde PowerShell (o se añade esa carpeta al `PATH`).

## 1 · Azure: inicio de sesión (Iván)

1. **Iván:** `az login` con la cuenta de UNIR, y elegir la suscripción «Azure for Students».
2. **Sesión:** comprueba la suscripción, las regiones permitidas, los proveedores y la cuota:

```bash
az account show --query "{nombre:name, id:id, tenant:tenantId}" -o table
az policy assignment list --query "[?contains(displayName, 'region')].{nombre:displayName, regiones:parameters.listOfAllowedLocations.value}" -o json
for p in Microsoft.Compute Microsoft.Network Microsoft.DBforPostgreSQL Microsoft.KeyVault Microsoft.Storage Microsoft.ManagedIdentity; do az provider register --namespace $p; done
az vm list-skus -l spaincentral --size Standard_B2ats_v2 --query "[].{tamano:name, restricciones:restrictions}" -o json
az postgres flexible-server list-skus -l spaincentral --query "[].supportedServerEditions[?name=='Burstable'].supportedServerSkus[].name" -o tsv
az vm list-usage -l spaincentral --query "[?contains(name.value, 'BASv2') || name.value=='cores']" -o table
```

Comprobado en la parte A (20-11, solo lectura, con el `az login` de Iván):
- regiones permitidas: `italynorth`, `spaincentral`, `switzerlandnorth`, `francecentral` y `denmarkeast`;
- en `spaincentral`, **B2ats_v2 está disponible salvo en la zona 1** (la máquina se crea sin zona) y **B1s no** (`NotAvailableForSubscription`);
- PostgreSQL Flexible Server: versiones 11 a 18 y Burstable **B1ms**; el proveedor `Microsoft.DBforPostgreSQL` ya está registrado;
- **`Microsoft.Compute`, `Network`, `KeyVault`, `Storage` y `ManagedIdentity` no están registrados**: el `az provider register` de arriba hace falta (es gratis), y hasta entonces `az vm list-usage` no enseña la cuota;
- un `terraform plan` de solo lectura (estado local, sin registrar proveedores) da **52 recursos que crear** y ningún error.

Si algo de esto ha cambiado, se para y se decide con Iván (la otra región permitida más cercana es `italynorth`).

### Lo que pasó el 20-11 (parte B): MFA obligatorio

Azure exige **MFA** para crear o cambiar recursos, también en Azure for Students. Un `az login` normal reutiliza la sesión del navegador y puede dejar un token sin MFA: el `apply` falla con `RequestDisallowedByAzure ... without authenticating through MFA`. La CLI guarda un token por cada forma de pedirlo, y Terraform pide `https://management.azure.com` (sin barra final), así que hay que forzar el MFA para esa forma:

```powershell
az login --tenant <tenant> --scope https://management.azure.com/.default --claims-challenge eyJhY2Nlc3NfdG9rZW4iOnsiYWNycyI6eyJlc3NlbnRpYWwiOnRydWUsInZhbHVlcyI6WyJwMSJdfX19
```

(El reto en base64 es `{"access_token":{"acrs":{"essential":true,"values":["p1"]}}}`.) Para comprobarlo: el token de `az account get-access-token --resource https://management.azure.com` tiene que llevar `amr: mfa`.

## 2 · Cloudflare: tokens (Iván)

En el panel de Cloudflare, **My Profile → API Tokens → Create Token → Custom token**. Dos tokens:

| Token | Permisos | Recursos | Caducidad | Dónde va |
|---|---|---|---|---|
| `trainwatch-terraform` | Zone · DNS · Edit; Zone · SSL and Certificates · Edit (certificado de origen y Authenticated Origin Pulls por nombre de host); Account · Cloudflare Pages · Edit | Zona `ivanchesa.com`; la cuenta de Iván | **1 día** | Variable de entorno `CLOUDFLARE_API_TOKEN` en la terminal de la sesión. Nunca en un fichero |
| `trainwatch-pages-ci` | Account · Cloudflare Pages · Edit | La cuenta de Iván | Sin caducidad (se puede rotar) | Secreto `CLOUDFLARE_API_TOKEN` del entorno `produccion` de GitHub (paso 6) |

**Ojo con el permiso de Pages:** es de **cuenta** («Account»), no de zona. El 20-11 el token tenía 95 permisos de zona y ninguno de cuenta, y la creación del proyecto de Pages daba 403 (`Authentication error`) aunque el plan saliera bien: el plan no llama a la API de Pages, el `apply` sí. Y no hace falta un token con todos los permisos de zona: con los tres de la tabla basta.

El **Zone ID** de `ivanchesa.com` y el **Account ID** ya están en `infra/terraform/produccion/terraform.tfvars.example` (los leyó la sesión de control; no son secretos).

## 3 · Estado remoto de Terraform (sesión, con el «sí» de Iván)

Crea la cuenta de almacenamiento del estado (unos KB: 0 $ con la oferta, céntimos sin ella).

```bash
cd infra/terraform/estado
cp terraform.tfvars.example terraform.tfvars   # y poner el id de la suscripción
terraform init
terraform plan -out estado.tfplan              # se enseña a Iván
terraform apply estado.tfplan                  # solo con su «sí»
terraform output                               # nombres para backend.hcl
```

## 4 · Infraestructura (sesión, con el «sí» de Iván)

```bash
cd ../produccion
cp backend.hcl.example backend.hcl             # con la cuenta de almacenamiento del paso 3
cp terraform.tfvars.example terraform.tfvars   # suscripción e IP pública del portátil (Zone ID y Account ID ya vienen)
export CLOUDFLARE_API_TOKEN=...                # el token trainwatch-terraform (lo pega Iván)
terraform init "-backend-config=backend.hcl"   # entre comillas: PowerShell 5.1 parte el argumento por el punto
terraform plan -out produccion.tfplan
```

**Antes del `apply`:** la sesión enseña a Iván el resumen del plan (unos 40 recursos: grupo, red, grupo de seguridad, IP, máquina, PostgreSQL, Key Vault, identidades, cuenta de copias y Cloudflare) y el **coste mensual calculado** de arriba. **Solo con su «sí»:**

```bash
terraform apply produccion.tfplan              # 10-15 min (PostgreSQL tarda)
terraform output
```

Si el `apply` falla con un 403 de Key Vault (los permisos de Azure tardan en propagarse), se repite `terraform plan` y `apply`: Terraform sigue donde lo dejó.

## 5 · Cloudflare: «Full (strict)» solo para `trainwatch-api` (Iván, con la sesión)

**La zona `ivanchesa.com` es compartida:** tiene la web principal, `torno` y `tierra-luna-sol` (Pages), el correo (A, MX, SPF, DKIM y DMARC) y una verificación de Google. **Nadie cambia ajustes de toda la zona**, tampoco Terraform, que solo crea los registros `trainwatch` y `trainwatch-api`, el certificado de origen y Authenticated Origin Pulls **por nombre de host** (`trainwatch-api`).

Estado de la zona (leído por la sesión de control el 20-11): SSL **Full** (no strict), Always Use HTTPS activado, TLS mínimo 1.2, Authenticated Origin Pulls de zona desactivado y Bot Fight Mode desactivado. Así que:

1. **Iván:** **Rules → Configuration Rules → Create rule**: «Hostname equals `trainwatch-api.ivanchesa.com`» → **SSL: Full (strict)**. La zona sigue en «Full» para lo demás. Se hace a mano porque una zona solo admite un conjunto de reglas de configuración y Terraform se adueñaría de él.
2. **Sesión:** comprueba que Authenticated Origin Pulls de `trainwatch-api` está activo (`terraform state show cloudflare_authenticated_origin_pulls.api`) y, después del primer despliegue, que `https://trainwatch-api.ivanchesa.com/api/estado` responde (si fallan la regla o el certificado de cliente, Cloudflare da 525 o 526).
3. (Opcional) **Security → WAF → Rate limiting rules:** una regla gratuita solo para `trainwatch-api` (por ejemplo, 100 peticiones cada 10 s por IP), como propuso la fase 4. Es una regla por nombre de host; no cambia la zona.

## 6 · GitHub: entorno, variables y secreto (sesión, con `gh`)

```bash
# Entorno «produccion», que solo admite la rama main
gh api -X PUT repos/IvanChesa/trainwatch/environments/produccion \
  -F "deployment_branch_policy[protected_branches]=false" -F "deployment_branch_policy[custom_branch_policies]=true"
gh api -X POST repos/IvanChesa/trainwatch/environments/produccion/deployment-branch-policies -f name=main -f type=branch

# Variables del repositorio (identificadores, no secretos), con las salidas de Terraform
gh variable set AZURE_CLIENT_ID --body "$(terraform output -raw github_client_id)"
gh variable set AZURE_TENANT_ID --body "$(terraform output -raw tenant_id)"
gh variable set AZURE_SUBSCRIPTION_ID --body "$(terraform output -raw suscripcion)"
gh variable set AZURE_GRUPO --body "$(terraform output -raw grupo_de_recursos)"
gh variable set AZURE_MAQUINA --body "$(terraform output -raw maquina)"
gh variable set CLOUDFLARE_ACCOUNT_ID --body "b35ef6008684721a57bda8f8be2df30b"

# El token trainwatch-pages-ci, como secreto del entorno (lo pega Iván en el aviso de gh)
gh secret set CLOUDFLARE_API_TOKEN --env produccion
```

## 7 · Primer despliegue, por la CI (sesión de control e Iván)

1. **La sesión de control** fusiona la fase 7 y sube `main`. El workflow **Despliegue** pasa las pruebas, publica la imagen en GHCR y despliega la web.
2. **El trabajo «API (Azure)» fallará la primera vez:** el paquete de GHCR nace privado y la máquina no lo puede descargar. **Iván:** en GitHub, **Packages → trainwatch-backend → Package settings → Change visibility → Public** (el código ya es público).
3. **Sesión:** `gh run rerun --failed` en esa ejecución. El despliegue termina con `DESPLIEGUE_OK` y comprueba `/api/estado` desde fuera.

## 8 · Comprobaciones (sesión)

```bash
# Importación del horario y estado de la ingesta, desde dentro (Actuator no es público)
az vm run-command invoke -g rg-trainwatch -n vm-trainwatch --command-id RunShellScript \
  --scripts "bash /opt/trainwatch/revisar.sh" --query "value[0].message" -o tsv

# Desde fuera, a través de Cloudflare
curl -s https://trainwatch-api.ivanchesa.com/api/directo/estado | head -c 400
curl -sI https://trainwatch-api.ivanchesa.com/api/nucleos          # cabeceras de seguridad, cf-ray
curl -sN --max-time 40 "https://trainwatch-api.ivanchesa.com/api/directo?nucleo=51" | grep -E "^event:"   # instantánea, cambios cada ~7 s y latido
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://trainwatch-api.ivanchesa.com/api/horario/importaciones   # 405
curl -s -o /dev/null -w "%{http_code}\n" https://trainwatch-api.ivanchesa.com/actuator/health                     # 404
curl -sk --max-time 10 https://<ip_publica>/ ; echo $?                                                           # sin respuesta (grupo de seguridad)
```

- **La web:** `https://trainwatch.ivanchesa.com` enseña la página provisional, el aviso de «no oficial», el estado de los datos y «Datos: Renfe (CC BY 4.0)».
- **La alerta:** `gh variable set VIGILANCIA_ACTIVA --body true`; `gh workflow run vigilancia.yml` → verde. Después se para la ingesta un momento y se vuelve a lanzar → **rojo**, y le llega el correo a Iván. Se vuelve a arrancar:

```bash
az vm run-command invoke -g rg-trainwatch -n vm-trainwatch --command-id RunShellScript \
  --scripts "cd /opt/trainwatch && docker compose stop backend"     # parar
gh workflow run vigilancia.yml                                       # debe fallar
az vm run-command invoke -g rg-trainwatch -n vm-trainwatch --command-id RunShellScript \
  --scripts "cd /opt/trainwatch && docker compose start backend"    # volver a arrancar
```

- **La copia:** a la mañana siguiente, `revisar.sh` enseña la última copia; también `az storage blob list --account-name <cuenta_copias> -c copias --auth-mode login -o table`.

## 9 · Coste real (sesión, a las 24-48 h)

En el portal, **Cost Management → Cost analysis**, agrupado por medidor, o:

```bash
az consumption usage list --start-date <ayer> --end-date <hoy> --query "[].{medidor:meterDetails.meterName, coste:pretaxCost}" -o table
```

La máquina, sus discos y PostgreSQL deben salir a 0 (medidores «Free»). Solo deben costar la IP y la zona DNS privada.

### Si la oferta gratuita no se aplica

Se para todo (`terraform destroy`, abajo) y se decide con Iván. Opciones, de más barata a más cara:
1. **Cloudflare Tunnel** en vez de IP pública (−3,65 $) y PostgreSQL en la misma máquina, con una máquina de 2 GiB (`B1ms`, 16,7 $/mes): ~19 $/mes, no cabe;
2. **Oracle Cloud Always Free** (D2), con sus máquinas Arm gratuitas (condiciones por comprobar entonces): Terraform y la copia `pg_dump` se llevan allí;
3. **Seguir en Azure** solo los meses que dure el crédito.

## Cada día (Iván o la sesión de control)

`revisar.sh` (arriba) enseña lo que hay que mirar:
- **salud** y componentes (`db`, disco);
- **directo:** antigüedad del último dato (< 60 s de día), trenes, núcleos sin datos en directo, estado de los circuitos;
- **ingesta** (fases 3 y 3b): `trainwatch.directo.errores` no debe crecer; `.descargas`, `.filas` y `.trenes`; en `/api/directo/estado`, `pendientes` a 0 y `guardadoAlDia` reciente (si no, la base de datos no está guardando);
- **SSE** (fase 4): `trainwatch.api.directo.conexiones`, `.rechazadas` (debe ser 0), `.desconectados`, `.reconexiones`;
- **máquina:** memoria, swap, disco y errores del backend en 24 h;
- **base de datos:** tamaño, retrasos y posiciones de ayer, para la captura de 24 h;
- **la última copia.**

Además, la vigilancia de GitHub Actions avisa por correo si la API cae, si los datos tienen más de 5 minutos o si hace más de 5 minutos que no se guarda todo en la base de datos. Si una actualización de seguridad pide reiniciar (el núcleo), la máquina se reinicia sola a las 02:00 UTC: puede salir un aviso suelto a esa hora. GitHub desactiva los workflows programados de un repo público tras **60 días sin actividad**: si el repo se queda quieto tanto tiempo, hay que volver a activarlo.

## Restaurar una copia

**En local** (probado en la fase 7, informe §3):

```bash
# 1. Descargar la copia (con az login y la IP del portátil en ips_administracion)
az storage blob download --account-name <cuenta_copias> -c copias -n trainwatch-AAAAMMDDTHHMMSSZ.dump -f copia.dump --auth-mode login

# 2. Una base de datos vacía con PostGIS (la de infra/docker-compose.yml, u otra)
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker exec -i trainwatch-postgis-1 psql -U trainwatch -d postgres -c "DROP DATABASE IF EXISTS restaurada" -c "CREATE DATABASE restaurada"

# 3. Restaurar (crea PostGIS, las tablas, las particiones y los datos; posiciones queda vacía)
docker exec -i trainwatch-postgis-1 pg_restore -U trainwatch -d restaurada --no-owner --no-privileges --exit-on-error < copia.dump

# 4. Comprobar
docker exec -i trainwatch-postgis-1 psql -U trainwatch -d restaurada -c "select count(*), min(fecha_servicio), max(fecha_servicio) from retrasos"
```

Después, el backend arranca contra esa base (`TRAINWATCH_DB_NAME=restaurada`): Flyway ve las migraciones ya aplicadas y la ingesta sigue escribiendo.

**En Azure** (el error de hoy): **PostgreSQL → Overview → Restore**, a un servidor nuevo en el instante de antes del error (hasta 14 días atrás). Después se cambia `HOST_POSTGRES` en `/etc/trainwatch/maquina.env` (o se importa el servidor nuevo en Terraform) y se vuelve a desplegar.

## Desmontarlo todo

```bash
# 0. Una última copia, descargada (arriba)
cd infra/terraform/produccion
export CLOUDFLARE_API_TOKEN=...            # un token como trainwatch-terraform
terraform plan -destroy -out destruir.tfplan   # se enseña a Iván
terraform apply destruir.tfplan                # borra la máquina, PostgreSQL (y sus copias gestionadas), Key Vault,
                                               # las copias lógicas, los DNS de Cloudflare, el certificado de origen y Pages
# El estado remoto (solo si no se va a volver a montar): quitar el cerrojo y destruir
az lock delete --name no-borrar-estado --resource-group rg-trainwatch-estado
cd ../estado && terraform destroy
```

Quedan: el presupuesto `trainwatch-gasto-mensual`, el entorno y las variables de GitHub, el paquete de GHCR y la regla de configuración de `trainwatch-api` (se borra a mano en **Rules → Configuration Rules**).

## Cuando se acabe el crédito (20-11-2027)

- La infraestructura está en Terraform y los datos en `pg_dump`: en otra nube se reescribe la parte de Azure del Terraform (máquina, red, base de datos), se restaura la última copia y se cambia el registro DNS de `trainwatch-api`. Cloudflare, GitHub Actions (salvo el paso de Azure) y la imagen no cambian.
- Candidata: **Oracle Cloud Always Free** (D2). El backend y PostgreSQL con PostGIS caben en la misma máquina Arm; la imagen tendría que construirse también para `arm64` (`docker buildx --platform linux/amd64,linux/arm64`).
