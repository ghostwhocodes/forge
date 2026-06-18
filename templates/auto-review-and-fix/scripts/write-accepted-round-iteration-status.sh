#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

write_accepted_round_iteration_status \
  "$FORGE_INPUT_ITERATION_STATUS" \
  "$FORGE_INPUT_ROUND_POLICY" \
  "$FORGE_INPUT_ACCEPTED_REVIEW_FINDINGS" \
  "$FORGE_INPUT_REVIEW_AUDIT_RESULT" \
  > "$FORGE_OUTPUT_ACCEPTED_ROUND_ITERATION_STATUS"
