#!/usr/bin/env bash
# TrainWatch · despliegue en la máquina (fase 7, ADR 0020). Lo ejecuta GitHub Actions como root con
# «az vm run-command invoke», después de copiar infra/produccion/ en /opt/trainwatch.
#   desplegar.sh ghcr.io/ivanchesa/trainwatch-backend@sha256:...
# 1. Lee los secretos de Key Vault con la identidad administrada de la máquina (sin guardar ninguna clave).
# 2. Arranca la imagen nueva con Docker Compose y espera a que esté lista.
# 3. Si no arranca en 3 minutos, vuelve a la imagen anterior y falla.
# 4. Instala el temporizador de las copias de la base de datos.
# La última línea es DESPLIEGUE_OK o DESPLIEGUE_FALLIDO: la CI la busca (run-command siempre termina «bien»).
set -euo pipefail

imagen=${1:?Uso: desplegar.sh <imagen por digest>}
dir=/opt/trainwatch
trap 'echo "DESPLIEGUE_FALLIDO (línea $LINENO)"' ERR

# shellcheck source=/dev/null
source /etc/trainwatch/maquina.env

# --- 1. Secretos ---------------------------------------------------------------------------------------------------
token=$(curl -sSf --retry 3 -H 'Metadata: true' \
  "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fvault.azure.net&client_id=${IDENTIDAD}" \
  | jq -r .access_token)
# La cabecera con el token se pasa por un descriptor (-H @...), no como argumento: así no sale en «ps».
secreto() {
  curl -sSf --retry 3 -H @<(printf 'Authorization: Bearer %s\n' "$token") \
    "https://${KEY_VAULT}.vault.azure.net/secrets/$1?api-version=7.4" | jq -r .value
}

umask 077
clave_bd=$(secreto bd-clave)
token_admin=$(secreto token-admin)
# sslmode=verify-full: TLS con el certificado y el nombre del servidor comprobados contra los certificados raíz del
# JRE (DigiCert y Microsoft, los que usa Azure).
cat > /etc/trainwatch/backend.env.nuevo <<EOF
TRAINWATCH_DB_URL=jdbc:postgresql://${HOST_POSTGRES}:5432/${BASE_DE_DATOS}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
TRAINWATCH_DB_USER=${USUARIO_BD}
TRAINWATCH_DB_PASSWORD=${clave_bd}
TRAINWATCH_ADMIN_TOKEN=${token_admin}
EOF
mv /etc/trainwatch/backend.env.nuevo /etc/trainwatch/backend.env
secreto origen-certificado > /etc/trainwatch/tls/origen.pem.nuevo
secreto origen-clave > /etc/trainwatch/tls/origen.key.nuevo
# La CA de los certificados de cliente que presenta Cloudflare (Authenticated Origin Pulls de trainwatch-api).
secreto origen-ca-clientes > /etc/trainwatch/tls/clientes-ca.pem.nuevo
mv /etc/trainwatch/tls/origen.pem.nuevo /etc/trainwatch/tls/origen.pem
mv /etc/trainwatch/tls/origen.key.nuevo /etc/trainwatch/tls/origen.key
mv /etc/trainwatch/tls/clientes-ca.pem.nuevo /etc/trainwatch/tls/clientes-ca.pem
unset token clave_bd token_admin
umask 022
echo "Secretos leídos de Key Vault ${KEY_VAULT}"

# --- 2. Arranque ---------------------------------------------------------------------------------------------------
cd "$dir"
anterior=$(sed -n 's/^TRAINWATCH_IMAGEN=//p' .env 2>/dev/null || true)
escribir_env() { printf 'TRAINWATCH_IMAGEN=%s\nHOST_API=%s\n' "$1" "$HOST_API" > .env; }

listo() {
  for _ in $(seq 1 36); do
    if curl -sf http://127.0.0.1:8081/actuator/health/readiness >/dev/null; then return 0; fi
    sleep 5
  done
  return 1
}

# Primero se descarga (con la imagen nueva solo en el entorno): si falla, .env sigue apuntando a la que funciona y
# la próxima vuelta atrás no irá a una imagen que nunca arrancó.
TRAINWATCH_IMAGEN="$imagen" HOST_API="$HOST_API" docker compose pull --quiet
escribir_env "$imagen"
docker compose up -d --remove-orphans
# Caddy lee el certificado al arrancar: si ha cambiado, se reinicia (es un segundo, y el SSE se reconecta solo).
docker compose restart caddy

if listo; then
  echo "Backend listo con ${imagen}"
else
  echo "El backend no está listo en 3 minutos. Últimas líneas:"
  docker compose logs --tail 40 backend || true
  if [ -n "$anterior" ] && [ "$anterior" != "$imagen" ]; then
    echo "Vuelvo a la imagen anterior: ${anterior}"
    escribir_env "$anterior"
    docker compose up -d
    listo && echo "La anterior vuelve a estar en marcha" || echo "La anterior tampoco arranca"
  fi
  echo "DESPLIEGUE_FALLIDO"
  exit 1
fi

# --- 3. Copias de la base de datos (ADR 0021) ------------------------------------------------------------------------
install -m 0644 trainwatch-copia.service trainwatch-copia.timer /etc/systemd/system/
chmod 0755 copia.sh revisar.sh desplegar.sh
systemctl daemon-reload
systemctl enable --now trainwatch-copia.timer

# Imágenes viejas fuera, para no llenar el disco.
docker image prune -f >/dev/null
echo "DESPLIEGUE_OK"
