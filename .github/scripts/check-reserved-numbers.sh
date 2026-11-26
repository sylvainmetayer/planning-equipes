#!/usr/bin/env bash
# Checks the numbers a branch adds against those its base already holds:
#   - a decision record NNNN added by the branch must carry a number no record
#     of the base carries, or the check fails;
#   - a migration V<n> added by the branch must end up strictly above the
#     highest version of the base — Flyway refuses an out-of-order version once
#     the higher one is applied, so « free » is not enough. Nobody reserves a
#     number: a branch takes the next one when it pushes, and the coordinator
#     runs renumeroter-migration.py right before merging it, which moves the
#     migration and its references above the base. A migration that is no
#     longer above is therefore a warning here, never a failure: failing would
#     cost every open pull request a push and a CI run each time another merges.
#     STRICT_MIGRATIONS=1 makes it a failure again: the push to main runs it so
#     against the tip main had before, the net under a forgotten renumbering,
#     before the image ships a migration a database cannot apply.
#
#   scripts/check-reserved-numbers.sh origin/main
#   STRICT_MIGRATIONS=1 scripts/check-reserved-numbers.sh <main before the push>
#
# Compared with the tip of the base, not with the fork point: two pull
# requests that both took the next number are told apart the moment the first
# one merges, at the next run of the second, rather than after its own merge,
# when FlywayMigrationsFrozenTest or Flyway itself on main would see it.
# The other open pull requests are .github/workflows/numeros-reserves.yml.
set -euo pipefail
export LC_ALL=C.UTF-8

base="${1:-origin/main}"
migrations='src/main/resources/db/migration'
decisions='docs/decisions'
status=0

if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  echo "check-reserved-numbers: base « $base » is not a commit this clone knows"; exit 1
fi
fork="$(git merge-base "$base" HEAD)"
# Every listing is assigned before it is read: inside `< <(…)` its failure
# would be ignored, and an unreadable tree would read « nothing reserved ».
base_migrations="$(git ls-tree -r --name-only "$base" -- "$migrations")"
base_records="$(git ls-tree -r --name-only "$base" -- "$decisions")"
# --no-renames: a file deleted and a near-copy added is still an addition.
added="$(git diff --name-only --no-renames --diff-filter=A "$fork" HEAD -- "$migrations" "$decisions")"

# The version of a migration file name (V122__x.sql → 122), nothing for a
# repeatable or an unrelated file.
migration_version() {
  local file="$1"
  [[ "$(basename "$file")" =~ ^V([0-9]+)__ ]] && echo "$((10#${BASH_REMATCH[1]}))"
}
# The number of a decision record (0074-x.md → 0074), nothing for README.md.
decision_number() {
  local file="$1"
  [[ "$(basename "$file")" =~ ^([0-9]{4})- ]] && echo "${BASH_REMATCH[1]}"
}

highest=0
highest_file=''
declare -A base_decisions=()
while IFS= read -r file; do
  if v="$(migration_version "$file")" && (( v > highest )); then
    highest="$v"; highest_file="$file"
  fi
done <<< "$base_migrations"
while IFS= read -r file; do
  if n="$(decision_number "$file")"; then
    base_decisions["$n"]="$file"
  fi
done <<< "$base_records"

checked=0
while IFS= read -r file; do
  if v="$(migration_version "$file")"; then
    checked=$((checked + 1))
    if (( v <= highest )) && [[ "${STRICT_MIGRATIONS:-}" == 1 ]]; then
      echo "$file: version $v is not above the highest of $base, $highest_file — renumeroter-migration.py was not run before the merge"
      status=1
    elif (( v <= highest )); then
      echo "::warning file=$file::version $v is not above the highest of $base, $highest_file — renumeroter-migration.py moves it at merge"
    fi
  elif n="$(decision_number "$file")"; then
    checked=$((checked + 1))
    if [[ -n "${base_decisions[$n]:-}" ]]; then
      echo "$file: number $n is already ${base_decisions[$n]} on $base"
      status=1
    fi
  fi
done <<< "$added"

if [[ "$status" -eq 0 ]]; then
  echo "check-reserved-numbers: $checked added migration(s) or decision record(s), no decision record takes a number of $base"
fi
exit "$status"
