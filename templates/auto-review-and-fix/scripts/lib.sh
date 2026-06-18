#!/usr/bin/env bash
# Sourced by command scripts; inherits caller's shell options.

json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/\\r}
  s=${s//$'\t'/\\t}
  printf '%s' "$s"
}

json_escape_file() {
  local path=$1
  local content
  content=$(cat "$path")
  json_escape "$content"
}

auto_review_and_fix_missing_dependency() {
  local context=$1
  local tools=$2
  echo "auto-review-and-fix ${context} requires ${tools}; install the missing dependency and retry" >&2
}

require_command() {
  local command_name=$1
  local context=$2

  if command -v "$command_name" >/dev/null 2>&1; then
    return 0
  fi

  auto_review_and_fix_missing_dependency "$context" "$command_name"
  return 1
}

require_any_command() {
  local context=$1
  shift

  local command_name
  local joined_tools=""
  for command_name in "$@"; do
    if command -v "$command_name" >/dev/null 2>&1; then
      return 0
    fi
    if [ -n "$joined_tools" ]; then
      joined_tools="${joined_tools} or "
    fi
    joined_tools="${joined_tools}${command_name}"
  done

  auto_review_and_fix_missing_dependency "$context" "$joined_tools"
  return 1
}

governance_context_json() {
  local repo_root=$1
  local governance_dir=$repo_root/ai/governance

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$repo_root" <<'PY'
import glob
import json
import os
import sys

repo_root = os.path.abspath(sys.argv[1])
governance_dir = os.path.join(repo_root, "ai", "governance")

well_known = [
    "PROJECT_OPERATING_PROFILE.md",
    "REVIEW_CALIBRATION.md",
    "RABBIT_HOLE_CHECK_PROMPT.md",
]

files = []
seen = set()

for name in well_known:
    path = os.path.join(governance_dir, name)
    if os.path.isfile(path):
        files.append(path)
        seen.add(os.path.normpath(path))

for path in sorted(glob.glob(os.path.join(governance_dir, "COMPONENT_PROFILE*.md"))):
    norm = os.path.normpath(path)
    if os.path.isfile(path) and norm not in seen:
        files.append(path)
        seen.add(norm)

if os.path.isdir(governance_dir):
    for name in sorted(os.listdir(governance_dir)):
        if not name.endswith(".md"):
            continue
        path = os.path.join(governance_dir, name)
        norm = os.path.normpath(path)
        if os.path.isfile(path) and norm not in seen:
            files.append(path)
            seen.add(norm)

def kind_for(path):
    name = os.path.basename(path)
    if name == "PROJECT_OPERATING_PROFILE.md":
        return "project_operating_profile"
    if name == "REVIEW_CALIBRATION.md":
        return "review_calibration"
    if name == "RABBIT_HOLE_CHECK_PROMPT.md":
        return "rabbit_hole_check_prompt"
    if name.startswith("COMPONENT_PROFILE") and name.endswith(".md"):
        return "component_profile"
    return "governance_note"

payload = {
    "present": len(files) > 0,
    "directory": "ai/governance",
    "files": [],
}

for path in files:
    rel = os.path.relpath(path, repo_root).replace(os.sep, "/")
    with open(path, encoding="utf-8") as handle:
        content = handle.read()
    payload["files"].append(
        {
            "kind": kind_for(path),
            "path": rel,
            "content_markdown": content,
        }
    )

json.dump(payload, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  local paths=()
  local path
  local rel
  local kind
  local first=true

  if [ -f "$governance_dir/PROJECT_OPERATING_PROFILE.md" ]; then
    paths+=("$governance_dir/PROJECT_OPERATING_PROFILE.md")
  fi
  if [ -f "$governance_dir/REVIEW_CALIBRATION.md" ]; then
    paths+=("$governance_dir/REVIEW_CALIBRATION.md")
  fi
  if [ -f "$governance_dir/RABBIT_HOLE_CHECK_PROMPT.md" ]; then
    paths+=("$governance_dir/RABBIT_HOLE_CHECK_PROMPT.md")
  fi
  if [ -d "$governance_dir" ]; then
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      paths+=("$path")
    done < <(find "$governance_dir" -maxdepth 1 -type f -name 'COMPONENT_PROFILE*.md' | sort)
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      case "$(basename "$path")" in
        PROJECT_OPERATING_PROFILE.md|REVIEW_CALIBRATION.md|RABBIT_HOLE_CHECK_PROMPT.md|COMPONENT_PROFILE*.md)
          continue
          ;;
      esac
      paths+=("$path")
    done < <(find "$governance_dir" -maxdepth 1 -type f -name '*.md' | sort)
  fi

  if [ "${#paths[@]}" -eq 0 ]; then
    cat <<'EOF'
{
  "present": false,
  "directory": "ai/governance",
  "files": []
}
EOF
    return
  fi

  printf '{\n'
  printf '  "present": true,\n'
  printf '  "directory": "ai/governance",\n'
  printf '  "files": [\n'
  for path in "${paths[@]}"; do
    rel=${path#"$repo_root"/}
    case "$(basename "$path")" in
      PROJECT_OPERATING_PROFILE.md)
        kind=project_operating_profile
        ;;
      REVIEW_CALIBRATION.md)
        kind=review_calibration
        ;;
      RABBIT_HOLE_CHECK_PROMPT.md)
        kind=rabbit_hole_check_prompt
        ;;
      COMPONENT_PROFILE*.md)
        kind=component_profile
        ;;
      *)
        kind=governance_note
        ;;
    esac
    if [ "$first" = false ]; then
      printf ',\n'
    fi
    first=false
    printf '    {\n'
    printf '      "kind": "%s",\n' "$kind"
    printf '      "path": "%s",\n' "$(json_escape "$rel")"
    printf '      "content_markdown": "%s"\n' "$(json_escape_file "$path")"
    printf '    }'
  done
  printf '\n  ]\n'
  printf '}\n'
}

write_review_consensus_request() {
  local request_path=$1
  local round_policy_path=$2
  local iteration_status_path=$3
  local checkpoint_history_path=$4
  local governance_context_path=$5

  if command -v jq >/dev/null 2>&1; then
    jq \
      --slurpfile round_policy "$round_policy_path" \
      --slurpfile iteration_status "$iteration_status_path" \
      --slurpfile governance_context "$governance_context_path" \
      --rawfile checkpoint_history "$checkpoint_history_path" '
      if type != "object" then
        error("request artifact must be a JSON object")
      else
        . + {
          review_consensus_max_rejections: ($round_policy[0].review_consensus.max_rejections // 0),
          automation_context: {
            round_policy: $round_policy[0],
            iteration_status: $iteration_status[0],
            checkpoint_history_markdown: $checkpoint_history,
            governance_context: ($governance_context[0] // {
              present: false,
              directory: "ai/governance",
              files: []
            })
          }
        }
      end
    ' "$request_path"
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$request_path" "$round_policy_path" "$iteration_status_path" "$checkpoint_history_path" "$governance_context_path" <<'PY'
import json
import sys

(
    request_path,
    round_policy_path,
    iteration_status_path,
    checkpoint_history_path,
    governance_context_path,
) = sys.argv[1:6]

with open(request_path, encoding="utf-8") as handle:
    request = json.load(handle)
if not isinstance(request, dict):
    raise SystemExit("request artifact must be a JSON object")

with open(round_policy_path, encoding="utf-8") as handle:
    round_policy = json.load(handle)
with open(iteration_status_path, encoding="utf-8") as handle:
    iteration_status = json.load(handle)
with open(checkpoint_history_path, encoding="utf-8") as handle:
    checkpoint_history = handle.read()
with open(governance_context_path, encoding="utf-8") as handle:
    governance_context = json.load(handle)

request["automation_context"] = {
    "round_policy": round_policy,
    "iteration_status": iteration_status,
    "checkpoint_history_markdown": checkpoint_history,
    "governance_context": governance_context,
}
request["review_consensus_max_rejections"] = (
    round_policy.get("review_consensus", {}).get("max_rejections", 0)
)

json.dump(request, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  require_any_command "request packaging" jq python3 || return 1
  return 1
}

write_accepted_round_iteration_status() {
  local iteration_status_path=$1
  local round_policy_path=$2
  local accepted_review_findings_path=$3
  local review_audit_result_path=$4

  if command -v python3 >/dev/null 2>&1; then
    python3 - \
      "$iteration_status_path" \
      "$round_policy_path" \
      "$accepted_review_findings_path" \
      "$review_audit_result_path" <<'PY'
import json
import sys

(
    iteration_status_path,
    round_policy_path,
    accepted_review_findings_path,
    review_audit_result_path,
) = sys.argv[1:5]

with open(iteration_status_path, encoding="utf-8") as handle:
    iteration_status = json.load(handle)
if not isinstance(iteration_status, dict):
    raise SystemExit("iteration status artifact must be a JSON object")

with open(round_policy_path, encoding="utf-8") as handle:
    round_policy = json.load(handle)
if not isinstance(round_policy, dict):
    raise SystemExit("round policy artifact must be a JSON object")

with open(accepted_review_findings_path, encoding="utf-8") as handle:
    accepted_review_findings = json.load(handle)
if not isinstance(accepted_review_findings, dict):
    raise SystemExit("accepted review findings artifact must be a JSON object")

with open(review_audit_result_path, encoding="utf-8") as handle:
    review_audit_result = json.load(handle)
if not isinstance(review_audit_result, dict):
    raise SystemExit("review audit result artifact must be a JSON object")

def as_int(value, default):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default

def as_string(value):
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    return None

def as_string_list(values):
    if not isinstance(values, list):
        return []
    result = []
    for value in values:
        text = as_string(value)
        if text is not None:
            result.append(text)
    return result

def first_present_string(*values):
    for value in values:
        text = as_string(value)
        if text is not None:
            return text
    return None

current_round = as_int(iteration_status.get("round", 0), 0) + 1
previous_history = iteration_status.get("round_history")
if not isinstance(previous_history, list):
    previous_history = []
previous_round_entry = previous_history[-1] if previous_history else {}
if not isinstance(previous_round_entry, dict):
    previous_round_entry = {}

governance_assessment = accepted_review_findings.get("governance_assessment")
if not isinstance(governance_assessment, dict):
    governance_assessment = {}

current_component_purpose = first_present_string(
    governance_assessment.get("component_purpose"),
    accepted_review_findings.get("bounded_context"),
)
previous_component_purpose = first_present_string(
    iteration_status.get("last_component_purpose"),
    previous_round_entry.get("component_purpose"),
    previous_round_entry.get("bounded_context"),
)

accepted_finding_ids = as_string_list(review_audit_result.get("accepted_finding_ids"))
if not accepted_finding_ids:
    accepted_findings = accepted_review_findings.get("findings")
    if not isinstance(accepted_findings, list):
        accepted_findings = []
    accepted_finding_ids = [
        finding_id
        for finding in accepted_findings
        if isinstance(finding, dict)
        for finding_id in [as_string(finding.get("id"))]
        if finding_id is not None
    ]

previous_finding_ids = {
    finding_id
    for entry in previous_history
    if isinstance(entry, dict)
    for finding_id in as_string_list(entry.get("findings_addressed"))
}
new_finding_ids = {
    finding_id
    for finding_id in accepted_finding_ids
    if finding_id not in previous_finding_ids
}

same_component_rounds = 1 if current_component_purpose is not None else 0
if (
    current_component_purpose is not None
    and previous_component_purpose is not None
    and current_component_purpose == previous_component_purpose
):
    same_component_rounds = as_int(iteration_status.get("same_component_rounds", 0), 0) + 1

round_entry = {
    "round": current_round,
    "findings_addressed": accepted_finding_ids,
    "result": "accepted",
    "impact_summary": (
        first_present_string(accepted_review_findings.get("summary"))
        or "accepted remediation round checkpointed"
    ),
}
if current_component_purpose is not None:
    round_entry["component_purpose"] = current_component_purpose
bounded_context = as_string(accepted_review_findings.get("bounded_context"))
if bounded_context is not None:
    round_entry["bounded_context"] = bounded_context

iteration_status["round"] = current_round
iteration_status["max_rounds"] = as_int(
    round_policy.get("max_rounds", iteration_status.get("max_rounds", 0)),
    as_int(iteration_status.get("max_rounds", 0), 0),
)
iteration_status["issues_closed_this_round"] = len(accepted_finding_ids)
iteration_status["remaining_issue_count_estimate"] = as_int(
    iteration_status.get("remaining_issue_count_estimate", 0),
    0,
)
iteration_status["new_issues_opened_this_round"] = len(new_finding_ids)
iteration_status["stalled_rounds"] = 0
iteration_status["cycle_detected"] = False
iteration_status["rabbit_hole_detected"] = False
iteration_status["design_boundary_hit"] = False
iteration_status["same_component_rounds"] = same_component_rounds
iteration_status["recommended_human_follow_up"] = "none"
iteration_status["round_history"] = [*previous_history, round_entry]
iteration_status["last_component_purpose"] = current_component_purpose
iteration_status["recommended_next_action"] = "continue"
iteration_status["reason"] = (
    "accepted remediation round checkpointed; pending post-round evaluation"
)

json.dump(iteration_status, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  if command -v jq >/dev/null 2>&1; then
    jq \
      --slurpfile accepted_review_findings "$accepted_review_findings_path" \
      --slurpfile review_audit_result "$review_audit_result_path" \
      --slurpfile round_policy "$round_policy_path" '
      if type != "object" then
        error("iteration status artifact must be a JSON object")
      else
        . as $status
        | ($accepted_review_findings[0] // {}) as $accepted
        | ($review_audit_result[0] // {}) as $audit
        | ($accepted.governance_assessment // {}) as $governance
        | (
            $status.round_history
            | if type == "array" then . else [] end
          ) as $previous_history
        | (
            $governance.component_purpose
            // $accepted.bounded_context
            | if type == "string" and . != "" then . else null end
          ) as $current_component_purpose
        | (
            $status.last_component_purpose
            // (
              if ($previous_history | length) > 0 then
                (
                  $previous_history[-1].component_purpose
                  // $previous_history[-1].bounded_context
                )
              else
                null
              end
            )
            | if type == "string" and . != "" then . else null end
          ) as $previous_component_purpose
        | (
            ($audit.accepted_finding_ids // [])
            | map(select(type == "string" and . != ""))
          ) as $accepted_ids_from_audit
        | (
            if ($accepted_ids_from_audit | length) > 0 then
              $accepted_ids_from_audit
            else
              (
                ($accepted.findings // [])
                | map(.id // empty)
                | map(select(type == "string" and . != ""))
              )
            end
          ) as $accepted_finding_ids
        | (
            $previous_history
            | map(
                if type == "object" and (.findings_addressed | type) == "array" then
                  .findings_addressed
                else
                  []
                end
              )
            | flatten
            | map(select(type == "string" and . != ""))
            | unique
          ) as $previous_finding_ids
        | ((($status.round // 0) + 1)) as $current_round
        | (
            {
              round: $current_round,
              findings_addressed: $accepted_finding_ids,
              result: "accepted",
              impact_summary: (
                $accepted.summary
                | if type == "string" and . != "" then
                    .
                  else
                    "accepted remediation round checkpointed"
                  end
              )
            }
            + (
              if $current_component_purpose == null then
                {}
              else
                { component_purpose: $current_component_purpose }
              end
            )
            + (
              if ($accepted.bounded_context | type) == "string" and $accepted.bounded_context != "" then
                { bounded_context: $accepted.bounded_context }
              else
                {}
              end
            )
          ) as $round_entry
        | $status + {
            round: $current_round,
            max_rounds: ($round_policy[0].max_rounds // $status.max_rounds // 0),
            issues_closed_this_round: ($accepted_finding_ids | length),
            remaining_issue_count_estimate: ($status.remaining_issue_count_estimate // 0),
            new_issues_opened_this_round: ((($accepted_finding_ids | unique) - $previous_finding_ids) | length),
            stalled_rounds: 0,
            cycle_detected: false,
            rabbit_hole_detected: false,
            design_boundary_hit: false,
            same_component_rounds: (
              if $current_component_purpose == null then
                0
              elif $previous_component_purpose == $current_component_purpose then
                (($status.same_component_rounds // 0) + 1)
              else
                1
              end
            ),
            recommended_human_follow_up: "none",
            round_history: ($previous_history + [$round_entry]),
            last_component_purpose: $current_component_purpose,
            recommended_next_action: "continue",
            reason: "accepted remediation round checkpointed; pending post-round evaluation"
          }
      end
    ' "$iteration_status_path"
    return
  fi

  require_any_command "accepted-round iteration status packaging" jq python3 || return 1
  return 1
}

write_terminal_iteration_status() {
  local previous_status_path=$1
  local round_policy_path=$2
  local round_gate_result_path=$3

  if command -v jq >/dev/null 2>&1; then
    jq \
      --slurpfile previous_status "$previous_status_path" \
      --slurpfile round_policy "$round_policy_path" \
      --slurpfile round_gate_result "$round_gate_result_path" '
      if ($round_gate_result[0].decision // "") != "complete" then
        error("round gate result must use decision=complete")
      else
        $previous_status[0]
        | .max_rounds = ($round_policy[0].max_rounds // .max_rounds // 0)
        | .issues_closed_this_round = 0
        | .remaining_issue_count_estimate = (.remaining_issue_count_estimate // 0)
        | .new_issues_opened_this_round = 0
        | .rabbit_hole_detected = ($round_gate_result[0].rabbit_hole_detected // .rabbit_hole_detected // false)
        | .design_boundary_hit = ($round_gate_result[0].design_boundary_hit // .design_boundary_hit // false)
        | .same_component_rounds = (.same_component_rounds // 0)
        | .recommended_human_follow_up = ($round_gate_result[0].recommended_human_follow_up // .recommended_human_follow_up // "none")
        | .recommended_next_action = "complete"
        | .reason = ($round_gate_result[0].rationale // "no worthwhile unattended remediation slice remains")
      end
    ' "$previous_status_path"
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$previous_status_path" "$round_policy_path" "$round_gate_result_path" <<'PY'
import json
import sys

(
    previous_status_path,
    round_policy_path,
    round_gate_result_path,
) = sys.argv[1:4]

with open(previous_status_path, encoding="utf-8") as handle:
    previous_status = json.load(handle)
with open(round_policy_path, encoding="utf-8") as handle:
    round_policy = json.load(handle)
with open(round_gate_result_path, encoding="utf-8") as handle:
    round_gate_result = json.load(handle)

if round_gate_result.get("decision") != "complete":
    raise SystemExit("round gate result must use decision=complete")

previous_status["max_rounds"] = round_policy.get(
    "max_rounds", previous_status.get("max_rounds", 0)
)
previous_status["issues_closed_this_round"] = 0
previous_status["remaining_issue_count_estimate"] = previous_status.get(
    "remaining_issue_count_estimate", 0
)
previous_status["new_issues_opened_this_round"] = 0
previous_status["rabbit_hole_detected"] = round_gate_result.get(
    "rabbit_hole_detected", previous_status.get("rabbit_hole_detected", False)
)
previous_status["design_boundary_hit"] = round_gate_result.get(
    "design_boundary_hit", previous_status.get("design_boundary_hit", False)
)
previous_status["same_component_rounds"] = previous_status.get("same_component_rounds", 0)
previous_status["recommended_human_follow_up"] = round_gate_result.get(
    "recommended_human_follow_up",
    previous_status.get("recommended_human_follow_up", "none"),
)
previous_status["recommended_next_action"] = "complete"
previous_status["reason"] = round_gate_result.get(
    "rationale", "no worthwhile unattended remediation slice remains"
)

json.dump(previous_status, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  require_any_command "terminal iteration status packaging" jq python3 || return 1
  return 1
}

request_repo_root() {
  local request_path=$1

  if command -v jq >/dev/null 2>&1; then
    jq -r '
      if (.repo_root? | type) == "string" then
        (.repo_root | gsub("^\\s+|\\s+$"; ""))
      else
        empty
      end
    ' "$request_path" 2>/dev/null || true
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$request_path" <<'PY' 2>/dev/null || true
import json
import sys

value = ""
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        repo_root = json.load(handle).get("repo_root", "")
    if isinstance(repo_root, str):
        value = repo_root.strip()
except Exception:
    pass

print(value)
PY
    return
  fi

  require_any_command "request parsing" jq python3 || return 1
  printf ''
}

current_physical_pwd() {
  pwd -P 2>/dev/null || pwd
}

append_markdown_section() {
  local existing_path=$1
  local new_section=$2

  if [ -f "$existing_path" ] && [ -s "$existing_path" ]; then
    printf '%s\n\n%s\n' "$(cat "$existing_path")" "$new_section"
  else
    printf '%s\n' "$new_section"
  fi
}

is_git_worktree() {
  local repo_root=$1

  [ -n "$repo_root" ] || return 1
  git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

git_head_exists() {
  local repo_root=$1

  git -C "$repo_root" rev-parse --verify HEAD >/dev/null 2>&1
}

git_checkpoint_pathspec_args() {
  local repo_root=$1
  local repo_abs
  local run_abs

  GIT_CHECKPOINT_PATHSPEC_ARGS=()
  repo_abs=$(cd "$repo_root" && pwd -P 2>/dev/null) || return 0
  run_abs=$(resolve_run_dir_path) || return 0

  case "$run_abs" in
    "$repo_abs")
      return 0
      ;;
    "$repo_abs"/*)
      local rel=${run_abs#"$repo_abs"/}
      [ -n "$rel" ] || return 0
      GIT_CHECKPOINT_PATHSPEC_ARGS=(-- . ":(top,exclude)$rel")
      ;;
  esac
}

resolve_run_dir_path() {
  local run_dir=${FORGE_RUN_DIR:-}
  local launch_cwd=${FORGE_SCRIPT_LAUNCH_CWD:-}
  local candidate

  [ -n "$run_dir" ] || return 1

  case "$run_dir" in
    /*)
      candidate=$run_dir
      ;;
    *)
      [ -n "$launch_cwd" ] || return 1
      candidate=$launch_cwd/$run_dir
      ;;
  esac

  if [ -d "$candidate" ]; then
    (cd "$candidate" && pwd -P) 2>/dev/null
    return
  fi

  printf '%s\n' "$candidate"
}

git_repo_has_changes() {
  local repo_root=$1

  git_checkpoint_pathspec_args "$repo_root"
  [ -n "$(git -C "$repo_root" status --porcelain=v1 --untracked-files=all --ignore-submodules=none "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)" ]
}

git_has_dirty_submodule_worktrees() {
  local repo_root=$1
  local line
  local kind
  local xy
  local sub
  local rest

  git_checkpoint_pathspec_args "$repo_root"

  while IFS= read -r line; do
    read -r kind xy sub rest <<< "$line"
    [ -n "$sub" ] || continue
    case "$sub" in
      S*)
        if [ "${sub:2:1}" != "." ] || [ "${sub:3:1}" != "." ]; then
          return 0
        fi
        ;;
    esac
  done < <(git -C "$repo_root" status --porcelain=v2 --untracked-files=all --ignore-submodules=none "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" 2>/dev/null || true)

  return 1
}

fail_dirty_submodule_checkpoint() {
  local repo_root=$1
  local phase=$2

  git_checkpoint_pathspec_args "$repo_root"
  echo "auto-review-and-fix ${phase} cannot checkpoint dirty submodule worktree changes from the parent repository." >&2
  echo "Commit or discard the nested submodule changes, or stage a clean gitlink update, before rerunning." >&2
  git -C "$repo_root" status --short --untracked-files=all --ignore-submodules=none "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}" >&2 || true
}

git_stage_checkpoint_changes() {
  local repo_root=$1

  git_checkpoint_pathspec_args "$repo_root"
  git -C "$repo_root" add -A "${GIT_CHECKPOINT_PATHSPEC_ARGS[@]}"
}

git_checkpoint_commit() {
  local repo_root=$1
  local message=$2

  if git -C "$repo_root" var GIT_AUTHOR_IDENT >/dev/null 2>&1 \
    && git -C "$repo_root" var GIT_COMMITTER_IDENT >/dev/null 2>&1; then
    git -C "$repo_root" commit -m "$message" >/dev/null
    return
  fi

  git -C "$repo_root" \
    -c user.name='Forge Auto Review' \
    -c user.email='forge-auto-review@local' \
    commit -m "$message" >/dev/null
}

write_rabbit_hole_check_request() {
  local request_path=$1
  local review_consensus_request_path=$2
  local round_policy_path=$3
  local iteration_status_path=$4
  local accepted_review_findings_path=$5
  local review_audit_result_path=$6
  local implementation_report_path=$7
  local evidence_path=$8
  local judge_result_path=$9
  local checkpoint_record_path=${10}

  if command -v python3 >/dev/null 2>&1; then
    python3 - \
      "$request_path" \
      "$review_consensus_request_path" \
      "$round_policy_path" \
      "$iteration_status_path" \
      "$accepted_review_findings_path" \
      "$review_audit_result_path" \
      "$implementation_report_path" \
      "$evidence_path" \
      "$judge_result_path" \
      "$checkpoint_record_path" <<'PY'
import json
import sys

(
    request_path,
    review_consensus_request_path,
    round_policy_path,
    iteration_status_path,
    accepted_review_findings_path,
    review_audit_result_path,
    implementation_report_path,
    evidence_path,
    judge_result_path,
    checkpoint_record_path,
) = sys.argv[1:11]

with open(request_path, encoding="utf-8") as handle:
    request = json.load(handle)
if not isinstance(request, dict):
    raise SystemExit("request artifact must be a JSON object")

with open(review_consensus_request_path, encoding="utf-8") as handle:
    review_consensus_request = json.load(handle)
with open(round_policy_path, encoding="utf-8") as handle:
    round_policy = json.load(handle)
with open(iteration_status_path, encoding="utf-8") as handle:
    iteration_status = json.load(handle)
with open(accepted_review_findings_path, encoding="utf-8") as handle:
    accepted_review_findings = json.load(handle)
with open(review_audit_result_path, encoding="utf-8") as handle:
    review_audit_result = json.load(handle)
with open(implementation_report_path, encoding="utf-8") as handle:
    implementation_report = handle.read()
with open(evidence_path, encoding="utf-8") as handle:
    evidence = json.load(handle)
with open(judge_result_path, encoding="utf-8") as handle:
    judge_result = json.load(handle)
with open(checkpoint_record_path, encoding="utf-8") as handle:
    checkpoint_record = json.load(handle)

request["rabbit_hole_context"] = {
    "round_policy": round_policy,
    "iteration_status": iteration_status,
    "accepted_review_findings": accepted_review_findings,
    "review_audit_result": review_audit_result,
    "implementation_report_markdown": implementation_report,
    "evidence": evidence,
    "judge_result": judge_result,
    "checkpoint_record": checkpoint_record,
    "governance_context": review_consensus_request.get("automation_context", {}).get(
        "governance_context",
        {
            "present": False,
            "directory": "ai/governance",
            "files": [],
        },
    ),
}

json.dump(request, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  if command -v jq >/dev/null 2>&1; then
    jq \
      --slurpfile review_consensus_request "$review_consensus_request_path" \
      --slurpfile round_policy "$round_policy_path" \
      --slurpfile iteration_status "$iteration_status_path" \
      --slurpfile accepted_review_findings "$accepted_review_findings_path" \
      --slurpfile review_audit_result "$review_audit_result_path" \
      --rawfile implementation_report "$implementation_report_path" \
      --slurpfile evidence "$evidence_path" \
      --slurpfile judge_result "$judge_result_path" \
      --slurpfile checkpoint_record "$checkpoint_record_path" '
      if type != "object" then
        error("request artifact must be a JSON object")
      else
        . + {
          rabbit_hole_context: {
            round_policy: $round_policy[0],
            iteration_status: $iteration_status[0],
            accepted_review_findings: $accepted_review_findings[0],
            review_audit_result: $review_audit_result[0],
            implementation_report_markdown: $implementation_report,
            evidence: $evidence[0],
            judge_result: $judge_result[0],
            checkpoint_record: $checkpoint_record[0],
            governance_context: (
              $review_consensus_request[0].automation_context.governance_context // {
                present: false,
                directory: "ai/governance",
                files: []
              }
            )
          }
        }
      end
    ' "$request_path"
    return
  fi

  require_any_command "rabbit-hole request packaging" jq python3 || return 1
  return 1
}

resolve_recorded_artifact_path() {
  local run_dir=$1
  local raw_path=$2
  local candidate
  local remainder

  [ -n "$raw_path" ] || return 1

  case "$raw_path" in
    /*)
      printf '%s\n' "$raw_path"
      return 0
      ;;
  esac

  candidate=$raw_path
  while [ -n "$candidate" ]; do
    case "/${run_dir%/}/" in
      *"/${candidate%/}/")
        remainder=${raw_path#"$candidate"}
        remainder=${remainder#/}
        if [ -n "$remainder" ]; then
          printf '%s/%s\n' "${run_dir%/}" "$remainder"
        else
          printf '%s\n' "${run_dir%/}"
        fi
        return 0
        ;;
    esac

    case "$candidate" in
      */*)
        candidate=${candidate%/*}
        ;;
      *)
        candidate=
        ;;
    esac
  done

  printf '%s/%s\n' "${run_dir%/}" "$raw_path"
}

last_recorded_artifact_path_with_jq() {
  local events_path=$1
  local artifact_name=$2

  [ -f "$events_path" ] || return 0

  jq -r -s --arg artifact_name "$artifact_name" '
    [
      .[]
      | select(.type == "artifact_written")
      | .artifact?
      | select(type == "object" and .name == $artifact_name)
      | .path?
      | select(type == "string" and length > 0)
    ]
    | last // empty
  ' "$events_path" 2>/dev/null || true
}

resolve_imported_child_artifact_path_with_jq() {
  local run_dir=$1
  local artifact_name=$2
  local events_path=$run_dir/events.ndjson
  local child_binding
  local child_run_dir
  local child_artifact
  local child_artifact_path
  local resolved_child_run_dir
  local resolved_path

  [ -f "$events_path" ] || return 1

  child_binding=$(jq -r -s --arg artifact_name "$artifact_name" '
    [
      .[]
      | select(.type == "artifact_metadata_recorded" and .artifact_name == $artifact_name)
      | .binding?
      | select(type == "object" and .kind == "imported_child")
      | [.child_run_dir, .child_artifact]
      | select(all(.[]; type == "string" and length > 0))
      | @tsv
    ]
    | last // empty
  ' "$events_path" 2>/dev/null || true)

  [ -n "$child_binding" ] || return 1
  IFS=$'\t' read -r child_run_dir child_artifact <<EOF
$child_binding
EOF
  [ -n "$child_run_dir" ] || return 1
  [ -n "$child_artifact" ] || return 1

  resolved_child_run_dir=$(resolve_recorded_artifact_path "$run_dir" "$child_run_dir") || return 1

  child_artifact_path=$(
    last_recorded_artifact_path_with_jq \
      "$resolved_child_run_dir/events.ndjson" \
      "$child_artifact"
  )
  [ -n "$child_artifact_path" ] || return 1

  resolved_path=$(resolve_recorded_artifact_path "$resolved_child_run_dir" "$child_artifact_path") || return 1
  [ -f "$resolved_path" ] || return 1

  printf '%s\n' "$resolved_path"
}

resolve_text_artifact_path_with_jq() {
  local run_dir=$1
  local artifact_name=$2
  local declared_path=$3
  local artifact_path
  local resolved_path

  resolved_path=$(resolve_imported_child_artifact_path_with_jq "$run_dir" "$artifact_name")
  if [ -n "$resolved_path" ] && [ -f "$resolved_path" ]; then
    printf '%s\n' "$resolved_path"
    return 0
  fi

  if [ -f "$declared_path" ]; then
    printf '%s\n' "$declared_path"
    return 0
  fi

  artifact_path=$(last_recorded_artifact_path_with_jq "$run_dir/events.ndjson" "$artifact_name")
  [ -n "$artifact_path" ] || return 1

  resolved_path=$(resolve_recorded_artifact_path "$run_dir" "$artifact_path") || return 1
  [ -f "$resolved_path" ] || return 1

  printf '%s\n' "$resolved_path"
}

write_run_summary_context() {
  local run_dir=$1
  local implementation_report_path=$run_dir/artifacts/implementation-report.md
  local evidence_path=$run_dir/artifacts/evidence.json
  local judge_result_path=$run_dir/artifacts/judge.json
  local last_remediated_findings_path=$run_dir/artifacts/last-remediated-findings.json
  local rabbit_hole_check_summary_path=$run_dir/artifacts/rabbit-hole-check-summary.md
  local rabbit_hole_check_report_path=$run_dir/artifacts/rabbit-hole-check-report.md

  if command -v python3 >/dev/null 2>&1; then
    python3 - \
      "$run_dir" \
      "$implementation_report_path" \
      "$evidence_path" \
      "$judge_result_path" \
      "$last_remediated_findings_path" \
      "$rabbit_hole_check_summary_path" \
      "$rabbit_hole_check_report_path" <<'PY'
import json
import os
import sys
from pathlib import Path

(
    run_dir,
    implementation_report_path,
    evidence_path,
    judge_result_path,
    last_remediated_findings_path,
    rabbit_hole_check_summary_path,
    rabbit_hole_check_report_path,
) = sys.argv[1:8]

def load_artifact_state(events_path):
    artifact_index = {}
    artifact_metadata = {}
    if not os.path.isfile(events_path):
        return artifact_index, artifact_metadata
    with open(events_path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            event = json.loads(line)
            event_type = event.get("type")
            if event_type == "artifact_written":
                artifact = event.get("artifact")
                if isinstance(artifact, dict):
                    name = artifact.get("name")
                    if isinstance(name, str) and name:
                        artifact_index[name] = artifact
            elif event_type == "artifact_metadata_recorded":
                artifact_name = event.get("artifact_name")
                if isinstance(artifact_name, str) and artifact_name:
                    artifact_metadata[artifact_name] = {
                        "binding": event.get("binding"),
                    }
    return artifact_index, artifact_metadata

def resolve_recorded_artifact_path(run_dir_path, raw_path):
    path = Path(raw_path)
    if path.is_absolute():
        return path
    run_dir = Path(run_dir_path)
    raw_parts = path.parts
    run_dir_parts = run_dir.parts
    for prefix_len in range(len(raw_parts), 0, -1):
        prefix = raw_parts[:prefix_len]
        if tuple(run_dir_parts[-prefix_len:]) == prefix:
            remainder = raw_parts[prefix_len:]
            return run_dir.joinpath(*remainder) if remainder else run_dir
    return run_dir / path

def resolve_text_artifact_path(run_dir_path, artifact_name, declared_path):
    artifact_index, artifact_metadata = load_artifact_state(
        os.path.join(run_dir_path, "events.ndjson")
    )
    binding = artifact_metadata.get(artifact_name, {}).get("binding")
    if isinstance(binding, dict) and binding.get("kind") == "imported_child":
        child_run_dir = binding.get("child_run_dir")
        child_artifact = binding.get("child_artifact")
        if isinstance(child_run_dir, str) and isinstance(child_artifact, str):
            child_run_dir_path = resolve_recorded_artifact_path(
                run_dir_path, child_run_dir
            )
            child_index, _ = load_artifact_state(
                os.path.join(child_run_dir_path, "events.ndjson")
            )
            child_record = child_index.get(child_artifact)
            if isinstance(child_record, dict):
                child_path = resolve_recorded_artifact_path(
                    child_run_dir_path, child_record.get("path", "")
                )
                if child_path.is_file():
                    return child_path

    candidate = Path(declared_path)
    if candidate.is_file():
        return candidate

    artifact = artifact_index.get(artifact_name)
    if isinstance(artifact, dict):
        artifact_path = resolve_recorded_artifact_path(
            run_dir_path, artifact.get("path", "")
        )
        if artifact_path.is_file():
            return artifact_path

    return None

def maybe_read_text(path):
    if not os.path.isfile(path):
        return False, ""
    with open(path, encoding="utf-8") as handle:
        return True, handle.read()

def maybe_read_json(path):
    if not os.path.isfile(path):
        return False, None
    with open(path, encoding="utf-8") as handle:
        return True, json.load(handle)

implementation_report_present, implementation_report_markdown = maybe_read_text(
    implementation_report_path
)
evidence_present, evidence = maybe_read_json(evidence_path)
judge_result_present, judge_result = maybe_read_json(judge_result_path)
last_remediated_findings_present, last_remediated_findings = maybe_read_json(
    last_remediated_findings_path
)
rabbit_hole_check_summary_present, rabbit_hole_check_summary_markdown = maybe_read_text(
    rabbit_hole_check_summary_path
)
rabbit_hole_check_report_resolved_path = resolve_text_artifact_path(
    run_dir,
    "rabbit_hole_check_report",
    rabbit_hole_check_report_path,
)
if rabbit_hole_check_report_resolved_path is None:
    rabbit_hole_check_report_present, rabbit_hole_check_report_markdown = False, ""
else:
    rabbit_hole_check_report_present, rabbit_hole_check_report_markdown = maybe_read_text(
        rabbit_hole_check_report_resolved_path
    )

context = {
    "has_remediation_context": any(
        [
            implementation_report_present,
            evidence_present,
            judge_result_present,
            last_remediated_findings_present,
        ]
    ),
    "implementation_report_present": implementation_report_present,
    "implementation_report_markdown": implementation_report_markdown,
    "evidence_present": evidence_present,
    "evidence": evidence,
    "judge_result_present": judge_result_present,
    "judge_result": judge_result,
    "last_remediated_findings_present": last_remediated_findings_present,
    "last_remediated_findings": last_remediated_findings,
    "rabbit_hole_check_summary_present": rabbit_hole_check_summary_present,
    "rabbit_hole_check_summary_markdown": rabbit_hole_check_summary_markdown,
    "rabbit_hole_check_report_present": rabbit_hole_check_report_present,
    "rabbit_hole_check_report_markdown": rabbit_hole_check_report_markdown,
}

json.dump(context, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
    return
  fi

  if command -v jq >/dev/null 2>&1; then
    local implementation_report_present=false
    local evidence_present=false
    local judge_result_present=false
    local last_remediated_findings_present=false
    local rabbit_hole_check_summary_present=false
    local rabbit_hole_check_report_present=false
    local has_remediation_context=false
    local implementation_report_tmp
    local evidence_tmp
    local judge_result_tmp
    local last_remediated_findings_tmp
    local rabbit_hole_check_summary_tmp
    local rabbit_hole_check_report_tmp
    local resolved_rabbit_hole_check_report_path
    local status=0

    implementation_report_tmp=$(mktemp)
    evidence_tmp=$(mktemp)
    judge_result_tmp=$(mktemp)
    last_remediated_findings_tmp=$(mktemp)
    rabbit_hole_check_summary_tmp=$(mktemp)
    rabbit_hole_check_report_tmp=$(mktemp)

    if [ -f "$implementation_report_path" ]; then
      implementation_report_present=true
      cat "$implementation_report_path" > "$implementation_report_tmp"
    else
      : > "$implementation_report_tmp"
    fi

    if [ -f "$evidence_path" ]; then
      evidence_present=true
      cat "$evidence_path" > "$evidence_tmp"
    else
      printf 'null\n' > "$evidence_tmp"
    fi

    if [ -f "$judge_result_path" ]; then
      judge_result_present=true
      cat "$judge_result_path" > "$judge_result_tmp"
    else
      printf 'null\n' > "$judge_result_tmp"
    fi

    if [ -f "$last_remediated_findings_path" ]; then
      last_remediated_findings_present=true
      cat "$last_remediated_findings_path" > "$last_remediated_findings_tmp"
    else
      printf 'null\n' > "$last_remediated_findings_tmp"
    fi

    if [ -f "$rabbit_hole_check_summary_path" ]; then
      rabbit_hole_check_summary_present=true
      cat "$rabbit_hole_check_summary_path" > "$rabbit_hole_check_summary_tmp"
    else
      : > "$rabbit_hole_check_summary_tmp"
    fi

    resolved_rabbit_hole_check_report_path=$(
      resolve_text_artifact_path_with_jq \
        "$run_dir" \
        "rabbit_hole_check_report" \
        "$rabbit_hole_check_report_path" \
        || true
    )

    if [ -n "${resolved_rabbit_hole_check_report_path:-}" ] \
      && [ -f "$resolved_rabbit_hole_check_report_path" ]; then
      rabbit_hole_check_report_present=true
      cat "$resolved_rabbit_hole_check_report_path" > "$rabbit_hole_check_report_tmp"
    else
      : > "$rabbit_hole_check_report_tmp"
    fi

    if [ "$implementation_report_present" = true ] \
      || [ "$evidence_present" = true ] \
      || [ "$judge_result_present" = true ] \
      || [ "$last_remediated_findings_present" = true ]; then
      has_remediation_context=true
    fi

    jq -n \
      --argjson has_remediation_context "$has_remediation_context" \
      --argjson implementation_report_present "$implementation_report_present" \
      --rawfile implementation_report_markdown "$implementation_report_tmp" \
      --argjson evidence_present "$evidence_present" \
      --slurpfile evidence "$evidence_tmp" \
      --argjson judge_result_present "$judge_result_present" \
      --slurpfile judge_result "$judge_result_tmp" \
      --argjson last_remediated_findings_present "$last_remediated_findings_present" \
      --slurpfile last_remediated_findings "$last_remediated_findings_tmp" \
      --argjson rabbit_hole_check_summary_present "$rabbit_hole_check_summary_present" \
      --rawfile rabbit_hole_check_summary_markdown "$rabbit_hole_check_summary_tmp" \
      --argjson rabbit_hole_check_report_present "$rabbit_hole_check_report_present" \
      --rawfile rabbit_hole_check_report_markdown "$rabbit_hole_check_report_tmp" '
      {
        has_remediation_context: $has_remediation_context,
        implementation_report_present: $implementation_report_present,
        implementation_report_markdown: $implementation_report_markdown,
        evidence_present: $evidence_present,
        evidence: $evidence[0],
        judge_result_present: $judge_result_present,
        judge_result: $judge_result[0],
        last_remediated_findings_present: $last_remediated_findings_present,
        last_remediated_findings: $last_remediated_findings[0],
        rabbit_hole_check_summary_present: $rabbit_hole_check_summary_present,
        rabbit_hole_check_summary_markdown: $rabbit_hole_check_summary_markdown,
        rabbit_hole_check_report_present: $rabbit_hole_check_report_present,
        rabbit_hole_check_report_markdown: $rabbit_hole_check_report_markdown
      }
    ' || status=$?

    rm -f -- \
      "$implementation_report_tmp" \
      "$evidence_tmp" \
      "$judge_result_tmp" \
      "$last_remediated_findings_tmp" \
      "$rabbit_hole_check_summary_tmp" \
      "$rabbit_hole_check_report_tmp"
    return "$status"
  fi

  require_any_command "run summary context packaging" jq python3 || return 1
  return 1
}
