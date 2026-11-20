# Lo que hace falta después del apply: las variables de GitHub (docs/despliegue.md, paso 6) y la administración.
# Ninguna salida es secreta.

output "grupo_de_recursos" {
  description = "Grupo de recursos (variable AZURE_GRUPO de GitHub)"
  value       = azurerm_resource_group.trainwatch.name
}

output "maquina" {
  description = "Nombre de la máquina (variable AZURE_MAQUINA de GitHub)"
  value       = azurerm_linux_virtual_machine.trainwatch.name
}

output "ip_publica" {
  description = "IP pública de la máquina (solo admite el 443 desde Cloudflare)"
  value       = azurerm_public_ip.maquina.ip_address
}

output "github_client_id" {
  description = "Client id de la identidad de GitHub Actions (variable AZURE_CLIENT_ID de GitHub)"
  value       = azurerm_user_assigned_identity.github.client_id
}

output "tenant_id" {
  description = "Tenant de Entra ID (variable AZURE_TENANT_ID de GitHub)"
  value       = data.azurerm_client_config.actual.tenant_id
}

output "suscripcion" {
  description = "Suscripción (variable AZURE_SUBSCRIPTION_ID de GitHub)"
  value       = var.suscripcion
}

output "key_vault" {
  description = "Key Vault con los secretos de producción"
  value       = azurerm_key_vault.trainwatch.name
}

output "postgres" {
  description = "Nombre DNS (privado) de PostgreSQL"
  value       = azurerm_postgresql_flexible_server.trainwatch.fqdn
}

output "cuenta_copias" {
  description = "Cuenta de almacenamiento de las copias lógicas"
  value       = azurerm_storage_account.copias.name
}

output "pages_subdominio" {
  description = "Subdominio pages.dev del proyecto de la web"
  value       = cloudflare_pages_project.web.subdomain
}

output "url_api" {
  description = "URL pública de la API"
  value       = "https://${local.host_api}"
}

output "url_web" {
  description = "URL pública de la web"
  value       = "https://${local.host_web}"
}
