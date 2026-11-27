#!/usr/bin/env bash
# Démarre l'instance de démo locale de compose.yml sur un dump de référence
# (scripts/demo/generer-dump.sh), comme la remise à zéro de l'hôte de démo :
# base recréée, dump restauré, application démarrée en DEMO_INSTANCE=true.
#
#   ADMIN_PASSWORD=… src/test/charge/demo-locale.sh reference.dump
#   src/test/charge/demo-locale.sh --arreter
#
# APP_IMAGE choisit la version (défaut :main), APP_PORT le port (18080),
# APP_MEM_LIMIT la limite de mémoire de l'application (2g, celle de
# docker-compose.prod.yml).
set -euo pipefail

ici=$(cd "$(dirname "$0")" && pwd)
compose=(docker compose -p planning-charge -f "$ici/compose.yml")
if [[ ${1:-} == --arreter ]]; then
  DB_PASSWORD=x ADMIN_PASSWORD=x "${compose[@]}" down --volumes --remove-orphans
  exit 0
fi
dump=${1:?usage : ADMIN_PASSWORD=… $0 <fichier.dump> | --arreter}
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD manque : le mot de passe du compte admin de la démo}"
# Jetable : la base ne sort pas de la machine.
DB_PASSWORD=${DB_PASSWORD:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)}
export ADMIN_PASSWORD DB_PASSWORD

"${compose[@]}" up --detach --wait postgres
"${compose[@]}" exec -T postgres pg_restore -U festival -d festival --no-owner --role=festival < "$dump"
"${compose[@]}" up --detach --wait --wait-timeout 420 app
echo "Démo locale prête : http://127.0.0.1:${APP_PORT:-18080}"
