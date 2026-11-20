#!/usr/bin/env bash
# TrainWatch · revisión diaria (fase 7). Resume lo que hay que mirar cada día sin abrir nada al exterior: se ejecuta
# en la máquina con «az vm run-command invoke ... --scripts /opt/trainwatch/revisar.sh» (docs/despliegue.md).
# Lee Actuator por el 8081 (solo localhost) y la base de datos por la red privada.
set -uo pipefail

# shellcheck source=/dev/null
source /etc/trainwatch/maquina.env
m() { curl -sf "http://127.0.0.1:8081/actuator/metrics/$1${2:-}" | jq -r '[.measurements[] | "\(.statistic)=\(.value)"] | join(" ")'; }

echo "== Salud"
curl -sf http://127.0.0.1:8081/actuator/health | jq -c '{status, componentes: (.components | map_values(.status))}'

echo "== Directo (/api/directo/estado)"
curl -sf http://127.0.0.1:8080/api/directo/estado \
  | jq -c '{ultimoDato, antiguedadS, trenes, sinHorario, circuitos, sinDatos: [.nucleos[] | select(.estado == "SIN_DATOS_EN_DIRECTO") | .nombre]}'

echo "== Horario (/api/horario/estado)"
curl -sf http://127.0.0.1:8080/api/horario/estado | jq -c '.' | cut -c1-400

echo "== Ingesta (desde el arranque)"
for n in descargas errores filas trenes antiguedad; do echo "trainwatch.directo.$n: $(m trainwatch.directo.$n)"; done
echo "== Directo SSE"
for n in conexiones rechazadas desconectados reconexiones; do echo "trainwatch.api.directo.$n: $(m trainwatch.api.directo.$n)"; done
echo "== JVM"
echo "heap: $(m jvm.memory.used '?tag=area:heap')"
echo "cpu: $(m process.cpu.usage)"
echo "arranque: $(m process.uptime)"

echo "== Máquina"
free -m | sed -n '1,3p'
df -h / | tail -1
docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}'
echo "Errores del backend en las últimas 24 h: $(docker compose -f /opt/trainwatch/compose.yaml logs --since 24h backend 2>&1 | grep -c ' ERROR ')"

echo "== Base de datos"
clave=$(sed -n 's/^TRAINWATCH_DB_PASSWORD=//p' /etc/trainwatch/backend.env)
docker run --rm --network host -e PGPASSWORD="$clave" -e PGSSLMODE=verify-full -e PGSSLROOTCERT=system \
  "postgres:${VERSION_CLIENTE_PG}-alpine" psql -h "$HOST_POSTGRES" -U "$USUARIO_BD" -d "$BASE_DE_DATOS" -XAt -F ' ' -c "
    select 'tamaño total', pg_size_pretty(pg_database_size(current_database()))
    union all select 'retrasos (filas de ayer)', count(*)::text from retrasos where fecha_servicio = current_date - 1
    union all select 'posiciones (filas de ayer)', count(*)::text from posiciones
      where instante >= (current_date - 1)::timestamptz and instante < current_date::timestamptz;"

echo "== Última copia"
journalctl -u trainwatch-copia --since '-36h' --no-pager -o cat | tail -2
