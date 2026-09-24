#!/usr/bin/env bash
# Vérifie qu'une instance déployée est saine, à la bonne version, et prête à
# servir le public. À lancer par l'exploitant juste après
# `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d`,
# ou par toute CI de déploiement : le script ne demande ni accès à l'hôte ni
# identifiant, seulement l'URL publique.
#
#   scripts/verifier-deploiement.sh https://planning.exemple.org 1.2.0
#   scripts/verifier-deploiement.sh https://planning.exemple.org        # version lue dans .env.prod
#
# Contrôles, dans l'ordre, arrêt au premier échec (code de sortie non nul) :
#   1. /q/health/ready répond 200 UP — base joignable, aucune migration en
#      attente ni en échec. Attente bornée (--attente, 120 s par défaut) : une
#      instance qui migre, ou un proxy qui répond 502 avant elle, n'est pas un
#      échec tant que le délai court ;
#   2. la version exposée par /api/config est la version attendue ;
#   3. /api/branding et /api/mentions-legales répondent 200 ;
#   4. aucune mention légale obligatoire n'est vide, sauf si l'instance se
#      déclare de démonstration — l'indicateur demoInstance de
#      /api/mentions-legales, qui reflète LEGAL_DEMO_INSTANCE tel que
#      l'application l'a lu, quelle que soit la façon dont il a été posé.
#
# Dépendances : bash, curl, jq. Voir docs/exploitation.md § 2.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage : verifier-deploiement.sh [options] <url> [version-attendue]

  <url>               URL publique de l'instance, ex. https://planning.exemple.org
  [version-attendue]  ex. 1.2.0 ; par défaut APP_VERSION lu dans --env-file

Options :
  --env-file <f>      fichier d'environnement de la pile (défaut : .env.prod)
  --attente <s>       délai maximal d'attente de la disponibilité (défaut : 120)
  --version-libre     ne pas comparer la version (APP_VERSION=main avant v1.0.0)
  --instance-demo     ne pas exiger les mentions légales, même si l'instance
                      ne se déclare pas de démonstration
  -h, --help          cette aide
EOF
}

ENV_FILE=.env.prod
ATTENTE=120
VERSION_LIBRE=false
DEMO=""
POSITIONNELS=()
valeur_option() {
  [[ $# -ge 2 && -n "$2" ]] || { echo "L'option $1 attend une valeur" >&2; usage >&2; exit 2; }
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) valeur_option "$@"; ENV_FILE="$2"; shift 2 ;;
    --attente) valeur_option "$@"; ATTENTE="$2"; shift 2 ;;
    --version-libre) VERSION_LIBRE=true; shift ;;
    --instance-demo) DEMO=true; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Option inconnue : $1" >&2; usage >&2; exit 2 ;;
    *) POSITIONNELS+=("$1"); shift ;;
  esac
done

if [[ ${#POSITIONNELS[@]} -lt 1 || ${#POSITIONNELS[@]} -gt 2 ]]; then
  usage >&2
  exit 2
fi
[[ "$ATTENTE" =~ ^[0-9]+$ ]] || { echo "--attente attend un nombre de secondes" >&2; exit 2; }
for outil in curl jq; do
  command -v "$outil" >/dev/null || { echo "✘ $outil est introuvable" >&2; exit 2; }
done

URL="${POSITIONNELS[0]%/}"
ATTENDUE="${POSITIONNELS[1]:-}"

# Une valeur du fichier d'environnement, sans l'exécuter : un .env n'est pas un
# script, et le sourcer exécuterait ce qu'il contient. Tolère un fichier aux
# fins de ligne Windows, un `export ` en tête et un commentaire en fin de ligne.
valeur_env() {
  [[ -f "$ENV_FILE" ]] || return 0
  sed -e 's/\r$//' "$ENV_FILE" \
    | sed -n -e "s/^[[:space:]]*\(export[[:space:]]\{1,\}\)\{0,1\}$1=//p" \
    | tail -n1 \
    | sed -e 's/[[:space:]]\{1,\}#.*$//' -e 's/^["'\'']//' -e 's/["'\'']$//'
}

if [[ -z "$ATTENDUE" && "$VERSION_LIBRE" == false ]]; then
  ATTENDUE="$(valeur_env APP_VERSION)"
  if [[ -z "$ATTENDUE" ]]; then
    echo "✘ Version attendue inconnue : passez-la en argument, ou APP_VERSION dans $ENV_FILE (ou --version-libre)." >&2
    exit 2
  fi
fi

ok() { echo "✔ $*"; }
ko() { echo "✘ $*" >&2; exit 1; }

# 1. Disponibilité, attente bornée.
REPONSE="$(mktemp)"
trap 'rm -f "$REPONSE"' EXIT
debut=$SECONDS
while true; do
  code="$(curl -sS -o "$REPONSE" -w '%{http_code}' --max-time 10 "$URL/q/health/ready" 2>/dev/null || true)"
  if [[ "$code" == "200" ]] && jq -e '.status == "UP"' "$REPONSE" >/dev/null 2>&1; then
    ok "Disponible (/q/health/ready UP) après $((SECONDS - debut)) s"
    break
  fi
  if (( SECONDS - debut >= ATTENTE )); then
    detail="$(jq -c '[.checks[]? | select(.status != "UP") | .name]' "$REPONSE" 2>/dev/null || true)"
    ko "Indisponible après ${ATTENTE} s (dernier statut HTTP : ${code:-aucun}${detail:+, en échec : $detail})"
  fi
  sleep 3
done

# 2. Version.
version="$(curl -fsS --max-time 10 "$URL/api/config" | jq -r '.version // empty')" \
  || ko "/api/config ne répond pas"
if [[ "$VERSION_LIBRE" == true ]]; then
  ok "Version exposée : ${version:-inconnue} (non comparée)"
elif [[ "$version" == "$ATTENDUE" ]]; then
  ok "Version $version"
else
  indice=""
  [[ "$ATTENDUE" != main ]] || indice=" — une image :main expose son SHA, utilisez --version-libre"
  ko "Version exposée « ${version:-inconnue} », attendue « $ATTENDUE »$indice"
fi

# 3. Routes publiques.
curl -fsS --max-time 10 -o /dev/null "$URL/api/branding" || ko "/api/branding ne répond pas 200"
ok "/api/branding répond"
mentions="$(curl -fsS --max-time 10 "$URL/api/mentions-legales")" || ko "/api/mentions-legales ne répond pas 200"
ok "/api/mentions-legales répond"

# 4. Mentions légales obligatoires — les cinq que RequiredMentionsLegales exige
# au démarrage ; une instance qui a démarré les a donc, sauf démonstration.
# L'instance dit elle-même si elle en est une : le fichier d'environnement ne
# voit ni une valeur posée autrement (`environment:` du compose, orchestrateur),
# ni `True`, `TRUE` ou des guillemets que l'application, elle, accepte.
if [[ -z "$DEMO" ]]; then
  DEMO="$(jq -r 'if .demoInstance == true then "true" else "false" end' <<<"$mentions")"
fi
if [[ "$DEMO" == true ]]; then
  ok "Mentions légales non exigées (instance de démonstration)"
else
  manquantes="$(jq -r '[{editeur, hebergeur, contact, baseLegale, conservation} | to_entries[]
                       | select((.value // "") | test("^\\s*$")) | .key] | join(", ")' <<<"$mentions")"
  [[ -z "$manquantes" ]] || ko "Mentions légales obligatoires vides : $manquantes (instance non déclarée de démonstration : LEGAL_DEMO_INSTANCE=true, ou --instance-demo pour ne pas les exiger)"
  ok "Mentions légales obligatoires renseignées"
fi

echo "Déploiement vérifié : $URL"
