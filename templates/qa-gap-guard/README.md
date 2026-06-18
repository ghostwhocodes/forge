# QA Gap Guard Template

This template is the shipped source bundle for `qa-gap-guard`.

Use it for repository-local QA evaluation runs where the goal is to identify
meaningful verification gaps, curate the real ones, and emit a clean
remediation brief plus prevention guidance for later engineering runs.

Required seed artifacts:

- `request`
- `qa_governance`

Template-local sample governance source:

- [`examples/qa-governance.json`](examples/qa-governance.json)

For consumer repositories, prefer a repo-local governance artifact under:

- `.forge/governance/qa-governance.json`

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for gap detection, summary, remediation
  briefing, and engineering feedback
- `hooks/notify`: repo-local notification hook baseline
- `scripts/capture-selection.sh`: source helper used to materialize curated QA
  gaps from typed human decision keys

Current flow:

- detect meaningful QA/testing gaps against the typed governance artifact and
  current request
- summarize the findings for human curation
- materialize the curated QA-gap selection
- write a gap-closure brief for a later coding run
- write an engineering self-improvement summary for future runs

This template is audit-only. It does not implement fixes in the same run.
