# PostgreSQL Flexible Server (ADR 0017 y 0021): B1ms (1 vCore, 2 GiB) y 32 GB, lo que cubre la oferta gratuita de
# 12 meses. Sin dirección pública: solo se llega desde la VNet (subred delegada y zona DNS privada). Copias
# gestionadas con restauración a cualquier instante de los últimos 14 días.

# La clave se genera en cada ejecución sin guardarse en el estado (recurso efímero) y se manda a PostgreSQL y a Key
# Vault como valor de solo escritura. Solo se manda cuando cambia version_clave_bd: para rotarla, se sube en 1.
ephemeral "random_password" "bd" {
  length  = 40
  special = false
}

resource "azurerm_postgresql_flexible_server" "trainwatch" {
  name                = "psql-trainwatch-${local.sufijo}"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  version             = var.version_postgres
  sku_name            = var.sku_postgres
  storage_mb          = var.almacenamiento_postgres_mb
  storage_tier        = "P4"
  # Sin crecimiento automático: el coste no cambia solo. El disco se vigila (docs/despliegue.md, «Cada día»).
  auto_grow_enabled = false

  backup_retention_days        = var.retencion_copias_dias
  geo_redundant_backup_enabled = false

  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id
  public_network_access_enabled = false

  administrator_login               = "trainwatch"
  administrator_password_wo         = ephemeral.random_password.bd.result
  administrator_password_wo_version = var.version_clave_bd

  authentication {
    password_auth_enabled         = true
    active_directory_auth_enabled = false
  }

  # Mantenimiento de Azure los domingos a las 03:00 UTC, lejos de la importación del horario (04:30 y 06:30 de Madrid).
  maintenance_window {
    day_of_week  = 0
    start_hour   = 3
    start_minute = 0
  }

  tags = var.etiquetas

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]

  lifecycle {
    # Azure elige la zona al crearlo; no hay que recrearlo si la cambia.
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "trainwatch" {
  name      = "trainwatch"
  server_id = azurerm_postgresql_flexible_server.trainwatch.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# PostGIS tiene que estar en la lista de extensiones permitidas; la crea la migración V1 de Flyway (ADR 0004).
resource "azurerm_postgresql_flexible_server_configuration" "extensiones" {
  name      = "azure.extensions"
  server_id = azurerm_postgresql_flexible_server.trainwatch.id
  value     = "POSTGIS"
}

# Registro de las sentencias que pasan de 2 s, para encontrar consultas lentas en la API.
resource "azurerm_postgresql_flexible_server_configuration" "sentencias_lentas" {
  name      = "log_min_duration_statement"
  server_id = azurerm_postgresql_flexible_server.trainwatch.id
  value     = "2000"
}
