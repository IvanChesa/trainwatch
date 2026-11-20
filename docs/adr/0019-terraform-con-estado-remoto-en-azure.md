# ADR 0019 · Terraform con estado remoto en Azure Storage, versiones fijadas y secretos que no pasan por el estado

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 7

## Contexto

La infraestructura tiene que estar escrita como código, poder repetirse y destruirse, y defenderse en una entrevista de Cloud. Terraform necesita guardar su estado en algún sitio: en el portátil se pierde y no se puede compartir con la CI ni con otra persona.

## Decisión

- **Terraform 1.17** con `azurerm` 5.14, `cloudflare` 5.29, `random` 3.9, `tls` 4.4 y `time` 0.14, fijados con `~>` en el parche y con `.terraform.lock.hcl` en el repo (sumas de los binarios).
- **Dos raíces:**
  - `infra/terraform/estado`: el grupo `rg-trainwatch-estado` y una cuenta de almacenamiento con el contenedor `tfstate`. Se aplica una vez, con estado local. Lleva un cerrojo `CanNotDelete`;
  - `infra/terraform/produccion`: todo lo demás, con `backend "azurerm"` en esa cuenta (`use_azuread_auth`). El nombre de la cuenta va en `backend.hcl`, fuera del repo.
- **La cuenta del estado** no admite claves compartidas ni SAS (`shared_access_key_enabled = false`): solo Entra ID, con el rol `Storage Blob Data Contributor` para quien aplica. Lleva **versionado de blobs** y 30 días de recuperación: si un `apply` estropea el estado, se vuelve a la versión anterior. El bloqueo entre dos `apply` a la vez lo da el *lease* del blob.
- **Valores de solo escritura:** la clave de PostgreSQL y el token de administración llegan a PostgreSQL y a Key Vault como valores de solo escritura (`administrator_password_wo`, `value_wo`): no salen en el plan ni en las propiedades de los recursos.
  - El **token de administración** es un recurso **efímero** (`ephemeral "random_password"`): no queda ni en el estado. Solo lo recibe Key Vault.
  - La **clave de PostgreSQL** es un `random_password` normal, que sí queda en el estado. Primero también era efímera, pero la revisión independiente vio el fallo: un efímero cambia en cada ejecución y, si un `apply` falla a medias (el secreto creado y el servidor no), el reintento deja claves distintas en el servidor y en Key Vault. Para rotarla: `terraform apply -replace=random_password.bd` y subir `version_clave_bd` en 1.
- **Lo que queda en el estado** (y por eso el estado está cerrado): la clave de PostgreSQL, las del certificado de origen y de la CA y el certificado de cliente de Authenticated Origin Pulls (ADR 0018) y la clave SSH que Azure exige para crear la máquina (que no sirve: el 22 está cerrado y sshd apagado). **Quien lea el estado puede fabricar certificados de cliente válidos para el origen**: el estado solo lo lee quien tenga el rol de datos de la cuenta (Iván).
- **Etiquetas** `proyecto=trainwatch`, `entorno=produccion` y `gestion=terraform` en todo.
- **El presupuesto se queda fuera de Terraform.** `trainwatch-gasto-mensual` (10 $/mes, avisos al 50, 80 y 100 %) es de toda la suscripción y lo creó Iván a mano antes que nada. Importarlo haría que un `terraform destroy` lo borrara justo cuando más falta hace (al desmontar y volver a montar). Se documenta en `docs/despliegue.md`.
- **La CI** ejecuta `terraform fmt -check -recursive` y `terraform validate` en las dos raíces en cada push, sin credenciales (`init -backend=false`). El `plan` y el `apply` los hace Iván en su portátil con `az login` (parte B): la identidad de GitHub no tiene permisos para cambiar la infraestructura.

## Alternativas

- **Terraform Cloud (HCP):** estado gestionado y gratis hasta cierto tamaño, pero es otra cuenta y otro sitio donde viven los secretos del estado. Azure Storage queda en la misma suscripción y con el mismo control de acceso.
- **Bicep:** nativo de Azure y sin estado (lo guarda Azure Resource Manager), pero el plan pide Terraform, que sirve igual para Cloudflare y para migrar a otra nube (D2).
- **`terraform apply` desde la CI:** daría a la identidad de GitHub permisos de Owner sobre el grupo. Para un proyecto de una persona, el `apply` a mano con el plan delante es más seguro y suficiente.
- **También la clave de PostgreSQL efímera** (la primera versión): no quedaba en el estado, pero un `apply` que falla a medias deja claves distintas en el servidor y en Key Vault. Entre una clave en un estado cerrado y un despliegue que se rompe solo, mejor lo primero.

## Consecuencias

- Para aplicar hace falta `az login` y que la IP del portátil esté en `ips_administracion` (Key Vault y la cuenta de copias rechazan el resto).
- Rotar la clave de PostgreSQL es un `apply` con `-replace` y una variable, y volver a desplegar: la máquina la lee de Key Vault en cada despliegue.
- El estado de `estado/` es local; si se pierde, se reconstruye con `terraform import` de cuatro recursos.
