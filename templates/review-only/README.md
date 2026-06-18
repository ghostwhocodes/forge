# Review Only Template

This template is the shipped source bundle for `review-only`.

Use it for discovery-only code review runs where Forge should stop at curated findings rather than continue into remediation.

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for the review scan and summary steps
- `hooks/notify`: repo-local notification hook baseline
- `scripts/README.md`: placeholder because this template has no helper scripts yet

Current flow:

- `forge scaffold repo` copies this bundle into repo-local `.forge/templates/review-only/`
- `forge intake review` selects this workflow by default unless you override the template choice
- `forge run init` resolves prompt-file references from this bundle into the frozen run-local `spec.json`
- `forge template analyze-runs` and `forge template propose-update` treat this bundle as editable source material
