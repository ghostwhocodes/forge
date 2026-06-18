#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

request_json=$(cat "$FORGE_INPUT_REQUEST")
request_prefix=${request_json%\}}

cat > "$FORGE_OUTPUT_CYCLE_INVESTIGATION_REQUEST" <<EOF
${request_prefix},
  "cycle_context": {
    "iteration_policy": $(cat "$FORGE_INPUT_ITERATION_POLICY"),
    "latest_review_findings": $(cat "$FORGE_INPUT_REVIEW_FINDINGS"),
    "latest_review_summary_markdown": "$(json_escape_file "$FORGE_INPUT_REVIEW_SUMMARY")",
    "curated_findings": $(cat "$FORGE_INPUT_CURATED_FINDINGS"),
    "fix_plan_markdown": "$(json_escape_file "$FORGE_INPUT_FIX_PLAN")",
    "implementation_report_markdown": "$(json_escape_file "$FORGE_INPUT_IMPLEMENTATION_REPORT")",
    "evidence": $(cat "$FORGE_INPUT_EVIDENCE"),
    "judge_result": $(cat "$FORGE_INPUT_JUDGE_RESULT"),
    "iteration_status": $(cat "$FORGE_INPUT_ITERATION_STATUS")
  }
}
EOF
