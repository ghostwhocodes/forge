#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

write_review_consensus_request \
  "$FORGE_INPUT_REQUEST" \
  "$FORGE_INPUT_ROUND_POLICY" \
  "$FORGE_INPUT_ITERATION_STATUS" \
  "$FORGE_INPUT_CHECKPOINT_HISTORY" \
  "$FORGE_INPUT_GOVERNANCE_CONTEXT" \
  > "$FORGE_OUTPUT_REVIEW_CONSENSUS_REQUEST"
