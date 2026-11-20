# Copias lógicas (ADR 0021): además de las copias gestionadas de PostgreSQL, un pg_dump cada noche a Blob Storage.
# Sirven para restaurar en otro sitio (en local, o en otra nube cuando se acabe el crédito). 14 días de retención
# (más 7 de borrado temporal): con retrasos creciendo, unos 21 ficheros caben en los 5 GB gratis (ADR 0021).

resource "azurerm_storage_account" "copias" {
  name                     = "sttwcopias${local.sufijo}"
  resource_group_name      = azurerm_resource_group.trainwatch.name
  location                 = azurerm_resource_group.trainwatch.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
  account_kind             = "StorageV2"
  access_tier              = "Hot"
  min_tls_version          = "TLS1_2"
  # Solo Entra ID (identidades administradas y az login): sin claves compartidas ni SAS, y nada público.
  shared_access_key_enabled       = false
  default_to_oauth_authentication = true
  allow_nested_items_to_be_public = false
  tags                            = var.etiquetas

  network_rules {
    default_action             = "Deny"
    bypass                     = ["AzureServices"]
    ip_rules                   = var.ips_administracion
    virtual_network_subnet_ids = [azurerm_subnet.maquina.id]
  }

  blob_properties {
    delete_retention_policy {
      days = 7
    }
  }
}

resource "azurerm_storage_container" "copias" {
  name                  = "copias"
  storage_account_id    = azurerm_storage_account.copias.id
  container_access_type = "private"
}

resource "azurerm_storage_management_policy" "copias" {
  storage_account_id = azurerm_storage_account.copias.id

  rule {
    name    = "borrar-a-los-14-dias"
    enabled = true
    filters {
      blob_types   = ["blockBlob"]
      prefix_match = ["copias/"]
    }
    actions {
      base_blob {
        delete_after_days_since_creation_greater_than = 14
      }
    }
  }
}

# Quien administra puede descargar las copias (para probar la restauración en local).
resource "azurerm_role_assignment" "administrador_copias" {
  scope                = azurerm_storage_container.copias.id
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = data.azurerm_client_config.actual.object_id
}
