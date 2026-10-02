#!/usr/bin/env bash
# Warns when a pull request adds a Flyway migration or a decision record under
# a number another open pull request adds too. Neither has merged, so neither
# is wrong yet: whichever merges second will have to renumber, and
# check-reserved-numbers.sh refuses it the moment the first one lands. This
# only says so earlier, as a warning on the files, and never fails on it.
#
#   REPO=owner/name PR=123 scripts/check-open-pr-numbers.sh
#
# Reads the GitHub API through `gh` (GH_TOKEN, read access to pull requests).
set -euo pipefail
export LC_ALL=C.UTF-8

: "${REPO:?owner/name of the repository}" "${PR:?number of the pull request}"

# The number a file reserves, prefixed by its kind so a migration V75 and a
# decision record 0075 never meet: « migration:122 », « decision:0075 ».
reservation() {
  local name; name="$(basename "$1")"
  case "$1" in
    src/main/resources/db/migration/*)
      [[ "$name" =~ ^V([0-9]+)__ ]] && echo "migration:$((10#${BASH_REMATCH[1]}))" ;;
    docs/decisions/*)
      [[ "$name" =~ ^([0-9]{4})- ]] && echo "decision:${BASH_REMATCH[1]}" ;;
    *) return 1 ;;
  esac
}

added_files() {
  gh api --paginate "repos/$REPO/pulls/$1/files?per_page=100" \
    --jq '.[] | select(.status == "added") | .filename'
}

# Every call is assigned before it is read: in a process substitution its
# failure would be ignored, and an unreadable API would read « no collision ».
own_files="$(added_files "$PR")"
open_prs="$(gh api --paginate "repos/$REPO/pulls?state=open&per_page=100" --jq '.[] | [.number, .title] | @tsv')"

declare -A mine=()
while IFS= read -r file; do
  if key="$(reservation "$file")"; then
    mine["$key"]="$file"
  fi
done <<< "$own_files"

if [[ "${#mine[@]}" -eq 0 ]]; then
  echo "check-open-pr-numbers: #$PR adds no migration and no decision record"; exit 0
fi

collisions=0
while IFS=$'\t' read -r other title; do
  [[ "$other" == "$PR" ]] && continue
  other_files="$(added_files "$other")"
  while IFS= read -r file; do
    key="$(reservation "$file")" || continue
    if [[ -n "${mine[$key]:-}" ]]; then
      collisions=$((collisions + 1))
      echo "::warning file=${mine[$key]}::${key%%:*} ${key#*:} is also added by #$other ($title) as $file — whichever merges second renumbers"
    fi
  done <<< "$other_files"
done <<< "$open_prs"

echo "check-open-pr-numbers: ${#mine[@]} number(s) reserved by #$PR, $collisions shared with another open pull request"
