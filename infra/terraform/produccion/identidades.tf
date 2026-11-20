# Identidades administradas (ADR 0020). El directorio de UNIR no deja registrar aplicaciones, así que GitHub Actions
# entra con una identidad administrada asignada por el usuario y una credencial federada: Azure se fía del token OIDC
# que GitHub firma para este repositorio y este entorno, y no hay ningún secreto que guardar ni que caduque.

# 1 · La de la máquina: lee los secretos de Key Vault y sube las copias a la cuenta de almacenamiento.
resource "azurerm_user_assigned_identity" "maquina" {
  name                = "id-trainwatch-maquina"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  tags                = var.etiquetas
}

resource "azurerm_role_assignment" "maquina_secretos" {
  scope                = azurerm_key_vault.trainwatch.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.maquina.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "maquina_copias" {
  scope                = azurerm_storage_container.copias.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.maquina.principal_id
  principal_type       = "ServicePrincipal"
}

# 2 · La de GitHub Actions: solo puede ejecutar el script de despliegue en la máquina.
resource "azurerm_user_assigned_identity" "github" {
  name                = "id-trainwatch-github"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  tags                = var.etiquetas
}

resource "azurerm_federated_identity_credential" "github" {
  name                      = "github-${var.entorno_github}"
  user_assigned_identity_id = azurerm_user_assigned_identity.github.id
  issuer                    = "https://token.actions.githubusercontent.com"
  audience                  = ["api://AzureADTokenExchange"]
  # Solo los trabajos del entorno «produccion» de este repositorio (y el entorno solo admite la rama main).
  subject = "repo:${var.repositorio_github}:environment:${var.entorno_github}"
}

# Rol propio con lo justo para «az vm run-command invoke» en esta máquina: ni crear, ni borrar, ni leer secretos.
# Ejecutar un script en la máquina equivale a ser root en ella: por eso la credencial solo vale para el entorno
# protegido de GitHub (ADR 0020).
resource "azurerm_role_definition" "despliegue" {
  name        = "TrainWatch despliegue (${local.sufijo})"
  scope       = azurerm_resource_group.trainwatch.id
  description = "GitHub Actions: ejecutar el script de despliegue en la máquina de TrainWatch"

  permissions {
    actions = [
      "Microsoft.Compute/virtualMachines/read",
      "Microsoft.Compute/virtualMachines/instanceView/read",
      "Microsoft.Compute/virtualMachines/runCommand/action",
      "Microsoft.Compute/locations/operations/read",
    ]
  }

  assignable_scopes = [azurerm_resource_group.trainwatch.id]
}

resource "azurerm_role_assignment" "github_despliegue" {
  scope              = azurerm_linux_virtual_machine.trainwatch.id
  role_definition_id = azurerm_role_definition.despliegue.role_definition_resource_id
  principal_id       = azurerm_user_assigned_identity.github.principal_id
  principal_type     = "ServicePrincipal"
}
