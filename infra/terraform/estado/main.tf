# TrainWatch · estado remoto de Terraform (fase 7, ADR 0019).
# Se aplica UNA vez, a mano y con estado local, antes que infra/terraform/produccion: crea la cuenta de
# almacenamiento donde vive el estado de producción. Su propio estado (terraform.tfstate, fuera del repo) es
# pequeño y se puede reconstruir con «terraform import» si se pierde.

terraform {
  required_version = "~> 1.17.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.14.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9.1"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = var.suscripcion
  # Los contenedores y los blobs, con Entra ID: la cuenta no admite claves compartidas.
  storage_use_azuread = true
}

data "azurerm_client_config" "actual" {}

# El nombre de una cuenta de almacenamiento es global en Azure: un sufijo al azar evita choques.
resource "random_string" "sufijo" {
  length  = 6
  upper   = false
  special = false
}

resource "azurerm_resource_group" "estado" {
  name     = "rg-trainwatch-estado"
  location = var.region
  tags     = var.etiquetas
}

resource "azurerm_storage_account" "estado" {
  name                     = "sttwestado${random_string.sufijo.result}"
  resource_group_name      = azurerm_resource_group.estado.name
  location                 = azurerm_resource_group.estado.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
  account_kind             = "StorageV2"
  access_tier              = "Hot"
  min_tls_version          = "TLS1_2"
  # Sin claves compartidas ni SAS: solo Entra ID (az login), y nada público.
  shared_access_key_enabled       = false
  default_to_oauth_authentication = true
  allow_nested_items_to_be_public = false
  tags                            = var.etiquetas

  blob_properties {
    # Cada escritura del estado guarda la versión anterior: se puede volver atrás si un apply lo estropea.
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
  }
}

resource "azurerm_storage_container" "estado" {
  name                  = "tfstate"
  storage_account_id    = azurerm_storage_account.estado.id
  container_access_type = "private"
}

# Quien aplica Terraform (Iván, con az login) lee y escribe el estado con Entra ID.
resource "azurerm_role_assignment" "estado_administrador" {
  scope                = azurerm_storage_account.estado.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = data.azurerm_client_config.actual.object_id
}

# Para que no se borre por error: «terraform destroy» de producción no toca esto, y aquí hay que quitar el cerrojo
# a mano antes de destruir.
resource "azurerm_management_lock" "estado" {
  name       = "no-borrar-estado"
  scope      = azurerm_resource_group.estado.id
  lock_level = "CanNotDelete"
  notes      = "Estado de Terraform de TrainWatch"
}
