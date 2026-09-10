#!/usr/bin/env bash
# Checks the commit messages of a branch against AGENTS.md's conventions:
# a subject under 72 characters with a conventional prefix and no trailing
# period, and no trailer but Signed-off-by and Co-Authored-By.
#
#   scripts/check-commits.sh origin/main
#
# Run by the Tests workflow on every pull request (issue #392, D8): the rules
# were written down and held by nobody — 14 of the last 50 commits carried a
# Claude-Session trailer, 70 of 300 subjects passed 72 characters.
set -euo pipefail
# Characters, not bytes: « épingler » is eight characters whatever the locale
# of the runner, and ${#subject} only counts them so under a UTF-8 locale.
export LC_ALL=C.UTF-8

base="${1:-origin/main}"
prefixes='^(feat|fix|refactor|docs|test|chore|build|ci|perf|style|revert)(\([^)]+\))?!?: '
status=0

while IFS= read -r sha; do
  subject="$(git log -1 --format=%s "$sha")"
  short="$(git log -1 --format=%h "$sha")"
  if [ "${#subject}" -ge 72 ]; then
    echo "$short: subject has ${#subject} characters, the limit is 71 — « $subject »"; status=1
  fi
  if ! [[ "$subject" =~ $prefixes ]]; then
    echo "$short: subject has no conventional prefix (feat:, fix:, refactor:, docs:, test:, chore:, build:, ci:…) — « $subject »"; status=1
  fi
  if [[ "$subject" == *. ]]; then
    echo "$short: subject ends with a period — « $subject »"; status=1
  fi
  # Trailers are the `Key: value` lines of the last paragraph; git parses them.
  while IFS= read -r trailer; do
    key="${trailer%%:*}"
    case "$key" in
      Signed-off-by|Co-Authored-By|Co-authored-by) ;;
      "") ;;
      *) echo "$short: trailer « $key » is not one of the two allowed (Signed-off-by, Co-Authored-By)"; status=1 ;;
    esac
  done < <(git log -1 --format=%B "$sha" | git interpret-trailers --parse)
done < <(git rev-list --no-merges "$base"..HEAD)

if [ "$status" -eq 0 ]; then
  echo "commit messages: $(git rev-list --count --no-merges "$base"..HEAD) checked, all conform"
fi
exit "$status"
