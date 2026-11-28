#!/usr/bin/env bash
# Joue un scénario k6 contre une instance de démo, par l'image officielle de
# k6 (la version est celle de compose.yml). Sort en erreur quand un seuil est
# dépassé.
#
#   BASE_URL=https://demo.example.org ADMIN_PASSWORD=… src/test/charge/lancer.sh visiteur
#   BASE_URL=http://127.0.0.1:18080 ADMIN_PASSWORD=… N=20 DUREE=3m src/test/charge/lancer.sh pic
#
# Scénarios : visiteur, calculs, espace, pic. Le résumé JSON de chaque passe
# s'écrit dans RESULTATS (défaut : ./resultats-charge). Les variables lues par
# les scénarios sont dans le README de ce dossier.
set -euo pipefail

ici=$(cd "$(dirname "$0")" && pwd)
scenario=${1:?usage : BASE_URL=… $0 visiteur|calculs|espace|pic [options k6…]}
shift
[[ -f $ici/scenarios/$scenario.js ]] || { echo "scénario inconnu : $scenario" >&2; exit 2; }
: "${BASE_URL:?BASE_URL manque : l’adresse de l’instance visée}"
image=$(sed -n 's/^ *image: *\(grafana\/k6:.*\)$/\1/p' "$ici/compose.yml")
resultats=$(mkdir -p "${RESULTATS:-resultats-charge}" && cd "${RESULTATS:-resultats-charge}" && pwd)

# Passed by name: a value never appears on the command line, the password least of all.
variables=()
for nom in BASE_URL ADMIN_USER ADMIN_PASSWORD N DUREE MONTEE PAUSE P95_MAX_MS TAUX_ERREUR_MAX \
  FILE_MAX SECONDES ESPACE_TOKEN ESPACE_TOKENS TAUX_SESSIONS_MIN; do
  [[ -n ${!nom:-} ]] && variables+=(-e "$nom")
done

# The host network: a BASE_URL on 127.0.0.1 is the machine's own, not the container's.
docker run --rm --network host --user "$(id -u):$(id -g)" "${variables[@]}" \
  -v "$ici/scenarios:/scenarios:ro" -v "$resultats:/resultats" \
  "$image" run --summary-export "/resultats/$scenario.json" "$@" "/scenarios/$scenario.js"
