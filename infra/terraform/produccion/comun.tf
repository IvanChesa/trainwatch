data "azurerm_client_config" "actual" {}

# Nombres globales (Key Vault, almacenamiento, PostgreSQL): un sufijo al azar evita choques.
resource "random_string" "sufijo" {
  length  = 6
  upper   = false
  special = false
}

resource "azurerm_resource_group" "trainwatch" {
  name     = "rg-trainwatch"
  location = var.region
  tags     = var.etiquetas
}

locals {
  sufijo   = random_string.sufijo.result
  host_api = "${var.subdominio_api}.${var.dominio}"
  host_web = "${var.subdominio_web}.${var.dominio}"
}
