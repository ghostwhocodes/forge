#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

repo_root=$(request_repo_root "$FORGE_INPUT_REQUEST")
if [ -z "$repo_root" ] || [ ! -d "$repo_root" ]; then
  echo "auto-review-and-fix governance context freeze requires request.repo_root to name an existing directory" >&2
  exit 1
fi

governance_context_json "$repo_root" > "$FORGE_OUTPUT_GOVERNANCE_CONTEXT"
