# Key Vault (ADR 0020): los secretos de producción viven aquí y en ningún otro sitio. La máquina los lee al
# desplegar con su identidad administrada; GitHub Actions no los ve.

resource "azurerm_key_vault" "trainwatch" {
  name                = "kv-trainwatch-${local.sufijo}"
  resource_group_name = azurerm_resource_group.trainwatch.name
  location            = azurerm_resource_group.trainwatch.location
  tenant_id           = data.azurerm_client_config.actual.tenant_id
  sku_name            = "standard"
  # Permisos con roles de Azure (RBAC), no con directivas de acceso.
  rbac_authorization_enabled = true
  soft_delete_retention_days = 7
  # Sin protección de purga, para que «terraform destroy» deje el nombre libre. Lo que guarda se puede regenerar.
  purge_protection_enabled = false
  tags                     = var.etiquetas

  network_acls {
    default_action             = "Deny"
    bypass                     = "AzureServices"
    ip_rules                   = var.ips_administracion
    virtual_network_subnet_ids = [azurerm_subnet.maquina.id]
  }
}

# Quien aplica Terraform escribe los secretos.
resource "azurerm_role_assignment" "administrador_secretos" {
  scope                = azurerm_key_vault.trainwatch.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.actual.object_id
}

# Los permisos de Azure tardan en propagarse: sin esta espera, la primera escritura suele dar 403.
resource "time_sleep" "propagacion_permisos" {
  create_duration = "90s"
  depends_on = [
    azurerm_role_assignment.administrador_secretos,
    azurerm_role_assignment.maquina_secretos,
    azurerm_role_assignment.maquina_copias,
  ]
}

resource "azurerm_key_vault_secret" "clave_bd" {
  name             = "bd-clave"
  key_vault_id     = azurerm_key_vault.trainwatch.id
  value_wo         = ephemeral.random_password.bd.result
  value_wo_version = var.version_clave_bd
  content_type     = "Clave de PostgreSQL (usuario trainwatch)"
  depends_on       = [time_sleep.propagacion_permisos]
}

# Token del disparo manual de la importación del horario (POST /api/horario/importaciones). Caddy no deja pasar
# ningún POST desde fuera: solo se usa desde la propia máquina.
ephemeral "random_password" "token_admin" {
  length  = 40
  special = false
}

resource "azurerm_key_vault_secret" "token_admin" {
  name             = "token-admin"
  key_vault_id     = azurerm_key_vault.trainwatch.id
  value_wo         = ephemeral.random_password.token_admin.result
  value_wo_version = 1
  content_type     = "TRAINWATCH_ADMIN_TOKEN"
  depends_on       = [time_sleep.propagacion_permisos]
}

# Certificado de origen de Cloudflare (cloudflare.tf), para el HTTPS entre Cloudflare y Caddy.
resource "azurerm_key_vault_secret" "certificado_origen" {
  name         = "origen-certificado"
  key_vault_id = azurerm_key_vault.trainwatch.id
  value        = cloudflare_origin_ca_certificate.api.certificate
  content_type = "application/x-pem-file"
  depends_on   = [time_sleep.propagacion_permisos]
}

resource "azurerm_key_vault_secret" "clave_origen" {
  name         = "origen-clave"
  key_vault_id = azurerm_key_vault.trainwatch.id
  value        = tls_private_key.origen.private_key_pem
  content_type = "application/x-pem-file"
  depends_on   = [time_sleep.propagacion_permisos]
}

# CA de los certificados de cliente de Cloudflare (cloudflare.tf): Caddy solo acepta conexiones firmadas por ella. No es
# secreta, pero así llega a la máquina por el mismo camino que el resto.
resource "azurerm_key_vault_secret" "ca_clientes" {
  name         = "origen-ca-clientes"
  key_vault_id = azurerm_key_vault.trainwatch.id
  value        = tls_self_signed_cert.aop_ca.cert_pem
  content_type = "application/x-pem-file"
  depends_on   = [time_sleep.propagacion_permisos]
}
