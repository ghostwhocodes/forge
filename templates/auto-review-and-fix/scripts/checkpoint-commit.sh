#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

require_command git "accepted-round checkpointing" || exit 1

copy_last_remediated_findings() {
  if [ -n "${FORGE_OUTPUT_LAST_REMEDIATED_FINDINGS:-}" ] \
    && [ -n "${FORGE_INPUT_ACCEPTED_REVIEW_FINDINGS:-}" ] \
    && [ -f "$FORGE_INPUT_ACCEPTED_REVIEW_FINDINGS" ]; then
    cp "$FORGE_INPUT_ACCEPTED_REVIEW_FINDINGS" "$FORGE_OUTPUT_LAST_REMEDIATED_FINDINGS"
  fi
}

FORGE_SCRIPT_LAUNCH_CWD=$(current_physical_pwd)

repo_root=$(request_repo_root "$FORGE_INPUT_REQUEST")
if ! is_git_worktree "$repo_root"; then
  echo "auto-review-and-fix checkpoint commit requires a git repository at request.repo_root" >&2
  exit 1
fi

cd "$repo_root"

if ! git_repo_has_changes "$repo_root"; then
  prev_sha=""
  notes="The verification step accepted the round, but there were no repository changes left to checkpoint."
  if git_head_exists "$repo_root"; then
    prev_sha=$(git rev-parse HEAD)
  else
    notes="The verification step accepted the round, but there were no repository changes left to checkpoint and the repository had no commits yet."
  fi
  new_section="## Accepted Round Checkpoint

- Kind: accepted-round
- Status: skipped
- Commit: \`$prev_sha\`
- Notes: $notes"
  history_markdown=$(append_markdown_section "$FORGE_INPUT_CHECKPOINT_HISTORY" "$new_section")
  printf '%s\n' "$history_markdown" > "$FORGE_OUTPUT_CHECKPOINT_HISTORY"
  cat > "$FORGE_OUTPUT_CHECKPOINT_RECORD" <<EOF
{
  "kind": "accepted-round",
  "status": "skipped",
  "commit_sha": "$prev_sha",
  "message": "accepted round had no repository changes to checkpoint"
}
EOF
  copy_last_remediated_findings
  exit 0
fi

if git_has_dirty_submodule_worktrees "$repo_root"; then
  fail_dirty_submodule_checkpoint "$repo_root" "accepted-round checkpoint"
  exit 1
fi

git_stage_checkpoint_changes "$repo_root"
git_checkpoint_commit "$repo_root" "forge auto-review-and-fix: accepted round checkpoint"
sha=$(git rev-parse HEAD)

new_section="## Accepted Round Checkpoint

- Kind: accepted-round
- Status: committed
- Commit: \`$sha\`
- Message: \`forge auto-review-and-fix: accepted round checkpoint\`
- Findings: See the accepted review findings artifact for the exact bounded slice covered by this checkpoint."
history_markdown=$(append_markdown_section "$FORGE_INPUT_CHECKPOINT_HISTORY" "$new_section")
printf '%s\n' "$history_markdown" > "$FORGE_OUTPUT_CHECKPOINT_HISTORY"

cat > "$FORGE_OUTPUT_CHECKPOINT_RECORD" <<EOF
{
  "kind": "accepted-round",
  "status": "committed",
  "commit_sha": "$sha",
  "message": "forge auto-review-and-fix: accepted round checkpoint"
}
EOF
copy_last_remediated_findings
