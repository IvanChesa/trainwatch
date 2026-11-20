#!/usr/bin/env bash
# TrainWatch · registra el consumo de la medición cada 5 s: docker stats de los dos contenedores y, cada 30 s,
# el heap, la CPU y las métricas de la ingesta por Actuator (puerto 18081, solo en localhost).
# Uso: infra/medicion/medir.sh <minutos> <carpeta de salida>
set -euo pipefail
minutos=${1:-20}
salida=${2:-medicion}
mkdir -p "$salida"
fin=$(( $(date +%s) + minutos * 60 ))
echo "hora,contenedor,cpu,memoria,limite,red,disco" > "$salida/docker-stats.csv"
echo "hora,heap_usado_mb,heap_comprometido_mb,no_heap_mb,cpu_proceso,trenes,antiguedad_s" > "$salida/actuator.csv"
metrica() { curl -sf "http://127.0.0.1:18081/actuator/metrics/$1${2:-}" | sed -n 's/.*"value":\([0-9.eE+-]*\).*/\1/p' | head -1; }
n=0
while [ "$(date +%s)" -lt "$fin" ]; do
  hora=$(date +%H:%M:%S)
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.NetIO}},{{.BlockIO}}' \
    trainwatch-medicion-backend-1 trainwatch-medicion-postgis-1 2>/dev/null \
    | sed "s|^|$hora,|; s| / |,|" >> "$salida/docker-stats.csv" || true
  if [ $((n % 6)) -eq 0 ]; then
    heap=$(metrica jvm.memory.used '?tag=area:heap' || true)
    comp=$(metrica jvm.memory.committed '?tag=area:heap' || true)
    noheap=$(metrica jvm.memory.used '?tag=area:nonheap' || true)
    cpu=$(metrica process.cpu.usage || true)
    trenes=$(metrica trainwatch.directo.trenes || true)
    antig=$(curl -sf http://127.0.0.1:18080/api/directo/estado | sed -n 's/.*"antiguedadS":\([0-9-]*\).*/\1/p' || true)
    awk -v h="$hora" -v a="${heap:-0}" -v b="${comp:-0}" -v c="${noheap:-0}" -v d="${cpu:-}" -v t="${trenes:-}" -v s="${antig:-}" \
      'BEGIN { printf "%s,%.0f,%.0f,%.0f,%s,%s,%s\n", h, a/1048576, b/1048576, c/1048576, d, t, s }' >> "$salida/actuator.csv"
  fi
  n=$((n + 1))
  sleep 5
done
