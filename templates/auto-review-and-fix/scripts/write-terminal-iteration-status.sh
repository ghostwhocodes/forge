#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

output_dir=$(dirname -- "$FORGE_OUTPUT_ITERATION_STATUS")
tmp_output=$(mktemp "$output_dir/iteration-status.XXXXXX")
trap 'rm -f -- "$tmp_output"' EXIT

write_terminal_iteration_status \
  "$FORGE_INPUT_ITERATION_STATUS" \
  "$FORGE_INPUT_ROUND_POLICY" \
  "$FORGE_INPUT_ROUND_GATE_RESULT" \
  > "$tmp_output"

mv -- "$tmp_output" "$FORGE_OUTPUT_ITERATION_STATUS"
