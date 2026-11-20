output "grupo_de_recursos" {
  description = "Grupo de recursos del estado (para backend.hcl de produccion)"
  value       = azurerm_resource_group.estado.name
}

output "cuenta_de_almacenamiento" {
  description = "Cuenta de almacenamiento del estado (para backend.hcl de produccion)"
  value       = azurerm_storage_account.estado.name
}

output "contenedor" {
  description = "Contenedor del estado"
  value       = azurerm_storage_container.estado.name
}
