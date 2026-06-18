# Review And Fix Template

This template is the shipped source bundle for `review-and-fix`.

Use it for review runs that should continue into remediation after an explicit human curation checkpoint.

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for review, planning, implementation, judge, and docs nodes
- `prompts/evaluate-iteration.txt`: source prompt for bounded continuation decisions after accepted fixes
- `hooks/notify`: repo-local notification hook baseline
- `scripts/lib.sh`: shared shell helpers for command nodes in this bundle
- `scripts/capture-selection.sh`: source helper used by the curated findings materialization command
- `scripts/collect-evidence.sh`: source helper used by the `evidence` command node
- `scripts/write-iteration-policy.sh`: source helper that freezes the bounded iteration policy artifact

Current flow:

- `forge scaffold repo` copies this bundle into repo-local `.forge/templates/review-and-fix/`
- `forge intake review --fix` selects this workflow by default unless you override the template choice
- `forge run init` resolves prompt-file and command-file references from this bundle into the frozen run-local `spec.json`
- `forge template analyze-runs` and `forge template propose-update` treat this bundle as editable source material

Config example:

```bash
cat > /tmp/review-and-fix-config.json <<'JSON'
{
  "review_and_fix": {
    "max_rounds": 3
  },
  "codex": {
    "model": "gpt-5.5",
    "reasoning_effort": "high"
  }
}
JSON

forge intake review \
  --repo="$repo" \
  --fix \
  --config=/tmp/review-and-fix-config.json \
  --out="$tmp"
```

`review_and_fix.max_rounds` overrides the default accepted-remediation round
cap for this template. The frozen policy derives `max_round_continuations` as
`max_rounds - 1`; `outer_iteration` resolves its budget from that frozen policy,
and checkpointed continuations reuse the same loop instance to enforce the
accepted-round cap. Each accepted-round evaluation still writes
`remaining_round_continuations` as status telemetry for operators and later
prompts.
`max_rounds` must be at most `4294967296`, keeping the derived continuation
budget within the engine's loop-budget range.
`codex.model` and `codex.reasoning_effort` apply to the `codex exec` dispatches
used by the review, planning, implementation, and judge nodes.

Current iteration notes:

- `iteration_policy` freezes the bounded continuation policy at run start
- repo-facing agents execute from the requested repository root via `cwd: "$request.repo_root"`
- `review_scan` is read-only and should report only currently actionable findings; if no bounded slice remains, curation may finish the run with `finding_selection_status=complete`
- `review_scan` may scan the repository when needed, but it should do so intentionally: inspect the current diff first when present, prefer the smallest signals that answer the current review question, and write `review-findings.json` as soon as one bounded slice or a justified empty result is established
- `judge` still decides whether the latest remediation slice is acceptable
- `evaluate_iteration` decides whether the run should continue, checkpoint, complete, or escalate before another scan
- when `evaluate_iteration` detects a likely repeated review/remediation cycle, it now routes to `prepare_cycle_investigation_request -> cycle_investigation -> human_cycle_checkpoint` instead of silently spending more local rounds
- the cycle investigation runs as a child workflow so it can produce a wider root-cause report before handing control back to a human
- the default frozen `max_rounds: 4` policy derives `max_round_continuations: 3`, and direct plus human-checkpoint `continue` transitions share one `outer_iteration` loop instance
- `human_iteration_checkpoint` appears only when the policy says the operator should re-evaluate the next round
