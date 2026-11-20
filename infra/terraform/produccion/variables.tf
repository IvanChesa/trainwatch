variable "suscripcion" {
  description = "Id de la suscripción de Azure (Azure for Students)"
  type        = string
}

variable "region" {
  description = "Región de Azure. La directiva de la suscripción solo admite algunas; se usa spaincentral"
  type        = string
  default     = "spaincentral"
}

variable "etiquetas" {
  description = "Etiquetas de todos los recursos"
  type        = map(string)
  default = {
    proyecto = "trainwatch"
    entorno  = "produccion"
    gestion  = "terraform"
  }
}

variable "tamano_maquina" {
  description = "Tamaño de la máquina virtual. B2ats_v2 (2 vCPU AMD, 1 GiB) entra en los 750 h/mes gratis (ADR 0017)"
  type        = string
  default     = "Standard_B2ats_v2"
}

variable "sku_postgres" {
  description = "Tamaño de PostgreSQL Flexible Server. B1ms (1 vCore, 2 GiB) entra en los 750 h/mes gratis"
  type        = string
  default     = "B_Standard_B1ms"
}

variable "version_postgres" {
  description = "Versión mayor de PostgreSQL (la 18, como en local y en las pruebas)"
  type        = string
  default     = "18"
}

variable "almacenamiento_postgres_mb" {
  description = "Disco de PostgreSQL en MB. 32 GB es el mínimo y lo que cubre la oferta gratuita"
  type        = number
  default     = 32768
}

variable "retencion_copias_dias" {
  description = "Días de copias gestionadas de PostgreSQL (restauración a cualquier instante). Gratis hasta el 100 % del disco"
  type        = number
  default     = 14
}

variable "version_clave_bd" {
  description = "Súbela en 1 para cambiar la clave de PostgreSQL: se genera otra y se escribe en el servidor y en Key Vault"
  type        = number
  default     = 1
}

variable "ips_administracion" {
  description = "IP públicas (sin /32) de quien aplica Terraform: Key Vault y la cuenta de copias solo admiten estas y la máquina"
  type        = list(string)
}

variable "sujeto_github" {
  description = <<-EOT
    Subject del token OIDC de GitHub que se acepta. GitHub lo firma con los identificadores fijos de la cuenta y del
    repositorio (propietario@id/repo@id), no solo con los nombres: comprobado en el primer despliegue (20-11-2026).
  EOT
  type        = string
  default     = "repo:IvanChesa@304584247/trainwatch@1465700293:environment:produccion"
}

variable "entorno_github" {
  description = "Entorno de GitHub Actions cuyo token se acepta (la credencial federada solo vale para él)"
  type        = string
  default     = "produccion"
}

variable "cloudflare_zone_id" {
  description = "Id de la zona ivanchesa.com en Cloudflare"
  type        = string
}

variable "cloudflare_account_id" {
  description = "Id de la cuenta de Cloudflare (para el proyecto de Pages)"
  type        = string
}

variable "dominio" {
  description = "Dominio de la zona de Cloudflare"
  type        = string
  default     = "ivanchesa.com"
}

variable "subdominio_api" {
  description = "Subdominio de la API y el directo"
  type        = string
  default     = "trainwatch-api"
}

variable "subdominio_web" {
  description = "Subdominio de la web (Cloudflare Pages)"
  type        = string
  default     = "trainwatch"
}

variable "proyecto_pages" {
  description = "Nombre del proyecto de Cloudflare Pages"
  type        = string
  default     = "trainwatch"
}
