#!/usr/bin/env bash
# PreToolUse hook (Edit|Write): blocks creating new root-level *.md files and
# any planning/notes/tracking markdown files, per AGENTS.md's doc rules:
# "Don't create planning/notes/tracking Markdown files in the repository" and
# "all technical documentation lives in docs/".
set -euo pipefail

input="$(cat)"
tool_name="$(jq -r '.tool_name // empty' <<<"$input")"
file_path="$(jq -r '.tool_input.file_path // empty' <<<"$input")"

[ "$tool_name" = "Write" ] || exit 0
[ -n "$file_path" ] || exit 0

case "$file_path" in
  *.md|*.MD|*.Md) ;;
  *) exit 0 ;;
esac

# Only new files are a concern; editing an existing tracked doc is fine.
[ -e "$file_path" ] && exit 0

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
case "$file_path" in
  /*) abs_path="$file_path" ;;
  *) abs_path="$repo_root/$file_path" ;;
esac
rel_path="${abs_path#"$repo_root"/}"

lower_path="$(printf '%s' "$rel_path" | tr '[:upper:]' '[:lower:]')"

# New root-level markdown file (README.md already exists and is edited via Edit, not Write).
if [ "${rel_path#*/}" = "$rel_path" ]; then
  echo "Refusing to create root-level markdown file '$rel_path': AGENTS.md reserves the repo root for README.md only — technical docs go under docs/ (with a row added to docs/README.md), not a new root file." >&2
  exit 2
fi

# Planning/notes/tracking docs anywhere in the repo.
case "$lower_path" in
  *notes*|*plan*|*todo*|*scratch*|*tracking*)
    echo "Refusing to create '$rel_path': looks like a planning/notes/tracking markdown file. AGENTS.md: don't create planning/notes/tracking Markdown files in the repository." >&2
    exit 2
    ;;
esac

exit 0
