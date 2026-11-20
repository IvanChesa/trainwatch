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
