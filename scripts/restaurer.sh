#!/usr/bin/env bash
# Restauration guidée d'une sauvegarde, exécutée SUR L'HÔTE par l'exploitant,
# depuis le répertoire de docker-compose.prod.yml. Hors de l'application, par
# décision (docs/decisions/0015-…) : aucune route, aucun bouton ne restaure.
#
#   scripts/restaurer.sh                        # liste les dumps du volume, demande lequel
#   scripts/restaurer.sh planning-20260308-040000.dump
#   scripts/restaurer.sh --fichier ./copie.dump # un fichier de l'hôte
#   scripts/restaurer.sh --essai [dump]         # dans une base jetable, chronométré
#
# Enchaînement : contrôle du dump (lu en entier, version de son schéma comparée
# à celle de l'image) → dump de précaution de l'état actuel → confirmation
# retapée → arrêt de `app` → base supprimée et
# recréée, pg_restore en une seule transaction → redémarrage de `app` →
# attente de la disponibilité (healthcheck /q/health/ready).
#
# Base recréée plutôt que `pg_restore --clean` : --clean ne supprime que les
# objets présents dans le dump, et restaurer un dump plus ancien que le schéma
# (le cas d'un retour arrière) laisserait les tables des migrations suivantes,
# que Flyway rejouerait au démarrage pour échouer sur « already exists ».
# Si la restauration échoue ou est interrompue, le dump de précaution est
# restauré à sa place avant de relancer `app` : l'application ne redémarre
# jamais sur une base vide ou à moitié restaurée — y compris quand la session
# SSH tombe en cours de route. Mieux vaut tout de même lancer le script dans
# tmux (ou sous nohup) : un retour arrière qui tourne sans terminal ne dit
# plus à personne comment il s'est terminé.
#
# Tout passe par `docker compose` : pg_restore et pg_dump viennent du conteneur
# `postgres` (même majeure que le serveur), le volume des sauvegardes est lu
# par un conteneur jetable de l'image applicative, point d'entrée remplacé —
# ce qui marche aussi le jour où l'application ne démarre plus. Les dumps
# portent des données de mineurs : rien ne quitte l'hôte, la copie de travail
# est effacée en sortie, la base d'essai aussi.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage : restaurer.sh [options] [nom-du-dump]

  [nom-du-dump]        fichier du volume des sauvegardes, ex. planning-20260308-040000.dump ;
                       sans argument, la liste est affichée et le choix demandé

Options :
  --fichier <chemin>   restaurer un fichier de l'hôte plutôt qu'un dump du volume
  --essai              restaurer dans une base jetable (conteneur postgres temporaire),
                       sans toucher la production, et chronométrer
  --oui                ne rien demander (procédure écrite, CI) — la confirmation est réputée donnée
  -f, --compose <f>    fichier compose (défaut : docker-compose.prod.yml)
  --env-file <f>       fichier d'environnement (défaut : .env.prod)
  --attente <s>        délai maximal pour que l'application redevienne disponible (défaut : 300)
  -h, --help           cette aide
EOF
}

COMPOSE_FILE=docker-compose.prod.yml
ENV_FILE=.env.prod
FICHIER=""
NOM=""
ESSAI=false
OUI=false
ATTENTE=300
valeur_option() {
  [[ $# -ge 2 && -n "$2" ]] || { echo "L'option $1 attend une valeur" >&2; usage >&2; exit 2; }
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --fichier) valeur_option "$@"; FICHIER="$2"; shift 2 ;;
    --essai) ESSAI=true; shift ;;
    --oui) OUI=true; shift ;;
    -f|--compose) valeur_option "$@"; COMPOSE_FILE="$2"; shift 2 ;;
    --env-file) valeur_option "$@"; ENV_FILE="$2"; shift 2 ;;
    --attente) valeur_option "$@"; ATTENTE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Option inconnue : $1" >&2; usage >&2; exit 2 ;;
    *) [[ -z "$NOM" ]] || { usage >&2; exit 2; }; NOM="$1"; shift ;;
  esac
done
[[ -z "$FICHIER" || -z "$NOM" ]] || { echo "✘ --fichier et un nom de dump s'excluent" >&2; exit 2; }
[[ "$ATTENTE" =~ ^[1-9][0-9]*$ ]] || { echo "✘ --attente attend un nombre de secondes" >&2; exit 2; }

# The database and its owner are fixed by the compose file, not by .env.prod.
DB=festival
DB_USER=festival
BACKUPS=/backups

etape() { echo; echo "▸ $*"; }
ok() { echo "  ✔ $*"; }
ko() { echo "  ✘ $*" >&2; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }
# A throwaway container of the application image with the backups volume
# mounted exactly as the compose file mounts it — its entrypoint replaced, so
# it works even when the application itself no longer starts.
volume() { dc run --rm --no-deps -T --entrypoint bash app -c "$1"; }

[[ -f "$COMPOSE_FILE" ]] || ko "$COMPOSE_FILE introuvable : lancez le script depuis le répertoire de la pile"
[[ -f "$ENV_FILE" ]] || ko "$ENV_FILE introuvable (--env-file)"
command -v docker >/dev/null || ko "docker est introuvable"

TRAVAIL="$(mktemp -d)"
APP_ARRETEE=false
BASE_ENTAMEE=false
PRECAUTION=""
ESSAI_CONTENEUR=""

# Replaces the database with a dump: dropped and recreated (the application is
# stopped, --force ends any other session), then restored in ONE transaction
# that stops at the first error — so a failure leaves an empty database, never
# a half-restored one.
restaurer_base() {
  dc exec -T postgres dropdb --force --if-exists -U $DB_USER $DB \
    && dc exec -T postgres createdb -U $DB_USER -O $DB_USER $DB \
    && dc exec -T postgres pg_restore --no-owner --single-transaction --exit-on-error \
         -U $DB_USER -d $DB < "$1"
}

# The last successful migration in the data of flyway_schema_history, from the
# plain-SQL text pg_restore prints for that table alone (COPY … FROM stdin).
derniere_migration() {
  awk -F'\t' '
    /^COPY [^ ]*flyway_schema_history \(/ {
      cols = $0; sub(/^[^(]*\(/, "", cols); sub(/\).*$/, "", cols)
      n = split(cols, c, ", ")
      for (i = 1; i <= n; i++) { if (c[i] == "version") v = i; if (c[i] == "success") s = i }
      dedans = 1; next
    }
    dedans && $0 == "\\." { dedans = 0 }
    dedans && v && s && $s == "t" && $v != "\\N" { print $v }
  ' | sort -V | tail -n1
}

nettoyer() {
  local code=$?
  # Nothing may abort the way back from here: not errexit on a message that a
  # dropped SSH session's terminal refuses (echo then fails, and under set -e
  # that would end the script with the application stopped on an empty
  # database), not a second signal, not the SIGPIPE of a `| tee` that died
  # with the session. Every message below is best-effort.
  set +e
  trap '' INT TERM HUP PIPE
  if [[ "$BASE_ENTAMEE" == true ]]; then
    # Interrupted or failed while the database was being replaced. A client
    # killed by Ctrl-C does not stop the pg_restore running in the container:
    # end it first, then put the state from before back.
    echo "  … restauration interrompue : retour à l'état d'avant ($PRECAUTION)" >&2 || true
    dc exec -T postgres psql -U $DB_USER -d postgres -qtAc \
      "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB' AND pid <> pg_backend_pid()" \
      >/dev/null 2>&1 || true
    if restaurer_base "$TRAVAIL/precaution" >/dev/null 2>&1; then
      echo "  ✔ état d'avant restauré" >&2 || true
    else
      echo "  ✘ l'état d'avant n'a pas pu être restauré : l'application reste ARRÊTÉE." >&2 || true
      echo "    Restaurer à la main : scripts/restaurer.sh $PRECAUTION" >&2 || true
      APP_ARRETEE=false
    fi
  fi
  if [[ "$APP_ARRETEE" == true ]]; then
    echo "  … relance de l'application" >&2 || true
    dc start app >/dev/null 2>&1 \
      || echo "  ✘ relance impossible : docker compose -f $COMPOSE_FILE start app" >&2 || true
  fi
  [[ -z "$ESSAI_CONTENEUR" ]] || docker rm -f "$ESSAI_CONTENEUR" >/dev/null 2>&1 || true
  rm -rf "$TRAVAIL"
  exit "$code"
}
trap nettoyer EXIT
# Every way out goes through nettoyer: a signal left at its default action
# would kill the shell without running it — SIGHUP when the SSH session drops,
# SIGPIPE when the `| tee` it was piped into died with it.
trap 'exit 130' INT TERM
trap 'exit 129' HUP
trap 'exit 141' PIPE

# 1. Le dump à restaurer.
etape "Choix du dump"
if [[ -n "$FICHIER" ]]; then
  [[ -r "$FICHIER" ]] || ko "$FICHIER est illisible"
  DUMP="$FICHIER"
  ok "fichier de l'hôte : $FICHIER ($(du -h "$FICHIER" | cut -f1))"
else
  if [[ -z "$NOM" ]]; then
    liste="$(volume "cd $BACKUPS 2>/dev/null && ls -1t -- *.dump 2>/dev/null" || true)"
    [[ -n "$liste" ]] || ko "aucun dump dans le volume des sauvegardes ($BACKUPS)"
    echo "  Dumps disponibles, du plus récent au plus ancien :"
    volume "cd $BACKUPS && ls -lt --time-style='+%Y-%m-%d %H:%M' -- *.dump" \
      | awk 'NR>0 {printf "   %3d  %s %s  %8s  %s\n", NR, $6, $7, $5, $8}'
    [[ "$OUI" == false ]] || ko "--oui demande de nommer le dump"
    read -r -p "  Numéro du dump à restaurer : " choix || ko "pas de terminal pour choisir : nommez le dump"
    [[ "$choix" =~ ^[0-9]+$ ]] || ko "choix invalide"
    NOM="$(sed -n "${choix}p" <<<"$liste")"
    [[ -n "$NOM" ]] || ko "aucun dump n° $choix"
  fi
  [[ "$NOM" =~ ^[A-Za-z0-9._-]+\.dump$ ]] || ko "« $NOM » n'est pas un nom de dump du volume (voir --fichier pour un chemin)"
  DUMP="$TRAVAIL/$NOM"
  volume "cat -- '$BACKUPS/$NOM'" > "$DUMP" || ko "$NOM introuvable dans le volume"
  ok "$NOM ($(du -h "$DUMP" | cut -f1))"
fi

# 2. Contrôles préalables — avant tout arrêt.
etape "Contrôles"
if [[ "$ESSAI" == false ]]; then
  # grep without -q: under pipefail, a grep that quits at its first match
  # can kill the writer with SIGPIPE and fail a pipeline that found it.
  dc ps --status running --services 2>/dev/null | grep -x postgres >/dev/null \
    || ko "le service postgres ne tourne pas : docker compose -f $COMPOSE_FILE up -d postgres"
fi
# The trial runs on the stack's own PostgreSQL image, read from the compose
# file rather than written here: the day it moves to another major, pg_restore
# must move with it.
if [[ "$ESSAI" == true ]]; then
  IMAGE_POSTGRES="$(dc config --images postgres 2>/dev/null | head -n1)" || IMAGE_POSTGRES=""
  [[ -n "$IMAGE_POSTGRES" ]] || ko "impossible de lire l'image du service postgres dans $COMPOSE_FILE"
fi
lecteur=(dc exec -T postgres)
[[ "$ESSAI" == false ]] || lecteur=(docker run --rm -i "$IMAGE_POSTGRES")
# pg_restore --list reads the table of contents only: a plain-SQL dump or
# anything else is refused here, before production is touched.
"${lecteur[@]}" pg_restore --list < "$DUMP" > "$TRAVAIL/toc" 2>&1 \
  || ko "ce fichier n'est pas un dump pg_dump --format=custom lisible : $(head -n1 "$TRAVAIL/toc")"
# A dump cut short after its table of contents (disk full, interrupted copy)
# passes --list: the whole archive is read too, every data block decompressed
# into /dev/null, so it fails here rather than halfway through the restore.
"${lecteur[@]}" pg_restore -f /dev/null < "$DUMP" > "$TRAVAIL/lecture" 2>&1 \
  || ko "dump incomplet ou corrompu, illisible jusqu'au bout : $(tail -n1 "$TRAVAIL/lecture")"
ok "dump lisible en entier ($(grep -c 'TABLE DATA' "$TRAVAIL/toc" || true) tables de données)"

# 3a. Mode essai : une base jetable, rien d'autre.
if [[ "$ESSAI" == true ]]; then
  etape "Essai dans une base jetable"
  ESSAI_CONTENEUR="planning-essai-restauration-$$"
  # No port is published and the container dies with the script: `trust`
  # needs no password to invent.
  docker run -d --name "$ESSAI_CONTENEUR" -e POSTGRES_DB=$DB -e POSTGRES_USER=$DB_USER \
    -e POSTGRES_HOST_AUTH_METHOD=trust "$IMAGE_POSTGRES" >/dev/null
  for _ in $(seq 1 60); do
    # Over TCP: during initdb the image runs a temporary server on the unix
    # socket only, which would answer before the database exists.
    docker exec "$ESSAI_CONTENEUR" pg_isready -h 127.0.0.1 -U $DB_USER -d $DB -q 2>/dev/null && break
    sleep 1
  done
  docker exec "$ESSAI_CONTENEUR" pg_isready -h 127.0.0.1 -U $DB_USER -d $DB -q || ko "la base jetable ne démarre pas"
  debut=$SECONDS
  docker exec -i "$ESSAI_CONTENEUR" pg_restore --no-owner --single-transaction --exit-on-error \
    -U $DB_USER -d $DB < "$DUMP" \
    || ko "pg_restore a échoué dans la base jetable"
  duree=$((SECONDS - debut))
  editions="$(docker exec "$ESSAI_CONTENEUR" psql -U $DB_USER -d $DB -tAc 'SELECT count(*) FROM edition' 2>/dev/null || echo '?')"
  schema="$(docker exec "$ESSAI_CONTENEUR" psql -U $DB_USER -d $DB -tAc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null || echo '?')"
  ok "restauré en ${duree} s : $editions édition(s), schéma en version $schema"
  echo
  echo "Essai terminé, base jetable supprimée. La production n'a pas été touchée."
  exit 0
fi

# A dump newer than the image (an image rolled back, a dump brought from
# another instance): Flyway ignores the migrations it does not know, and the
# application would start on a schema it was never written for. Said, and
# confirmed, before anything stops.
version_dump="$("${lecteur[@]}" pg_restore --data-only --table=flyway_schema_history -f - < "$DUMP" 2>/dev/null \
  | derniere_migration)" || version_dump=""
# The image's own migrations, read from the application jar in a throwaway
# container: file names are stored uncompressed in a zip.
# shellcheck disable=SC2016  # expanded inside the container, on purpose
version_image="$(volume 'grep -aoh "db/migration/V[0-9][0-9._]*__" /app/app/*.jar 2>/dev/null' \
  | sed 's|.*/V||; s|__$||; s|_|.|g' | sort -uV | tail -n1)" || version_image=""
if [[ -z "$version_dump" || -z "$version_image" ]]; then
  echo "  ⚠ version du schéma illisible (dump : ${version_dump:-?}, image : ${version_image:-?}) : contrôle sauté" >&2
elif [[ "$version_dump" != "$version_image" \
        && "$(printf '%s\n' "$version_dump" "$version_image" | sort -V | tail -n1)" == "$version_dump" ]]; then
  echo "  ⚠ le schéma du dump (V$version_dump) est PLUS RÉCENT que celui de l'image déployée (V$version_image)." >&2
  echo "    Flyway ignore les migrations qu'il ne connaît pas : l'application démarrerait sur un" >&2
  echo "    schéma qu'elle ne connaît pas. Mieux vaut déployer d'abord l'image de ce dump (APP_VERSION)." >&2
  if [[ "$OUI" == false ]]; then
    read -r -p "  Tapez PLUS-RECENT pour restaurer quand même : " mot \
      || ko "pas de terminal pour confirmer : passez --oui"
    [[ "$mot" == PLUS-RECENT ]] || ko "abandon : rien n'a été touché"
  fi
else
  ok "schéma du dump en version $version_dump, connu de l'image (jusqu'à $version_image)"
fi

# Room for the safety dump: at least the size of the current database.
taille_base="$(dc exec -T postgres psql -U $DB_USER -d $DB -tAc "SELECT pg_database_size('$DB')" | tr -d '[:space:]')"
libre="$(volume "df -Pk $BACKUPS | awk 'NR==2 {print \$4 * 1024}'" | tr -d '[:space:]')"
if [[ -n "$taille_base" && -n "$libre" ]] && (( libre < taille_base )); then
  ko "espace insuffisant dans $BACKUPS pour le dump de précaution ($libre octets libres, base de $taille_base)"
fi
ok "espace disponible pour le dump de précaution"

# 3. Filet de sécurité : l'état actuel, sous un nom que la rotation ignore.
etape "Dump de précaution de l'état actuel"
PRECAUTION="planning-avant-restauration-$(date +%Y%m%d-%H%M%S).dump"
dc exec -T postgres pg_dump -U $DB_USER -d $DB --format=custom > "$TRAVAIL/precaution" \
  || ko "le dump de précaution a échoué : restauration annulée, rien n'a changé"
volume "cat > '$BACKUPS/$PRECAUTION'" < "$TRAVAIL/precaution" \
  || ko "impossible d'écrire le dump de précaution dans le volume : restauration annulée"
ok "$PRECAUTION écrit dans le volume des sauvegardes (hors rotation)"
echo "    Pour revenir en arrière : scripts/restaurer.sh $PRECAUTION"

# 4. Confirmation.
etape "Confirmation"
url="$(sed -n 's/^[[:space:]]*PUBLIC_URL=//p' "$ENV_FILE" | tail -n1)"
echo "  La base « $DB » de l'instance ${url:-(PUBLIC_URL non renseignée)} va être ÉCRASÉE par ${NOM:-$FICHIER}."
echo "  Toute écriture postérieure à ce dump sera perdue (sauf à restaurer $PRECAUTION)."
echo "  L'application sera arrêtée pendant l'opération."
if [[ "$OUI" == false ]]; then
  read -r -p "  Tapez RESTAURER pour continuer : " mot || ko "pas de terminal pour confirmer : passez --oui"
  [[ "$mot" == RESTAURER ]] || ko "abandon : rien n'a été restauré (le dump de précaution reste dans le volume)"
fi

# 5. Restauration, application arrêtée : ni écriture concurrente, ni cache
# d'éditions périmé au redémarrage.
etape "Restauration"
debut=$SECONDS
APP_ARRETEE=true
dc stop app >/dev/null
ok "application arrêtée"
BASE_ENTAMEE=true
restaurer_base "$DUMP" || ko "la restauration a échoué"
BASE_ENTAMEE=false
ok "base recréée et restaurée"
dc start app >/dev/null
APP_ARRETEE=false
ok "application relancée"

# 6. Vérification : le healthcheck du service (/q/health/ready = base joignable,
# migrations à jour).
etape "Vérification"
conteneur="$(dc ps -q app)"
etat=inconnu
for _ in $(seq 1 "$ATTENTE"); do
  etat="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}sans-healthcheck{{end}}' "$conteneur" 2>/dev/null || echo absent)"
  [[ "$etat" == healthy || "$etat" == sans-healthcheck ]] && break
  sleep 1
done
case "$etat" in
  healthy) ok "application disponible (/q/health/ready)" ;;
  sans-healthcheck) ok "application relancée (pas de healthcheck dans $COMPOSE_FILE : vérifiez à la main)" ;;
  *) ko "application toujours « $etat » après ${ATTENTE} s : docker compose -f $COMPOSE_FILE logs app" ;;
esac

echo
echo "Restauration terminée en $((SECONDS - debut)) s d'interruption. Dump de précaution : $PRECAUTION"
