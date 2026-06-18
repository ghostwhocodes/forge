# Architecture Guard Template

This template is the shipped source bundle for `architecture-guard`.

Use it for repository-local architecture compliance work where the source of truth is a typed contract ledger rather than an informal checklist.

Required seed artifacts:

- `request`
- `architecture_contract`
- `decommission_ledger`
- `test_governance`

Template-local sample contract source:

- [`examples/runtime-boundaries.json`](examples/runtime-boundaries.json)

Template-local sample decommission ledger source:

- [`examples/runtime-boundaries-decommission.json`](examples/runtime-boundaries-decommission.json)

Template-local sample test-governance source:

- [`examples/runtime-boundaries-test-governance.json`](examples/runtime-boundaries-test-governance.json)

For consumer repositories, prefer repo-local governance artifacts under:

- `.forge/governance/architecture-contract.json`
- `.forge/governance/decommission-ledger.json`
- `.forge/governance/test-governance.json`

The `examples/` directory in this bundle is source-side reference material for
authors of the template. `forge scaffold repo` does not copy it into consumer
repositories.

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for drift detection, planning, implementation, and judgement
- `hooks/notify`: repo-local notification hook baseline
- `scripts/capture-selection.sh`: source helper used to materialize curated boundary findings from typed human decision keys

Current flow:

- detect architecture drift against the typed contract, decommission ledger, and
  test-governance model
- summarize the real findings for human curation
- materialize the curated selection as structured state
- plan and implement guardrail fixes
- judge the result against the same governance artifact set
- require explicit human signoff before completion

This template exists to make architecture drift review part of the same Forge system being built, not a separate manual process.
