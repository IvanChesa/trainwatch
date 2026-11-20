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
| Blob Storage: estado de Terraform y copias (< 2 GB) | ~0,04 $ | 0 $ (5 GB) |
| Key Vault (unas decenas de operaciones al mes) | < 0,01 $ | 0 $ (10.000 operaciones) |
| Diagnóstico de arranque (almacenamiento gestionado, unos MB) | céntimos | céntimos |
| Tráfico de salida (< 100 GB/mes) | 0 $ | 0 $ |
| **Total** | **~43 $/mes** | **~4,2 $/mes (~50 $ al año)** |

- Con la oferta gratuita, el gasto cabe en el objetivo de **8 $ al mes** y los 100 $ duran el año.
- **Sin la oferta gratuita, no cabe:** ~43 $/mes acabarían el crédito en algo más de dos meses. Por eso, el paso 9 comprueba en Cost Management, a las 24-48 h, que la máquina y PostgreSQL salen a 0 $. Si no, se para todo y se decide con Iván (ver «Si la oferta gratuita no se aplica»).
- El presupuesto `trainwatch-gasto-mensual` (10 $/mes, avisos al 50, 80 y 100 %) ya existe y **no lo gestiona Terraform** ([ADR 0019](adr/0019-terraform-con-estado-remoto-en-azure.md)): sigue ahí aunque se destruya todo.
- Cloudflare (DNS, proxy, Pages) y GitHub (Actions en un repo público, GHCR público): 0 €.

## 0 · Herramientas (sesión)

Terraform 1.17 ya está instalado (`winget install Hashicorp.Terraform`). Falta la CLI de Azure, que es gratis:

```bash
winget install --id Microsoft.AzureCLI -e
```

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

Si `B2ats_v2` o `B1ms` no están en `spaincentral`, se para y se decide con Iván (la otra región permitida más cercana es `italynorth`).

## 2 · Cloudflare: tokens (Iván)

En el panel de Cloudflare, **My Profile → API Tokens → Create Token → Custom token**. Dos tokens:

| Token | Permisos | Recursos | Caducidad | Dónde va |
|---|---|---|---|---|
| `trainwatch-terraform` | Zone · DNS · Edit; Zone · SSL and Certificates · Edit; Account · Cloudflare Pages · Edit | Zona `ivanchesa.com`; la cuenta de Iván | **1 día** | Variable de entorno `CLOUDFLARE_API_TOKEN` en la terminal de la sesión. Nunca en un fichero |
| `trainwatch-pages-ci` | Account · Cloudflare Pages · Edit | La cuenta de Iván | Sin caducidad (se puede rotar) | Secreto `CLOUDFLARE_API_TOKEN` del entorno `produccion` de GitHub (paso 6) |

Iván copia también, de la página de la zona `ivanchesa.com` (columna derecha, «API»), el **Zone ID** y el **Account ID**. No son secretos.

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
cp terraform.tfvars.example terraform.tfvars   # suscripción, IP pública del portátil, Zone ID y Account ID
export CLOUDFLARE_API_TOKEN=...                # el token trainwatch-terraform (lo pega Iván)
terraform init -backend-config=backend.hcl
terraform plan -out produccion.tfplan
```

**Antes del `apply`:** la sesión enseña a Iván el resumen del plan (unos 40 recursos: grupo, red, grupo de seguridad, IP, máquina, PostgreSQL, Key Vault, identidades, cuenta de copias y Cloudflare) y el **coste mensual calculado** de arriba. **Solo con su «sí»:**

```bash
terraform apply produccion.tfplan              # 10-15 min (PostgreSQL tarda)
terraform output
```

Si el `apply` falla con un 403 de Key Vault (los permisos de Azure tardan en propagarse), se repite `terraform plan` y `apply`: Terraform sigue donde lo dejó.

## 5 · Cloudflare: modo SSL y comprobaciones (Iván, con la sesión)

Terraform ya ha creado el DNS de `trainwatch-api` (con proxy), el de `trainwatch` (Pages), el certificado de origen y Authenticated Origin Pulls. Falta el **modo SSL**, que es de toda la zona `ivanchesa.com`:

1. **SSL/TLS → Overview.** Si ya está en **Full (strict)**, no se toca. Si no, en vez de cambiar toda la zona: **Rules → Configuration Rules → Create rule**, «Hostname equals `trainwatch-api.ivanchesa.com`» → **SSL: Full (strict)**.
2. **SSL/TLS → Edge Certificates:** «Always Use HTTPS» activado.
3. **Security → Bots:** si «Bot Fight Mode» está activado, la vigilancia de GitHub Actions (un `curl`) puede recibir un desafío. Se comprueba en el paso 8 y, si pasa, se desactiva para `trainwatch-api` o se añade una regla de omisión.
4. (Opcional) **Security → WAF → Rate limiting rules:** una regla gratuita para `trainwatch-api` (por ejemplo, 100 peticiones cada 10 s por IP), como propuso la fase 4.

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
gh variable set CLOUDFLARE_ACCOUNT_ID --body "<Account ID>"

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
- **ingesta** (fases 3 y 3b): `trainwatch.directo.errores` no debe crecer; `.descargas`, `.filas` y `.trenes`;
- **SSE** (fase 4): `trainwatch.api.directo.conexiones`, `.rechazadas` (debe ser 0), `.desconectados`, `.reconexiones`;
- **máquina:** memoria, swap, disco y errores del backend en 24 h;
- **base de datos:** tamaño, retrasos y posiciones de ayer, para la captura de 24 h;
- **la última copia.**

Además, la vigilancia de GitHub Actions avisa por correo si la API cae o los datos tienen más de 5 minutos. GitHub desactiva los workflows programados de un repo público tras **60 días sin actividad**: si el repo se queda quieto tanto tiempo, hay que volver a activarlo.

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

Quedan: el presupuesto `trainwatch-gasto-mensual`, el entorno y las variables de GitHub, el paquete de GHCR y Authenticated Origin Pulls en la zona (inofensivo para las demás webs; se desactiva en **SSL/TLS → Origin Server**).

## Cuando se acabe el crédito (20-11-2027)

- La infraestructura está en Terraform y los datos en `pg_dump`: en otra nube se reescribe la parte de Azure del Terraform (máquina, red, base de datos), se restaura la última copia y se cambia el registro DNS de `trainwatch-api`. Cloudflare, GitHub Actions (salvo el paso de Azure) y la imagen no cambian.
- Candidata: **Oracle Cloud Always Free** (D2). El backend y PostgreSQL con PostGIS caben en la misma máquina Arm; la imagen tendría que construirse también para `arm64` (`docker buildx --platform linux/amd64,linux/arm64`).
