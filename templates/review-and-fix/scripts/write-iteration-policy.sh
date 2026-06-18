#!/usr/bin/env bash
set -euo pipefail

if command -v python3 >/dev/null 2>&1; then
  python3 - "${FORGE_INPUT_REQUEST:-}" "$FORGE_OUTPUT_ITERATION_POLICY" <<'PY'
import json
import sys

request_path, output_path = sys.argv[1:3]
MAX_LOOP_BUDGET = 2**32 - 1
MAX_TOTAL_ROUNDS = MAX_LOOP_BUDGET + 1

policy = {
    "policy_id": "review_and_fix_iteration_v1",
    "goal": "Continue review-and-fix rounds only while the next accepted slice is still materially valuable.",
    "max_rounds": 4,
    "max_round_continuations": 3,
    "max_stalled_rounds": 2,
    "checkpoint_interval": 2,
    "continue_requires_material_progress": True,
    "checkpoint_when": {
        "round_multiple_of": 2,
        "scope_expands": True,
        "replan_count_reaches": 2,
    },
    "escalate_when": {
        "stalled_rounds_reaches": 2,
        "new_high_severity_finding": True,
        "requires_human_judgment": True,
    },
}


def require_object(value, field):
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise SystemExit(f"{field} must be a JSON object")
    return value


def require_int(value, field, minimum, maximum=None):
    if isinstance(value, bool) or not isinstance(value, int):
        raise SystemExit(f"{field} must be an integer")
    if value < minimum:
        raise SystemExit(f"{field} must be >= {minimum}")
    if maximum is not None and value > maximum:
        raise SystemExit(f"{field} must be <= {maximum}")
    return value


if request_path:
    with open(request_path, encoding="utf-8") as handle:
        request = json.load(handle)
    request = require_object(request, "request")
    workflow_config = require_object(request.get("workflow_config"), "workflow_config")
    overrides = require_object(
        workflow_config.get("review_and_fix"),
        "workflow_config.review_and_fix",
    )
    if "max_rounds" in overrides:
        policy["max_rounds"] = require_int(
            overrides["max_rounds"],
            "workflow_config.review_and_fix.max_rounds",
            1,
            MAX_TOTAL_ROUNDS,
        )
        policy["max_round_continuations"] = max(0, policy["max_rounds"] - 1)

with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(policy, handle, indent=2)
    handle.write("\n")
PY
  exit 0
fi

cat > "$FORGE_OUTPUT_ITERATION_POLICY" <<'EOF'
{
  "policy_id": "review_and_fix_iteration_v1",
  "goal": "Continue review-and-fix rounds only while the next accepted slice is still materially valuable.",
  "max_rounds": 4,
  "max_round_continuations": 3,
  "max_stalled_rounds": 2,
  "checkpoint_interval": 2,
  "continue_requires_material_progress": true,
  "checkpoint_when": {
    "round_multiple_of": 2,
    "scope_expands": true,
    "replan_count_reaches": 2
  },
  "escalate_when": {
    "stalled_rounds_reaches": 2,
    "new_high_severity_finding": true,
    "requires_human_judgment": true
  }
}
EOF
