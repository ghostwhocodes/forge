#!/usr/bin/env bash
set -euo pipefail

cat > "$FORGE_OUTPUT_QUALITY_POLICY" <<'EOF'
{
  "blocking_threshold": "medium",
  "max_review_cycles": 3,
  "block_on_low": false
}
EOF
