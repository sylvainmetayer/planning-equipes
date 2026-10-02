#!/usr/bin/env bash
# Refuses a branch that adds a Flyway migration or a decision record under a
# number its base already holds:
#   - a migration V<n> added by the branch must be strictly above the highest
#     version of the base — Flyway refuses an out-of-order version once the
#     higher one is applied, so « free » is not enough;
#   - a decision record NNNN added by the branch must carry a number no record
#     of the base carries.
#
#   scripts/check-reserved-numbers.sh origin/main
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
migration_version() { [[ "$(basename "$1")" =~ ^V([0-9]+)__ ]] && echo "$((10#${BASH_REMATCH[1]}))"; }
# The number of a decision record (0074-x.md → 0074), nothing for README.md.
decision_number() { [[ "$(basename "$1")" =~ ^([0-9]{4})- ]] && echo "${BASH_REMATCH[1]}"; }

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
    if (( v <= highest )); then
      echo "$file: version $v is not above the highest of $base, $highest_file — take V$((highest + 1)) or above"
      status=1
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
  echo "check-reserved-numbers: $checked added migration(s) or decision record(s), none takes a number of $base"
fi
exit "$status"
