#!/usr/bin/env bash
# Tourne les captures et les vidéos de la vitrine, de bout en bout (site/README.md, « Les captures et les vidéos : le tournage »).
#
#   site/scripts/tournage/tourner.sh [dossier]      # par défaut site/src : images/, videos/ et exemples/ (les PDF) y sont réécrits
#   APP_IMAGE=ghcr.io/sylvainmetayer/planning-equipes:v2.1.0 site/scripts/tournage/tourner.sh
#
# Demande docker (et `docker compose`), ffmpeg et ImageMagick avec WebP. Playwright tourne dans son image
# officielle : il n'est dans aucune dépendance du dépôt. Prévoir un quart d'heure ; à lancer de préférence
# sur une machine jetable, la pile (PostgreSQL, application, Mailpit) est démontée à la fin.
set -euo pipefail

racine=$(cd "$(dirname "$0")/../../.." && pwd)
sortie=$(cd "${1:-$racine/site/src}" && pwd)
export APP_IMAGE=${APP_IMAGE:-ghcr.io/sylvainmetayer/planning-equipes:main}
# Un mot de passe jetable par tournage : la pile est démontée à la fin
ADMIN_PASSWORD=${ADMIN_PASSWORD:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)}
export ADMIN_PASSWORD
playwright=1.65.1
brut=$(mktemp -d)
compose=(docker compose -f "$racine/docker-compose.yml" -f "$racine/site/scripts/tournage/compose.yml" --profile app)

nettoyer() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$brut"
}
trap nettoyer EXIT

echo "== Pile : $APP_IMAGE"
"${compose[@]}" up --detach --wait --wait-timeout 420 postgres mailpit app

echo "== Parcours (Playwright $playwright)"
docker run --rm --network host --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -e BASE_URL=http://localhost:8080 -e MAILPIT_URL=http://localhost:8025 -e ADMIN_PASSWORD \
  -e SCENARIO=/repo/src/main/resources/scenarios/vitrine-fete-du-jeu-de-pierrefeuille.yaml -e OUT=/out \
  -v "$racine:/repo:ro" -v "$brut:/out" \
  "mcr.microsoft.com/playwright:v$playwright-noble" \
  bash -c "mkdir -p /tmp/w && cd /tmp/w && npm init -y >/dev/null && npm install playwright@$playwright --no-audit --no-fund >/dev/null && cp /repo/site/scripts/tournage/tourner.mjs . && node tourner.mjs"

echo "== PDF d'exemple"
mkdir -p "$sortie/exemples"
cp "$brut/exemples/planning-global.pdf" "$brut/exemples/planning-animateur.pdf" "$brut/exemples/planning-animateur-feuille.pdf" "$sortie/exemples/"

echo "== Encodage"
webp=(-quality 80 -define webp:method=6)
for lang in fr en; do
  mkdir -p "$sortie/images/$lang/vignettes" "$sortie/videos/$lang"
  for ecran in accueil planning rail personne horaires regles diagnostic diffuser mural; do
    magick "$brut/$lang/$ecran.png" "${webp[@]}" "$sortie/images/$lang/$ecran.webp"
    magick "$brut/$lang/$ecran.png" -resize 800x -quality 72 -define webp:method=6 "$sortie/images/$lang/vignettes/$ecran.webp"
  done
  for film in calcul jourj espace; do
    source_video="$brut/$lang/film-$film.webm"
    marques="$brut/$lang/film-$film.marks"
    # Le début montre le chargement de l'application, puis le menu qui se referme : il est coupé
    debut=0.8
    [[ "$film" == espace ]] || debut=3.0
    filtre="[0:v]trim=start=$debut,setpts=PTS-STARTPTS[v]"
    if [[ -s "$marques" ]]; then
      read -r de a cible < "$marques"
      filtre="[0:v]split=3[x][y][z];[x]trim=start=$debut:end=$de,setpts=PTS-STARTPTS[v0];[y]trim=start=$de:end=$a,setpts=(PTS-STARTPTS)*$cible/($a-$de)[v1];[z]trim=start=$a,setpts=PTS-STARTPTS[v2];[v0][v1][v2]concat=n=3:v=1:a=0[v]"
    fi
    ffmpeg -loglevel error -y -i "$source_video" -filter_complex "$filtre" -map '[v]' -an \
      -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -r 25 -movflags +faststart \
      "$sortie/videos/$lang/$film.mp4"
    # L'affiche est une image de la vidéo encodée, prise une fois le menu refermé
    ffmpeg -loglevel error -y -ss 2 -i "$sortie/videos/$lang/$film.mp4" -frames:v 1 "$brut/$lang/affiche-$film.png"
    magick "$brut/$lang/affiche-$film.png" "${webp[@]}" "$sortie/images/$lang/film-$film.webp"
    duree=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$sortie/videos/$lang/$film.mp4")
    poids=$(($(stat -c %s "$sortie/videos/$lang/$film.mp4") / 1000))
    printf '   %s %-7s %5.1f s  %4d ko\n' "$lang" "$film" "$duree" "$poids"
  done
done
echo "== Fini : relire les images, puis mettre la durée de chaque vidéo à jour dans src/_data/pages/accueil.json (fr et en)"
