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
- **Claves sin estado:** la de PostgreSQL y el token de administración son **recursos efímeros** (`ephemeral "random_password"`) que se mandan como valores de solo escritura (`administrator_password_wo`, `value_wo`) a PostgreSQL y a Key Vault. No quedan en el estado ni en el plan. Para rotar la de PostgreSQL, se sube `version_clave_bd` en 1.
- **Lo que sí queda en el estado** (y por eso el estado está cerrado): la clave del certificado de origen (ADR 0018) y la clave SSH que Azure exige para crear la máquina (que no sirve: el 22 está cerrado y sshd apagado).
- **Etiquetas** `proyecto=trainwatch`, `entorno=produccion` y `gestion=terraform` en todo.
- **El presupuesto se queda fuera de Terraform.** `trainwatch-gasto-mensual` (10 $/mes, avisos al 50, 80 y 100 %) es de toda la suscripción y lo creó Iván a mano antes que nada. Importarlo haría que un `terraform destroy` lo borrara justo cuando más falta hace (al desmontar y volver a montar). Se documenta en `docs/despliegue.md`.
- **La CI** ejecuta `terraform fmt -check -recursive` y `terraform validate` en las dos raíces en cada push, sin credenciales (`init -backend=false`). El `plan` y el `apply` los hace Iván en su portátil con `az login` (parte B): la identidad de GitHub no tiene permisos para cambiar la infraestructura.

## Alternativas

- **Terraform Cloud (HCP):** estado gestionado y gratis hasta cierto tamaño, pero es otra cuenta y otro sitio donde viven los secretos del estado. Azure Storage queda en la misma suscripción y con el mismo control de acceso.
- **Bicep:** nativo de Azure y sin estado (lo guarda Azure Resource Manager), pero el plan pide Terraform, que sirve igual para Cloudflare y para migrar a otra nube (D2).
- **`terraform apply` desde la CI:** daría a la identidad de GitHub permisos de Owner sobre el grupo. Para un proyecto de una persona, el `apply` a mano con el plan delante es más seguro y suficiente.
- **Claves con `random_password` normal:** más sencillo, pero la clave queda en claro en el estado.

## Consecuencias

- Para aplicar hace falta `az login` y que la IP del portátil esté en `ips_administracion` (Key Vault y la cuenta de copias rechazan el resto).
- Rotar la clave de PostgreSQL es subir una variable y volver a desplegar; la máquina la lee de Key Vault en cada despliegue.
- Si un recurso con valor efímero se recrea solo (por ejemplo, el secreto de Key Vault), recibe una clave nueva que no coincide con la del servidor: hay que subir `version_clave_bd` para que vuelvan a coincidir (anotado en la guía).
- El estado de `estado/` es local; si se pierde, se reconstruye con `terraform import` de cuatro recursos.
