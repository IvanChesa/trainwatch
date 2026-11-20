# La máquina virtual (ADR 0017): Ubuntu 24.04 con Docker; el backend y Caddy en Docker Compose
# (infra/produccion). cloud-init solo prepara la máquina; el despliegue lo hace GitHub Actions con
# «az vm run-command» (ADR 0020).

# Azure exige una clave SSH para crear la máquina. El puerto 22 está cerrado en el grupo de seguridad y cloud-init
# apaga sshd: la clave no se usa y no sale en ninguna salida.
resource "tls_private_key" "maquina" {
  algorithm = "ED25519"
}

resource "azurerm_linux_virtual_machine" "trainwatch" {
  name                  = "vm-trainwatch"
  computer_name         = "trainwatch"
  resource_group_name   = azurerm_resource_group.trainwatch.name
  location              = azurerm_resource_group.trainwatch.location
  size                  = var.tamano_maquina
  admin_username        = "trainwatch"
  network_interface_ids = [azurerm_network_interface.maquina.id]
  tags                  = var.etiquetas

  disable_password_authentication = true
  admin_ssh_key {
    username   = "trainwatch"
    public_key = tls_private_key.maquina.public_key_openssh
  }

  # P6 (Premium SSD de 64 GB): la oferta gratuita incluye dos al mes durante 12 meses.
  os_disk {
    name                 = "osdisk-trainwatch"
    caching              = "ReadWrite"
    storage_account_type = "Premium_LRS"
    disk_size_gb         = 64
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.maquina.id]
  }

  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml", {
    key_vault       = azurerm_key_vault.trainwatch.name
    identidad       = azurerm_user_assigned_identity.maquina.client_id
    cuenta_copias   = azurerm_storage_account.copias.name
    contenedor      = azurerm_storage_container.copias.name
    host_postgres   = azurerm_postgresql_flexible_server.trainwatch.fqdn
    usuario_bd      = azurerm_postgresql_flexible_server.trainwatch.administrator_login
    base_de_datos   = azurerm_postgresql_flexible_server_database.trainwatch.name
    host_api        = local.host_api
    version_cliente = var.version_postgres
  }))

  # Diagnóstico de arranque con almacenamiento gestionado: habilita la consola serie del portal (sin SSH).
  boot_diagnostics {}

  secure_boot_enabled = true
  vtpm_enabled        = true

  depends_on = [
    # La máquina lee los secretos al arrancar: tienen que existir, y su permiso también.
    azurerm_role_assignment.maquina_secretos,
    azurerm_key_vault_secret.clave_bd,
    azurerm_key_vault_secret.token_admin,
    azurerm_key_vault_secret.certificado_origen,
    azurerm_key_vault_secret.clave_origen,
  ]

  lifecycle {
    # Una imagen nueva de Ubuntu no debe recrear la máquina: se actualiza con unattended-upgrades.
    ignore_changes = [source_image_reference[0].version]
  }
}
