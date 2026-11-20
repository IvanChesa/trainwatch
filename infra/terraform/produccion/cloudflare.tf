# Cloudflare (ADR 0018): DNS de la API con proxy, certificado de origen para el modo «Full (strict)»,
# Authenticated Origin Pulls por nombre de host y el proyecto de Pages de la web.
# La zona ivanchesa.com es COMPARTIDA (la web principal, otros proyectos y el correo): aquí solo se tocan los
# registros trainwatch y trainwatch-api y ajustes por nombre de host, nunca ajustes de toda la zona. El modo SSL
# «Full (strict)» solo para trainwatch-api va en una regla de configuración (docs/despliegue.md, paso 5).

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

# Authenticated Origin Pulls **solo para trainwatch-api** (la zona es compartida: nada de ajustes de toda la zona).
# Cloudflare presenta un certificado de cliente al conectar con el origen y Caddy solo acepta los firmados por una CA
# propia de TrainWatch. Con el certificado compartido de Cloudflare, cualquier otra zona de Cloudflare lo presentaría
# igual; con uno propio, solo la conexión de esta zona y este nombre pasa.
resource "tls_private_key" "aop_ca" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_self_signed_cert" "aop_ca" {
  private_key_pem       = tls_private_key.aop_ca.private_key_pem
  is_ca_certificate     = true
  validity_period_hours = 87600 # 10 años
  allowed_uses          = ["cert_signing", "crl_signing"]
  subject {
    common_name  = "TrainWatch origin pull CA"
    organization = "TrainWatch"
  }
}

resource "tls_private_key" "aop_cliente" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_cert_request" "aop_cliente" {
  private_key_pem = tls_private_key.aop_cliente.private_key_pem
  subject {
    common_name  = "Cloudflare origin pull ${local.host_api}"
    organization = "TrainWatch"
  }
}

resource "tls_locally_signed_cert" "aop_cliente" {
  cert_request_pem      = tls_cert_request.aop_cliente.cert_request_pem
  ca_private_key_pem    = tls_private_key.aop_ca.private_key_pem
  ca_cert_pem           = tls_self_signed_cert.aop_ca.cert_pem
  validity_period_hours = 43800 # 5 años; Terraform lo renueva 30 días antes
  early_renewal_hours   = 720
  allowed_uses          = ["digital_signature", "client_auth"]
}

resource "cloudflare_authenticated_origin_pulls_hostname_certificate" "api" {
  zone_id     = var.cloudflare_zone_id
  certificate = tls_locally_signed_cert.aop_cliente.cert_pem
  private_key = tls_private_key.aop_cliente.private_key_pem
}

resource "cloudflare_authenticated_origin_pulls" "api" {
  zone_id = var.cloudflare_zone_id
  config = [{
    hostname = local.host_api
    cert_id  = cloudflare_authenticated_origin_pulls_hostname_certificate.api.id
    enabled  = true
  }]
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
