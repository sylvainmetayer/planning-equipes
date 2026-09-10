#!/usr/bin/env bash
# PreToolUse hook (Edit|Write): blocks editing an already-committed Flyway
# migration under src/main/resources/db/migration/V*.sql.
# AGENTS.md: "Schema change = new versioned file; never edit an applied migration."
set -euo pipefail

input="$(cat)"
tool_name="$(jq -r '.tool_name // empty' <<<"$input")"
file_path="$(jq -r '.tool_input.file_path // empty' <<<"$input")"

case "$tool_name" in Edit|Write) ;; *) exit 0 ;; esac
[ -n "$file_path" ] || exit 0

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
case "$file_path" in
  /*) abs_path="$file_path" ;;
  *) abs_path="$repo_root/$file_path" ;;
esac
rel_path="${abs_path#"$repo_root"/}"

case "$rel_path" in
  src/main/resources/db/migration/V*.sql) ;;
  *) exit 0 ;;
esac

if git -C "$repo_root" ls-files --error-unmatch -- "$rel_path" >/dev/null 2>&1; then
  echo "Refusing to edit '$rel_path': it's an already-committed Flyway migration. Schema changes must be a NEW versioned file (Vn+1__*.sql) — never edit an applied migration (see AGENTS.md)." >&2
  exit 2
fi

exit 0
