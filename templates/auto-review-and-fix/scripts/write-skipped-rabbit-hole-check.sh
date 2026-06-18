#!/usr/bin/env bash
set -euo pipefail

summary_path=$FORGE_OUTPUT_RABBIT_HOLE_CHECK_SUMMARY
report_path=$FORGE_OUTPUT_RABBIT_HOLE_CHECK_REPORT
gate_result_path=$FORGE_INPUT_RABBIT_HOLE_GATE_RESULT

if command -v python3 >/dev/null 2>&1; then
  python3 - "$gate_result_path" "$summary_path" "$report_path" <<'PY'
import json
import sys

gate_result_path, summary_path, report_path = sys.argv[1:4]

with open(gate_result_path, encoding="utf-8") as handle:
    gate_result = json.load(handle)

rationale = gate_result.get(
    "rationale",
    "The latest accepted round did not cross the rabbit-hole check thresholds.",
)
follow_up = gate_result.get("recommended_human_follow_up", "none")

summary = "\n".join(
    [
        "# Rabbit-Hole Check",
        "",
        "- Status: skipped",
        f"- Reason: {rationale}",
        f"- Recommended follow-up: {follow_up}",
        "",
    ]
)

report_lines = [
    "## Rabbit-hole check",
    "",
    "The rabbit-hole investigation subrun was skipped for this accepted round.",
    "",
    f"Reason: {rationale}",
]

if follow_up != "none":
    report_lines.extend(
        [
            "",
            f"Recommended human follow-up if the loop stalls later: `{follow_up}`.",
        ]
    )

report_lines.extend(
    [
        "",
        "This placeholder report keeps downstream evaluation and summary inputs stable when no design-drift investigation was necessary.",
        "",
    ]
)

with open(summary_path, "w", encoding="utf-8") as handle:
    handle.write(summary)
with open(report_path, "w", encoding="utf-8") as handle:
    handle.write("\n".join(report_lines))
PY
  exit 0
fi

if command -v jq >/dev/null 2>&1; then
  rationale=$(jq -r '.rationale // "The latest accepted round did not cross the rabbit-hole check thresholds."' "$gate_result_path")
  follow_up=$(jq -r '.recommended_human_follow_up // "none"' "$gate_result_path")
  cat > "$summary_path" <<EOF
# Rabbit-Hole Check

- Status: skipped
- Reason: $rationale
- Recommended follow-up: $follow_up
EOF
  cat > "$report_path" <<EOF
## Rabbit-hole check

The rabbit-hole investigation subrun was skipped for this accepted round.

Reason: $rationale

EOF
  if [ "$follow_up" != "none" ]; then
    printf 'Recommended human follow-up if the loop stalls later: `%s`.\n\n' "$follow_up" >> "$report_path"
  fi
  cat >> "$report_path" <<'EOF'
This placeholder report keeps downstream evaluation and summary inputs stable when no design-drift investigation was necessary.
EOF
  exit 0
fi

echo "auto-review-and-fix skipped rabbit-hole reporting requires jq or python3; install the missing dependency and retry" >&2
exit 1
