#!/usr/bin/env bash
# Checks that a release tag raises the version as its commit subjects ask, and
# that a pre-release vX.Y.Z-rc.N comes before its final version and after the
# rc of the same number. Called by release.yml, played by
# test_check_release_version.py.
#
#   bash .github/scripts/check-release-version.sh v4.0.0-rc.1
#
# Deliberately no `set -e`: every failure must say why through `::error::`, and
# a pipeline that legitimately matches nothing (a first rc has no sibling) is
# not one. Under `bash -e` with `pipefail`, that empty `grep` used to end the
# step with exit code 1 and no message.
set -uo pipefail

TAG="${1:?usage: check-release-version.sh vX.Y.Z[-rc.N]}"
faute=0

if ! [[ "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-rc\.([1-9][0-9]*))?$ ]]; then
  echo "::error::$TAG n'est pas un tag de release vX.Y.Z ni de préversion vX.Y.Z-rc.N"
  exit 1
fi
na="${BASH_REMATCH[1]}" nb="${BASH_REMATCH[2]}" nc="${BASH_REMATCH[3]}" rc="${BASH_REMATCH[5]}"
finale="v$na.$nb.$nc"

# The STABLE tag preceding THIS one in history, not the most recent of the
# repository: on `release/1.2`, a v1.2.1 follows v1.2.0, not v1.3.0.
# Pre-releases are excluded: a v4.0.0-rc.2 compares with v3.5.0, like v4.0.0
# does, not with its own rc.1. Same reading as `--current` in release.yml.
precedent="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude 'v*-*' "$TAG^" 2>/dev/null || true)"  # none: first release

if [ -z "$precedent" ]; then
  echo "::notice::$TAG est la première release de sa ligne : pas de montée à vérifier"
else
  if ! sujets="$(git log --format='%s' "$precedent..$TAG")"; then
    echo "::error::impossible de lire les commits entre $precedent et $TAG"
    exit 1
  fi
  demande=1  # 1 PATCH, 2 MINOR, 3 MAJOR — cf. site/src/docs/installer/versioning.md § 2
  # Here-strings, not `printf | grep -q`: grep leaving early would SIGPIPE
  # printf, and pipefail would read that as "no match".
  if grep -qE '^[a-z]+(\([^)]*\))?!:' <<< "$sujets"; then
    demande=3
  elif grep -qE '^feat' <<< "$sujets"; then
    demande=2
  fi

  IFS=. read -r pa pb pc <<< "${precedent#v}"
  if   [ "$na" -gt "$pa" ]; then pose=3
  elif [ "$na" -eq "$pa" ] && [ "$nb" -gt "$pb" ]; then pose=2
  elif [ "$na" -eq "$pa" ] && [ "$nb" -eq "$pb" ] && [ "$nc" -gt "$pc" ]; then pose=1
  else pose=0
  fi

  noms=(aucune PATCH MINOR MAJOR)
  if [ "$pose" -eq 0 ]; then
    echo "::error::$TAG n'est pas supérieur à $precedent"
    faute=1
  elif [ "$pose" -lt "$demande" ]; then
    echo "::error::${noms[$pose]} posée entre $precedent et $TAG, mais les sujets demandaient ${noms[$demande]} (site/src/docs/installer/versioning.md § 2)"
    faute=1
  fi
fi

# A rc comes before its final version and after the rc of the same number: a
# v4.0.0-rc.1 set after v4.0.0, or after a v4.0.0-rc.2, would be a "newer"
# image that is not.
if [ -n "$rc" ]; then
  if git rev-parse -q --verify "refs/tags/$finale" >/dev/null; then
    echo "::error::$finale est déjà publiée : $TAG ne peut plus la précéder"
    faute=1
  fi
  plus_haute=0
  while read -r autre; do
    n="${autre##*-rc.}"
    if [[ "$autre" != "$TAG" && "$n" =~ ^[0-9]+$ ]] && [ "$n" -gt "$plus_haute" ]; then
      plus_haute="$n"
    fi
  done < <(git tag --list "$finale-rc.*")
  if [ "$plus_haute" -gt 0 ] && [ "$rc" -le "$plus_haute" ]; then
    echo "::error::$TAG ne suit pas $finale-rc.$plus_haute : le numéro de rc doit croître"
    faute=1
  fi
fi

exit "$faute"
