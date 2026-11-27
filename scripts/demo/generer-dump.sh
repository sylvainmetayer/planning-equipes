#!/usr/bin/env bash
# Fabrique le dump de référence d'une instance de démonstration (ADR 0130,
# site/src/docs/installer/instance-de-demo.md) : une pile jetable démarre,
# importe le scénario de la vitrine (Pierrefeuille), calcule et publie le plan,
# crée les trois comptes locaux de la démo, puis écrit un pg_dump -Fc.
#
#   DEMO_PASSWORD=<mot de passe affiché> scripts/demo/generer-dump.sh reference.dump
#   APP_IMAGE=ghcr.io/sylvainmetayer/planning-equipes:4.2.0 DEMO_PASSWORD=<mot de passe affiché> scripts/demo/generer-dump.sh reference.dump
#
# DEMO_PASSWORD (12 caractères au moins) est le mot de passe des comptes
# rh@example.org, responsable@example.org et lecteur@example.org. Le compte
# admin n'est pas dans le dump : c'est ADMIN_PASSWORD de l'instance de démo.
# DEMO_ESPACE_TOKEN (un UUID, une valeur fixe par défaut) devient le jeton de
# l'espace de l'animateur du plan qui a une adresse et le plus de
# postes : son lien, /animateur/<jeton>, reste le même d'un dump à l'autre.
# Le dump est sans propriétaire ni droits (--no-owner --no-acl) : il se restaure
# sous le rôle de la base qui le reçoit. Demande docker (et `docker compose`),
# curl et jq ; quelques minutes.
set -euo pipefail

sortie=${1:?usage : DEMO_PASSWORD=… $0 <fichier.dump>}
: "${DEMO_PASSWORD:?DEMO_PASSWORD manque : le mot de passe des trois comptes locaux de la démo}"
if (( ${#DEMO_PASSWORD} < 12 )); then
  echo "DEMO_PASSWORD doit faire au moins 12 caractères" >&2
  exit 1
fi
ici=$(cd "$(dirname "$0")" && pwd)
racine=$(cd "$ici/../.." && pwd)
scenario=${SCENARIO:-$racine/src/main/resources/scenarios/vitrine-fete-du-jeu-de-pierrefeuille.yaml}
export APP_IMAGE=${APP_IMAGE:-ghcr.io/sylvainmetayer/planning-equipes:main}
export APP_PORT=${APP_PORT:-18080} MAILPIT_PORT=${MAILPIT_PORT:-18025}
# Jetables : la pile est démontée à la fin, et rien de ces deux secrets n'entre dans le dump.
ADMIN_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
DB_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
export ADMIN_PASSWORD DB_PASSWORD
# Public by design: the demo's showcase prints the link.
espace_token=${DEMO_ESPACE_TOKEN:-00000000-0000-4000-8000-0000000de305}
base=http://127.0.0.1:$APP_PORT
mailpit=http://127.0.0.1:$MAILPIT_PORT
travail=$(mktemp -d)
compose=(docker compose -p planning-demo-dump -f "$ici/compose.yml")

nettoyer() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$travail"
}
trap nettoyer EXIT

# curl sous la session admin ; échoue sur un statut d'erreur en montrant la réponse.
api() {
  local methode=$1 chemin=$2
  shift 2
  local corps=$travail/corps code
  code=$(curl -sS -o "$corps" -w '%{http_code}' -b "$travail/admin" -X "$methode" "$@" "$base$chemin")
  if (( code >= 400 )); then
    echo "$methode $chemin : $code $(head -c 300 "$corps")" >&2
    exit 1
  fi
  cat "$corps"
}

echo "== Pile : $APP_IMAGE"
"${compose[@]}" up --detach --wait --wait-timeout 420

echo "== Connexion admin"
code=$(curl -sS -o /dev/null -w '%{http_code}' -c "$travail/admin" \
  --data-urlencode j_username=admin --data-urlencode "j_password=$ADMIN_PASSWORD" "$base/j_security_check")
[[ $code == 302 ]] || { echo "connexion admin refusée ($code)" >&2; exit 1; }

echo "== Import du scénario"
# Une édition de passage porte l'en-tête de l'import, puis toutes les autres disparaissent : le dump n'a que la démo.
avant=$(api GET /api/editions | jq -r '.[].id')
passage=$(api POST /api/editions -H 'Content-Type: application/json' -d '{"nom":"Passage"}' | jq -r .id)
api PUT "/api/editions/$passage/active" -H "X-Edition-Id: $passage" >/dev/null
for ancienne in $avant; do
  api DELETE "/api/editions/$ancienne" -H "X-Edition-Id: $passage" >/dev/null
done
edition=$(api POST /api/reference-data/import-scenario-fichier -H 'Content-Type: text/plain' \
  -H "X-Edition-Id: $passage" --data-binary "@$scenario" | jq -r .editionId)
api PUT "/api/editions/$edition/active" -H "X-Edition-Id: $edition" >/dev/null
api DELETE "/api/editions/$passage" -H "X-Edition-Id: $edition" >/dev/null
h=(-H "X-Edition-Id: $edition")

echo "== Calcul"
job=$(api POST '/api/solve/async/reference-data?seconds=40' "${h[@]}" | jq -r .id)
for _ in $(seq 120); do
  etat=$(api GET "/api/jobs/$job" "${h[@]}" | jq -r .status)
  case $etat in
    COMPLETED) break ;;
    FAILED | CANCELLED | INTERRUPTED) echo "calcul : $etat" >&2; exit 1 ;;
  esac
  sleep 2
done
[[ $etat == COMPLETED ]] || { echo "calcul : toujours $etat après 4 minutes" >&2; exit 1; }
# Publié : l'écran du responsable de stand ne montre que le plan publié.
api POST /api/planning/publication "${h[@]}" -H 'Content-Type: application/json' -d '{"exclusions":[]}' >/dev/null

# Un compte local : créé et invité par l'API, mot de passe choisi par le lien lu dans Mailpit, connexion vérifiée.
compte() {
  local email=$1 nom=$2 droit=$3 id jeton=''
  id=$(api POST /api/comptes "${h[@]}" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg e "$email" --arg n "$nom" '{email: $e, nom: $n, invite: true}')" | jq -r .id)
  api POST "/api/comptes/$id/habilitations" "${h[@]}" -H 'Content-Type: application/json' -d "$droit" >/dev/null
  for _ in $(seq 60); do
    message=$(curl -sS "$mailpit/api/v1/search?query=$(jq -rn --arg e "to:\"$email\"" '$e|@uri')" | jq -r '.messages[0].ID // empty' || true)
    if [[ -n $message ]]; then
      jeton=$(curl -sS "$mailpit/api/v1/message/$message" | jq -r .Text | grep -oE 'mot-de-passe#[A-Za-z0-9_-]+' | head -n1 | cut -d'#' -f2 || true)
      [[ -n $jeton ]] && break
    fi
    sleep 0.5
  done
  [[ -n $jeton ]] || { echo "aucun lien de mot de passe pour $email" >&2; exit 1; }
  curl -sSf -o /dev/null -H 'Content-Type: application/json' \
    -d "$(jq -n --arg t "$jeton" --arg p "$DEMO_PASSWORD" '{token: $t, password: $p}')" "$base/api/auth/mot-de-passe"
  curl -sSf -H 'Content-Type: application/json' \
    -d "$(jq -n --arg e "$email" --arg p "$DEMO_PASSWORD" '{email: $e, password: $p}')" "$base/api/auth/connexion" \
    | jq -e '.challenge' >/dev/null || { echo "connexion de $email refusée" >&2; exit 1; }
  echo "   $email"
}

echo "== Comptes"
stands=$(api GET /api/stands "${h[@]}" | jq -c '[.[0:2][].id]')
compte rh@example.org 'Dominique RH' "{\"role\":\"RH\",\"editionId\":\"$edition\"}"
compte responsable@example.org 'Camille Responsable' \
  "{\"role\":\"RESPONSABLE_STAND\",\"editionId\":\"$edition\",\"standIds\":$stands}"
compte lecteur@example.org 'Alex Lecteur' "{\"role\":\"LECTEUR\",\"editionId\":\"$edition\"}"

echo "== Espace de démonstration"
# The animateur with an address and the most seats: a full espace to show.
api GET /api/planning/persisted "${h[@]}" > "$travail/plan.json"
api GET /api/animateurs "${h[@]}" > "$travail/animateurs.json"
animateur=$(jq -n --slurpfile plan "$travail/plan.json" --slurpfile gens "$travail/animateurs.json" -r '
  ([$gens[0][] | select(.email != null and .email != "") | .id]) as $avec
  | [$plan[0].postes[] | select(.animateur != null) | .animateur.id | select(IN($avec[]))]
  | group_by(.) | max_by(length) | .[0] // empty')
[[ -n $animateur ]] || { echo "aucun animateur avec une adresse et des postes" >&2; exit 1; }
"${compose[@]}" exec -T postgres psql -q -U festival -d festival -v ON_ERROR_STOP=1 \
  -c "UPDATE animateur SET access_token = '$espace_token' WHERE edition_id = '$edition' AND id = '$animateur'" >/dev/null
# An unknown token answers 404; a known one asks for the session (401).
code=$(curl -sS -o /dev/null -w '%{http_code}' "$base/api/espace-animateur/$espace_token")
[[ $code != 404 ]] || { echo "l'espace $espace_token est inconnu" >&2; exit 1; }
echo "   /animateur/$espace_token"

echo "== Dump"
# L'application arrêtée d'abord : rien ne s'écrit pendant la copie.
"${compose[@]}" stop app >/dev/null
"${compose[@]}" exec -T postgres pg_dump -U festival -d festival -Fc --no-owner --no-acl > "$sortie"
echo "== Fini : $sortie ($(du -h "$sortie" | cut -f1)), édition $edition"
