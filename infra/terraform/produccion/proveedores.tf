# El estado vive en la cuenta de almacenamiento que crea ../estado (ADR 0019). Los nombres van en backend.hcl
# (copia de backend.hcl.example): terraform init -backend-config=backend.hcl
terraform {
  backend "azurerm" {
    container_name   = "tfstate"
    key              = "produccion.tfstate"
    use_azuread_auth = true
  }
}

provider "azurerm" {
  features {
    key_vault {
      # Al destruir, el almacén se purga para que el nombre quede libre (no lleva protección de purga: ver
      # secretos.tf).
      purge_soft_delete_on_destroy = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
  subscription_id     = var.suscripcion
  storage_use_azuread = true
}

# El token va en la variable de entorno CLOUDFLARE_API_TOKEN, nunca en un fichero (docs/despliegue.md).
provider "cloudflare" {}
