#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

require_command git "evidence collection" || exit 1

FORGE_SCRIPT_LAUNCH_CWD=$(current_physical_pwd)

repo_root=$(request_repo_root "$FORGE_INPUT_REQUEST")
if ! is_git_worktree "$repo_root"; then
  printf '{"status":"blocked","reason":"request.repo_root is missing or is not a git repository"}\n' \
    > "$FORGE_OUTPUT_EVIDENCE"
  exit 0
fi

cd "$repo_root"
git_checkpoint_pathspec_args "$repo_root"

if git_head_exists "$repo_root"; then
  diff_stat=$(git diff --stat HEAD "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)
  tracked_changed_files=$(git diff --name-only HEAD "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)
else
  diff_stat=$(git diff --cached --stat "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)
  tracked_changed_files=$(git diff --cached --name-only "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)
fi
untracked_files=$(git ls-files --others --exclude-standard "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)
changed_files=$(
  {
    printf '%s\n' "$tracked_changed_files"
    printf '%s\n' "$untracked_files"
  } | sed '/^$/d' | sort -u
)

if [ -n "$untracked_files" ]; then
  untracked_section=$(printf 'Untracked files:\n%s' "$untracked_files")
  if [ -n "$diff_stat" ]; then
    diff_stat=$(printf '%s\n\n%s' "$diff_stat" "$untracked_section")
  else
    diff_stat=$untracked_section
  fi
fi

printf '{\n  "status": "collected",\n  "diff_stat": "%s",\n  "changed_files": "%s"\n}\n' \
  "$(json_escape "$diff_stat")" \
  "$(json_escape "$changed_files")" \
  > "$FORGE_OUTPUT_EVIDENCE"
