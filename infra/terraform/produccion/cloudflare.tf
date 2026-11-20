# Cloudflare (ADR 0018): DNS de la API con proxy, certificado de origen para el modo «Full (strict)»,
# Authenticated Origin Pulls y el proyecto de Pages de la web. El modo SSL de la zona no se toca aquí: es de toda la
# zona ivanchesa.com (docs/despliegue.md, paso 5).

# API: registro A con proxy. Los navegadores ven las IP de Cloudflare, nunca la de la máquina.
resource "cloudflare_dns_record" "api" {
  zone_id = var.cloudflare_zone_id
  name    = local.host_api
  type    = "A"
  content = azurerm_public_ip.maquina.ip_address
  proxied = true
  ttl     = 1
  comment = "TrainWatch: API y directo (Azure, Terraform)"
}

# Certificado de origen: lo firma la CA de origen de Cloudflare y solo Cloudflare se fía de él, que es justo lo que
# hace falta en «Full (strict)». 15 años. La clave privada se genera aquí y va a Key Vault.
resource "tls_private_key" "origen" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_cert_request" "origen" {
  private_key_pem = tls_private_key.origen.private_key_pem
  subject {
    common_name = local.host_api
  }
}

resource "cloudflare_origin_ca_certificate" "api" {
  csr                = tls_cert_request.origen.cert_request_pem
  hostnames          = [local.host_api]
  request_type       = "origin-ecc"
  requested_validity = 5475
}

# Authenticated Origin Pulls: Cloudflare presenta un certificado de cliente al conectar con el origen y Caddy lo
# exige. Así, aunque alguien conozca la IP y venga desde otra cuenta de Cloudflare, no pasa. Las otras webs de la
# zona (Pages) no se ven afectadas: no comprueban el certificado de cliente.
resource "cloudflare_authenticated_origin_pulls_settings" "zona" {
  zone_id = var.cloudflare_zone_id
  enabled = true
}

# Web: proyecto de Pages de subida directa (sin integración con Git): lo publica GitHub Actions con wrangler
# después de pasar las pruebas (ADR 0020).
resource "cloudflare_pages_project" "web" {
  account_id        = var.cloudflare_account_id
  name              = var.proyecto_pages
  production_branch = "main"
}

resource "cloudflare_pages_domain" "web" {
  account_id   = var.cloudflare_account_id
  project_name = cloudflare_pages_project.web.name
  name         = local.host_web
}

resource "cloudflare_dns_record" "web" {
  zone_id = var.cloudflare_zone_id
  name    = local.host_web
  type    = "CNAME"
  content = cloudflare_pages_project.web.subdomain
  proxied = true
  ttl     = 1
  comment = "TrainWatch: web (Cloudflare Pages, Terraform)"
}
