# ADR 0020 · Despliegue continuo: GHCR, OIDC con identidad administrada federada, `az vm run-command` y wrangler

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 7

## Contexto

Al fusionar en `main` hay que compilar, probar, publicar la imagen y desplegar la API y la web, **sin secretos de larga duración en el repo**. El directorio de UNIR **no deja registrar aplicaciones** en Entra ID (comprobado el 20-11), así que no se puede usar el OIDC habitual con una aplicación registrada. Y la máquina no tiene SSH abierto (ADR 0018).

## Decisión

1. **Un workflow, `despliegue.yml`, en cada push a `main`:**
   - `ci.yml` entero (pruebas del backend y del frontend, Terraform, ShellCheck, Caddy y compose), reutilizado con `workflow_call`;
   - la imagen del backend a **GHCR** con `GITHUB_TOKEN` (`packages: write` solo en ese trabajo), etiquetada con el commit y `latest`, con caché de capas de GitHub Actions;
   - la API y la web en paralelo, en el **entorno `produccion`** de GitHub, que solo admite la rama `main`.
2. **Entrada en Azure por OIDC con una identidad administrada asignada por el usuario** (`id-trainwatch-github`) y una **credencial federada** cuyo *subject* es `repo:IvanChesa/trainwatch:environment:produccion`. GitHub firma un token para ese trabajo; Azure lo cambia por uno suyo de una hora. En GitHub solo hay **variables** (identificadores, no secretos): `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_GRUPO` y `AZURE_MAQUINA`.
3. **Rol propio mínimo** sobre la máquina: leerla y `runCommand/action`. Nada de crear, borrar, leer secretos ni tocar la red.
4. **Despliegue con `az vm run-command invoke`:** el agente de Azure ejecuta un script como root en la máquina, sin SSH. El script lleva `infra/produccion/` dentro (tar.gz en base64, unos KB) y llama a `desplegar.sh <imagen por digest>`, que:
   - lee los secretos de **Key Vault** con la identidad de la máquina (`id-trainwatch-maquina`, rol `Key Vault Secrets User`) y los escribe en ficheros de root con permisos 600;
   - hace `docker compose pull` y `up -d` con la imagen **por digest** (no por etiqueta);
   - espera a `/actuator/health/readiness` hasta 3 min; si no llega, **vuelve a la imagen anterior** y falla;
   - instala el temporizador de las copias (ADR 0021).

   Como `run-command` termina «bien» aunque el script falle, la CI busca la línea `DESPLIEGUE_OK` en la salida. Después comprueba `/api/estado` desde fuera, a través de Cloudflare.
5. **La web, con `wrangler pages deploy`** desde Actions (`cloudflare/wrangler-action@v4`), después de las pruebas y con `VITE_API_URL` apuntando a la API. El proyecto de Pages lo crea Terraform como «subida directa». El token de Cloudflare tiene **un solo permiso** (Cloudflare Pages: Edit) y es un secreto del entorno `produccion`.
6. **Los secretos de producción solo están en Key Vault** (clave de PostgreSQL, token de administración, certificado de origen) y, el de Cloudflare Pages, en el entorno de GitHub. Nunca en el repo ni en la imagen.

## Alternativas

- **Aplicación registrada con OIDC** (lo habitual): imposible en el directorio de UNIR. La identidad administrada federada es equivalente y además es un recurso de la suscripción que Terraform crea y destruye.
- **Secreto de cliente o *publish profile* en GitHub:** un secreto de larga duración, justo lo que se quiere evitar.
- **SSH desde Actions:** hay que abrir el 22 a los rangos de GitHub (miles de IP) y guardar una clave privada como secreto.
- **Que la máquina se actualice sola** (Watchtower o un temporizador que mire GHCR): sin permisos en Azure para la CI, pero el despliegue deja de ser un paso de la CI con su resultado, y una imagen rota no se detecta ni se deshace.
- **Integración de Pages con GitHub** (Cloudflare compila al hacer push): sin token en GitHub, pero publica aunque las pruebas fallen, compila también cada rama y saca el build de la CI. Con wrangler, la web solo sale si `ci.yml` pasa.
- **Azure Container Apps o App Service** en vez de máquina: ver ADR 0017 (coste).

## Consecuencias

- Ejecutar un script en la máquina equivale a ser root en ella: por eso la credencial federada solo vale para el entorno `produccion`, que solo admite `main`, y `main` solo cambia con una fusión revisada.
- Antes de la parte B no hay variables: los trabajos de la API y la web se saltan y `main` sigue en verde.
- El paquete de GHCR hay que hacerlo **público** una vez (paso de la parte B), para que la máquina lo descargue sin credenciales.
- `run-command` admite un comando a la vez y devuelve como mucho 4 KB de salida: `desplegar.sh` escribe poco y termina con la marca.
