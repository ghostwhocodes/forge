# Implement Change Template

This template is the shipped source bundle for `implement-change`.

Use it for:

- simple change requests
- feature spec implementation
- fixing a pasted review issue
- unattended implementation runs that need a bounded machine review loop

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for each agent or judge node
- `hooks/notify`: repo-local notification hook baseline
- `scripts/collect-evidence.sh`: source helper used by the `evidence` command node
- `scripts/write-quality-policy.sh`: source helper that freezes the quality policy artifact

Current flow:

- `forge scaffold repo` copies this bundle into repo-local `.forge/templates/implement-change/`
- `forge intake ... --template=implement-change` or the default intake mapping selects this workflow source
- `forge run init` resolves `prompt_file` and `command_file` references from this bundle into the frozen run-local `spec.json`
- `forge template analyze-runs` and `forge template propose-update` target this bundle as the editable source of truth

Current implementation loop:

- `plan -> critique -> human_review_plan`
- `human_review_plan -> quality_policy -> implement`
- `implement -> evidence -> review -> quality_gate`
- `quality_gate -> implement_rework -> evidence -> review -> quality_gate` while the quality policy still allows another remediation round
- `quality_gate -> judge -> docs_update -> human_signoff` when the quality gate passes

Quality-loop notes:

- `quality_policy` freezes the blocking threshold and maximum review-cycle budget with the run
- `quality_gate` is responsible for enforcing that policy semantically through `pass`, `rework`, or `escalate`
- `warnings` carries cumulative loop telemetry forward across rounds so the final judge and human signoff can see stability signals, not only the latest review snapshot
