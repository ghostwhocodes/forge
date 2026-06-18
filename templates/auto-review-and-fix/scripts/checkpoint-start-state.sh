#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

require_command git "baseline checkpointing" || exit 1

FORGE_SCRIPT_LAUNCH_CWD=$(current_physical_pwd)

repo_root=$(request_repo_root "$FORGE_INPUT_REQUEST")
if ! is_git_worktree "$repo_root"; then
  echo "auto-review-and-fix baseline checkpoint requires a git repository at request.repo_root" >&2
  exit 1
fi

cd "$repo_root"

history_title="# Checkpoint History"
if git_repo_has_changes "$repo_root"; then
  if git_has_dirty_submodule_worktrees "$repo_root"; then
    fail_dirty_submodule_checkpoint "$repo_root" "baseline checkpoint"
    exit 1
  fi

  git_stage_checkpoint_changes "$repo_root"
  git_checkpoint_commit "$repo_root" "forge auto-review-and-fix: baseline checkpoint"
  sha=$(git rev-parse HEAD)
  section="## Baseline Checkpoint

- Kind: baseline
- Status: committed
- Commit: \`$sha\`
- Message: \`forge auto-review-and-fix: baseline checkpoint\`
- Notes: Repository started dirty, so the workflow created a baseline checkpoint before unattended remediation began."
  printf '%s\n\n%s\n' "$history_title" "$section" > "$FORGE_OUTPUT_CHECKPOINT_HISTORY"
  cat > "$FORGE_OUTPUT_CHECKPOINT_RECORD" <<EOF
{
  "kind": "baseline",
  "status": "committed",
  "commit_sha": "$sha",
  "message": "forge auto-review-and-fix: baseline checkpoint"
}
EOF
else
  sha=""
  notes="Repository was already clean, so no baseline commit was needed."
  if git_head_exists "$repo_root"; then
    sha=$(git rev-parse HEAD)
  else
    notes="Repository was already clean and had no commits yet, so no baseline commit was needed."
  fi
  section="## Baseline Checkpoint

- Kind: baseline
- Status: skipped
- Commit: \`${sha}\`
- Notes: ${notes}"
  printf '%s\n\n%s\n' "$history_title" "$section" > "$FORGE_OUTPUT_CHECKPOINT_HISTORY"
  cat > "$FORGE_OUTPUT_CHECKPOINT_RECORD" <<EOF
{
  "kind": "baseline",
  "status": "skipped",
  "commit_sha": "$sha",
  "message": "baseline checkpoint not needed"
}
EOF
fi
