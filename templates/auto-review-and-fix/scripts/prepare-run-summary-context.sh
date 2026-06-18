#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

write_run_summary_context "$FORGE_RUN_DIR" > "$FORGE_OUTPUT_RUN_SUMMARY_CONTEXT"
