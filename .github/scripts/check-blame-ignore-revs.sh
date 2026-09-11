#!/usr/bin/env bash
# Checks that every revision of .git-blame-ignore-revs is a commit this clone
# knows and an ancestor of HEAD.
#
#   .github/scripts/check-blame-ignore-revs.sh
#
# The repository merges in rebase, so the SHA a reformatting had on its branch
# is not the one main received (#467): listed by mistake, it exists on the
# author's machine and nowhere else, and `git blame` ignores an unknown full
# SHA without a word — the reformatting is back on every line. Needs the full
# history (fetch-depth: 0); DocumentationStructuralTest holds the form alone.
set -euo pipefail

status=0
while IFS= read -r line; do
  sha="${line%%#*}"
  sha="${sha//[[:space:]]/}"
  [ -n "$sha" ] || continue
  if ! git cat-file -e "$sha^{commit}" 2>/dev/null; then
    echo ".git-blame-ignore-revs: $sha is not a commit this clone knows"; status=1
  elif ! git merge-base --is-ancestor "$sha" HEAD; then
    echo ".git-blame-ignore-revs: $sha is not an ancestor of HEAD — a branch SHA, replaced by the rebase?"; status=1
  fi
done < .git-blame-ignore-revs

if [ "$status" -eq 0 ]; then
  echo ".git-blame-ignore-revs: every listed revision is an ancestor of HEAD"
fi
exit "$status"
