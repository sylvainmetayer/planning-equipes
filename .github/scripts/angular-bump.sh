#!/usr/bin/env bash
# Prints « true » when a Renovate branch bumps an @angular/ package in
# src/main/webui/package.json and does not carry the migration commit yet, and
# « false » otherwise. Run from a checkout of the branch with its full history.
#
#   .github/scripts/angular-bump.sh main
#
# Read by angular-renovate.yml, which acts on « true », and by
# licences-renovate.yml, which then stands aside: the former regenerates the
# licence inventory in its own patch, and two workflows pushing to the same
# branch is the race that made them refuse each other.
#
# The diff runs from the merge base (`...`), not from the tip of the base: a
# base that has moved would make an Angular bump already merged look like this
# branch's. No `pipefail` and no `grep -q` on a pipe: the early exit of grep
# would fail the pipeline on SIGPIPE and turn a match into « false ».
set -eu

base="${1:?usage: angular-bump.sh <base branch>}"
git fetch --no-tags --quiet origin "$base"

subjects="$(git log "origin/$base..HEAD" --format=%s)"
if grep -qF 'chore(deps): migrer Angular avec ng update' <<<"$subjects"; then
  echo false
  exit 0
fi

diff="$(git diff "origin/$base...HEAD" -- src/main/webui/package.json)"
if grep -qE '^[-+][[:space:]]+"@angular/' <<<"$diff"; then
  echo true
else
  echo false
fi
