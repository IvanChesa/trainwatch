#!/usr/bin/env bash
# TrainWatch · vigilancia (fase 7). Lo ejecuta GitHub Actions cada 15 minutos (.github/workflows/vigilancia.yml):
# si falla, GitHub avisa a Iván por correo. También sirve a mano:  infra/vigilancia/comprobar.sh https://trainwatch-api.ivanchesa.com
# Falla si:
#   - la API no responde (o no responde JSON) en 20 s;
#   - la ingesta no ha terminado un ciclo en los últimos 5 min («calculado»);
#   - hace más de 5 min que no se guarda todo en la base de datos («guardadoAlDia»: filas atascadas en la cola);
#   - el último dato de Renfe tiene más de 5 min («antiguedadS»). De 01:00 a 05:00 (hora de Madrid) no hay trenes y
#     Renfe puede no publicar muestras nuevas: esa comprobación solo avisa, sin fallar.
set -uo pipefail

url=${1:-${URL_API:-https://trainwatch-api.ivanchesa.com}}
maximo=${ANTIGUEDAD_MAXIMA_S:-300}
resumen=${GITHUB_STEP_SUMMARY:-/dev/null}

fallo() { echo "❌ $*" | tee -a "$resumen"; exit 1; }

cuerpo=$(curl -sS --fail --max-time 20 --retry 2 --retry-delay 5 -H 'Accept: application/json' \
  -A 'trainwatch-vigilancia' "$url/api/directo/estado") || fallo "La API no responde: $url/api/directo/estado"
echo "$cuerpo" | jq -e . >/dev/null 2>&1 || fallo "La API no responde JSON: $(echo "$cuerpo" | head -c 200)"

ahora=$(date -u +%s)
calculado=$(echo "$cuerpo" | jq -r '.calculado // empty')
antiguedad=$(echo "$cuerpo" | jq -r '.antiguedadS // empty')
trenes=$(echo "$cuerpo" | jq -r '.trenes')
circuitos=$(echo "$cuerpo" | jq -c '.circuitos')

[ -n "$calculado" ] || fallo "La ingesta no ha terminado ningún ciclo (sin «calculado»)"
edad_ciclo=$(( ahora - $(date -u -d "$calculado" +%s) ))
[ "$edad_ciclo" -le "$maximo" ] || fallo "La ingesta lleva ${edad_ciclo} s sin terminar un ciclo (máximo ${maximo})"

# Que lo que llega se guarda: si la base de datos falla, la ingesta sigue (el directo va al día) pero las filas se
# quedan en una cola y «guardadoAlDia» deja de avanzar.
guardado=$(echo "$cuerpo" | jq -r '.guardadoAlDia // empty')
pendientes=$(echo "$cuerpo" | jq -c '.pendientes // {}')
if [ -n "$guardado" ]; then
  edad_guardado=$(( ahora - $(date -u -d "$guardado" +%s) ))
  [ "$edad_guardado" -le "$maximo" ] \
    || fallo "Hace ${edad_guardado} s que no se guarda todo en la base de datos (máximo ${maximo}). Pendientes: ${pendientes}"
fi

hora_madrid=$(TZ=Europe/Madrid date +%H)
noche=false
if [ "$hora_madrid" -ge 1 ] && [ "$hora_madrid" -lt 5 ]; then noche=true; fi

if [ -z "$antiguedad" ]; then
  [ "$noche" = true ] || fallo "No hay ningún dato de Renfe en memoria"
  echo "⚠️ De noche y sin datos de Renfe todavía" | tee -a "$resumen"
elif [ "$antiguedad" -gt "$maximo" ]; then
  [ "$noche" = true ] || fallo "El último dato de Renfe tiene ${antiguedad} s (máximo ${maximo}). Circuitos: ${circuitos}"
  echo "⚠️ De noche: el último dato de Renfe tiene ${antiguedad} s" | tee -a "$resumen"
fi

echo "✅ API en marcha: último ciclo hace ${edad_ciclo} s, último dato hace ${antiguedad:-?} s, ${trenes} trenes. Pendientes: ${pendientes}. Circuitos: ${circuitos}" | tee -a "$resumen"
