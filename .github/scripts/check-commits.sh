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

# Fails closed. `git rev-list` in a process substitution has its exit code
# ignored by `set -e`, so an unknown base — the base branch of a stacked PR
# deleted between its creation and the run — used to print « 0 checked, all
# conform » and exit 0 without having read a single commit.
if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  echo "check-commits: base « $base » is not a commit this clone knows"; exit 1
fi
count="$(git rev-list --count --no-merges "$base"..HEAD)"

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
  # `git interpret-trailers --parse` reads the last paragraph only, and reads
  # nothing when that paragraph mixes prose and trailers: the one trailer this
  # repository forbids survived in both forms (1 of 40 on main). Scanned in the
  # whole body, then — a line is a line.
  if git log -1 --format=%b "$sha" | grep -qiE '^Claude-Session:'; then
    echo "$short: carries a Claude-Session trailer, which AGENTS.md forbids"; status=1
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
  echo "commit messages: $count checked, all conform"
fi
exit "$status"
