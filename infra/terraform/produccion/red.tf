# Red (ADR 0017 y 0018): una VNet con dos subredes, la de la máquina y la delegada a PostgreSQL. PostgreSQL no tiene
# dirección pública; la máquina solo admite el 443 desde Cloudflare y no tiene SSH abierto.

resource "azurerm_virtual_network" "trainwatch" {
  name                = "vnet-trainwatch"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  address_space       = ["10.40.0.0/16"]
  tags                = var.etiquetas
}

resource "azurerm_subnet" "maquina" {
  name                 = "snet-maquina"
  resource_group_name  = azurerm_resource_group.trainwatch.name
  virtual_network_name = azurerm_virtual_network.trainwatch.name
  address_prefixes     = ["10.40.1.0/24"]
  # La salida a Internet va por la IP pública de la máquina, no por el acceso de salida por defecto (retirado).
  default_outbound_access_enabled = false

  # Puntos de conexión de servicio (gratis): Key Vault y la cuenta de copias solo admiten tráfico de esta subred
  # y de las IP de administración.
  service_endpoint {
    service = "Microsoft.KeyVault"
  }
  service_endpoint {
    service = "Microsoft.Storage"
  }
}

resource "azurerm_subnet" "postgres" {
  name                            = "snet-postgres"
  resource_group_name             = azurerm_resource_group.trainwatch.name
  virtual_network_name            = azurerm_virtual_network.trainwatch.name
  address_prefixes                = ["10.40.2.0/24"]
  default_outbound_access_enabled = false

  delegation {
    name = "postgres"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# Rangos de Cloudflare, leídos de su API en cada plan: si Cloudflare añade uno, el siguiente apply lo abre.
data "cloudflare_ip_ranges" "cloudflare" {}

resource "azurerm_network_security_group" "maquina" {
  name                = "nsg-trainwatch-maquina"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  tags                = var.etiquetas

  security_rule {
    name                       = "https-desde-cloudflare"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefixes    = data.cloudflare_ip_ranges.cloudflare.ipv4_cidrs
    destination_address_prefix = "*"
  }

  # Sin SSH ni nada más: la administración va por «az vm run-command» (el agente de la máquina, sin puertos) y la
  # consola serie del portal. Las reglas por defecto admiten el tráfico de la VNet y del balanceador; esta las corta.
  security_rule {
    name                       = "denegar-el-resto"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "maquina" {
  subnet_id                 = azurerm_subnet.maquina.id
  network_security_group_id = azurerm_network_security_group.maquina.id
}

resource "azurerm_public_ip" "maquina" {
  name                = "pip-trainwatch"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = var.etiquetas
}

resource "azurerm_network_interface" "maquina" {
  name                = "nic-trainwatch"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  tags                = var.etiquetas

  ip_configuration {
    name                          = "principal"
    subnet_id                     = azurerm_subnet.maquina.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.maquina.id
  }
}

# Zona DNS privada de PostgreSQL: el nombre del servidor se resuelve a su IP privada dentro de la VNet.
resource "azurerm_private_dns_zone" "postgres" {
  name                = "trainwatch.private.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.trainwatch.name
  tags                = var.etiquetas
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                 = "vnet-trainwatch"
  private_dns_zone_id  = azurerm_private_dns_zone.postgres.id
  virtual_network_id   = azurerm_virtual_network.trainwatch.id
  registration_enabled = false
  tags                 = var.etiquetas
}
