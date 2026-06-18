#!/usr/bin/env bash
set -euo pipefail

if command -v python3 >/dev/null 2>&1; then
  python3 - \
    "${FORGE_INPUT_REQUEST:-}" \
    "$FORGE_OUTPUT_ROUND_POLICY" \
    "$FORGE_OUTPUT_ITERATION_STATUS" <<'PY'
import json
import sys

request_path, round_policy_path, iteration_status_path = sys.argv[1:4]
MAX_DERIVED_TOTAL_ROUNDS = 2**32

policy = {
    "policy_id": "auto_review_and_fix_v1",
    "goal": "Allow unattended review and remediation rounds only while the next accepted slice still produces material convergence.",
    "max_rounds": 5,
    "max_round_continuations": 4,
    "max_fix_retries": 2,
    "max_replans": 2,
    "review_consensus": {
        "max_rejections": 2,
        "escalate_on_repeated_rejected_review": True,
    },
    "guardrails": {
        "enabled": True,
        "auto_rabbit_hole_check_after_round": 2,
        "prefer_complete_over_escalate_for_design_boundary": True,
    },
    "checkpoint_each_accepted_round": True,
    "baseline_checkpoint_when_dirty": True,
    "continue_requires_material_progress": True,
    "escalate_when": {
        "round_budget_exhausted": True,
        "new_high_severity_issue": True,
        "requires_human_judgment": True,
        "reviewer_ignored_auditor_pushback": True,
        "valid_issue_requires_complexity_expansion_without_explicit_authorization": True,
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


def require_bool(value, field):
    if not isinstance(value, bool):
        raise SystemExit(f"{field} must be a boolean")
    return value


def merge_ints(target, source, fields, field_prefix, minimum):
    for field in fields:
        if field in source:
            target[field] = require_int(source[field], f"{field_prefix}.{field}", minimum)


def merge_bools(target, source, fields, field_prefix):
    for field in fields:
        if field in source:
            target[field] = require_bool(source[field], f"{field_prefix}.{field}")


if request_path:
    with open(request_path, encoding="utf-8") as handle:
        request = json.load(handle)
    request = require_object(request, "request")
    workflow_config = require_object(request.get("workflow_config"), "workflow_config")
    overrides = require_object(
        workflow_config.get("auto_review_and_fix"),
        "workflow_config.auto_review_and_fix",
    )

    max_rounds_overridden = "max_rounds" in overrides
    merge_ints(
        policy,
        overrides,
        ["max_rounds", "max_round_continuations", "max_fix_retries", "max_replans"],
        "workflow_config.auto_review_and_fix",
        0,
    )
    if "max_rounds" in overrides:
        maximum = (
            None
            if "max_round_continuations" in overrides
            else MAX_DERIVED_TOTAL_ROUNDS
        )
        policy["max_rounds"] = require_int(
            overrides["max_rounds"],
            "workflow_config.auto_review_and_fix.max_rounds",
            1,
            maximum,
        )
    if max_rounds_overridden and "max_round_continuations" not in overrides:
        policy["max_round_continuations"] = max(0, policy["max_rounds"] - 1)

    review_consensus = require_object(
        overrides.get("review_consensus"),
        "workflow_config.auto_review_and_fix.review_consensus",
    )
    merge_ints(
        policy["review_consensus"],
        review_consensus,
        ["max_rejections"],
        "workflow_config.auto_review_and_fix.review_consensus",
        0,
    )
    merge_bools(
        policy["review_consensus"],
        review_consensus,
        ["escalate_on_repeated_rejected_review"],
        "workflow_config.auto_review_and_fix.review_consensus",
    )

    guardrails = require_object(
        overrides.get("guardrails"),
        "workflow_config.auto_review_and_fix.guardrails",
    )
    merge_ints(
        policy["guardrails"],
        guardrails,
        ["auto_rabbit_hole_check_after_round"],
        "workflow_config.auto_review_and_fix.guardrails",
        1,
    )
    merge_bools(
        policy["guardrails"],
        guardrails,
        ["enabled", "prefer_complete_over_escalate_for_design_boundary"],
        "workflow_config.auto_review_and_fix.guardrails",
    )

    merge_bools(
        policy,
        overrides,
        [
            "checkpoint_each_accepted_round",
            "baseline_checkpoint_when_dirty",
            "continue_requires_material_progress",
        ],
        "workflow_config.auto_review_and_fix",
    )

    escalate_when = require_object(
        overrides.get("escalate_when"),
        "workflow_config.auto_review_and_fix.escalate_when",
    )
    merge_bools(
        policy["escalate_when"],
        escalate_when,
        list(policy["escalate_when"].keys()),
        "workflow_config.auto_review_and_fix.escalate_when",
    )

iteration_status = {
    "round": 0,
    "max_rounds": policy["max_rounds"],
    "issues_closed_this_round": 0,
    "remaining_issue_count_estimate": 0,
    "new_issues_opened_this_round": 0,
    "stalled_rounds": 0,
    "cycle_detected": False,
    "rabbit_hole_detected": False,
    "design_boundary_hit": False,
    "same_component_rounds": 0,
    "last_component_purpose": None,
    "recommended_human_follow_up": "none",
    "round_history": [],
    "recommended_next_action": "continue",
    "reason": "seeded before the first automated round",
}

with open(round_policy_path, "w", encoding="utf-8") as handle:
    json.dump(policy, handle, indent=2)
    handle.write("\n")
with open(iteration_status_path, "w", encoding="utf-8") as handle:
    json.dump(iteration_status, handle, indent=2)
    handle.write("\n")
PY
  exit 0
fi

cat > "$FORGE_OUTPUT_ROUND_POLICY" <<'EOF'
{
  "policy_id": "auto_review_and_fix_v1",
  "goal": "Allow unattended review and remediation rounds only while the next accepted slice still produces material convergence.",
  "max_rounds": 5,
  "max_round_continuations": 4,
  "max_fix_retries": 2,
  "max_replans": 2,
  "review_consensus": {
    "max_rejections": 2,
    "escalate_on_repeated_rejected_review": true
  },
  "guardrails": {
    "enabled": true,
    "auto_rabbit_hole_check_after_round": 2,
    "prefer_complete_over_escalate_for_design_boundary": true
  },
  "checkpoint_each_accepted_round": true,
  "baseline_checkpoint_when_dirty": true,
  "continue_requires_material_progress": true,
  "escalate_when": {
    "round_budget_exhausted": true,
    "new_high_severity_issue": true,
    "requires_human_judgment": true,
    "reviewer_ignored_auditor_pushback": true,
    "valid_issue_requires_complexity_expansion_without_explicit_authorization": true
  }
}
EOF

cat > "$FORGE_OUTPUT_ITERATION_STATUS" <<'EOF'
{
  "round": 0,
  "max_rounds": 5,
  "issues_closed_this_round": 0,
  "remaining_issue_count_estimate": 0,
  "new_issues_opened_this_round": 0,
  "stalled_rounds": 0,
  "cycle_detected": false,
  "rabbit_hole_detected": false,
  "design_boundary_hit": false,
  "same_component_rounds": 0,
  "last_component_purpose": null,
  "recommended_human_follow_up": "none",
  "round_history": [],
  "recommended_next_action": "continue",
  "reason": "seeded before the first automated round"
}
EOF
