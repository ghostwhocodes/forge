#!/usr/bin/env bash
set -euo pipefail

# Collect execution evidence after implementation.
# Reads the repo root from the request artifact and gathers git diff stats
# and basic build/test signals if available.
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

repo_root=""
if [ -n "${FORGE_INPUT_REQUEST:-}" ] && [ -f "$FORGE_INPUT_REQUEST" ]; then
  # Try to extract repo_root from request JSON
  if command -v jq &>/dev/null; then
    repo_root=$(jq -r '.repo_root // empty' "$FORGE_INPUT_REQUEST" 2>/dev/null || true)
  fi
fi

diff_stat=""
if [ -n "$repo_root" ] && [ -d "$repo_root/.git" ]; then
  diff_stat=$(cd "$repo_root" && git diff --stat HEAD 2>/dev/null || true)
fi

if [ -n "$diff_stat" ]; then
  printf '{"status":"collected","diff_stat":"%s"}\n' \
    "$(json_escape "$diff_stat")" \
    > "$FORGE_OUTPUT_EVIDENCE"
else
  printf '{"status":"collected"}\n' > "$FORGE_OUTPUT_EVIDENCE"
fi
