#!/usr/bin/env bash
# TrainWatch · copia lógica de la base de datos (fase 7, ADR 0021). La lanza cada noche trainwatch-copia.timer.
# pg_dump en formato propio (comprimido), sin los datos de las posiciones (48 h, se regeneran solas), y subida a
# Blob Storage con la identidad administrada de la máquina, que puede escribir pero no borrar. El almacenamiento las
# borra a los 14 días.
# Restauración: docs/despliegue.md, «Restaurar una copia».
set -euo pipefail

# shellcheck source=/dev/null
source /etc/trainwatch/maquina.env
# shellcheck source=/dev/null
source /opt/trainwatch/versiones.env
# La clave va por el entorno (-e PGPASSWORD sin valor), no en la línea de órdenes de docker: así no sale en «ps».
PGPASSWORD=$(sed -n 's/^TRAINWATCH_DB_PASSWORD=//p' /etc/trainwatch/backend.env)
export PGPASSWORD

marca=$(date -u +%Y%m%dT%H%M%SZ)
fichero="trainwatch-${marca}.dump"
dir=$(mktemp -d /var/tmp/trainwatch-copia.XXXXXX)
trap 'rm -rf "$dir"' EXIT
chmod 0777 "$dir"

# El cliente de PostgreSQL de la misma versión mayor que el servidor. TLS comprobando el certificado con los
# certificados raíz del sistema.
docker run --rm --network host \
  -e PGPASSWORD -e PGSSLMODE=verify-full -e PGSSLROOTCERT=system \
  -v "$dir":/salida \
  "$IMAGEN_CLIENTE_PG" \
  pg_dump -h "$HOST_POSTGRES" -U "$USUARIO_BD" -d "$BASE_DE_DATOS" \
    --format=custom --compress=9 --no-owner --no-privileges \
    --exclude-table-data='public.posiciones*' \
    --file="/salida/${fichero}"

tamano=$(stat -c %s "$dir/$fichero")
token=$(curl -sSf --retry 3 -H 'Metadata: true' \
  "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fstorage.azure.com%2F&client_id=${IDENTIDAD}" \
  | jq -r .access_token)
curl -sSf --retry 3 -X PUT -T "$dir/$fichero" \
  -H @<(printf 'Authorization: Bearer %s\n' "$token") \
  -H 'x-ms-version: 2023-11-03' \
  -H 'x-ms-blob-type: BlockBlob' \
  -H 'Content-Type: application/octet-stream' \
  "https://${CUENTA_COPIAS}.blob.core.windows.net/${CONTENEDOR_COPIAS}/${fichero}"

echo "Copia subida: ${fichero} (${tamano} bytes)"
