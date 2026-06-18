# Templates

This directory contains the shipped Forge workflow templates.

Each template bundle has its own `workflow.json`, prompts, helper scripts, and
template-local README. This index summarizes the process in each flow and shows
the control path at a glance.

## Shipped Templates

- [`implement-change`](implement-change/): plan, implement, review, gate, judge, and sign off a requested change
- [`review-and-fix`](review-and-fix/): review first, curate findings, remediate a bounded slice, then decide whether another round is worthwhile
- [`auto-review-and-fix`](auto-review-and-fix/): fully automated review consensus plus bounded remediation with checkpoint commits and only a final human signoff
- [`review-only`](review-only/): discovery-only review that stops after curated findings
- [`qa-gap-guard`](qa-gap-guard/): audit QA gaps and produce remediation guidance without changing code
- [`architecture-guard`](architecture-guard/): detect architecture drift against governance artifacts and remediate selected violations

## Implement Change

Use this when the run should deliver a requested change end to end, with a
bounded machine review loop and explicit human checkpoints.

```mermaid
flowchart TD
    A[plan] --> B[critique]
    B --> C{human_review_plan}
    C -- approved --> D[quality_policy]
    C -- rework --> A
    D --> E[implement]
    E --> F[evidence]
    F --> G[review]
    G --> H{quality_gate}
    H -- rework --> I[implement_rework]
    I --> F
    H -- pass --> J{judge}
    H -- escalate --> X[escalate]
    J -- replan --> C
    J -- accept --> K[docs_update]
    J -- escalate --> X
    K --> L{human_signoff}
    L -- changes_requested --> E
    L -- accepted --> M[complete]
```

Process summary:

- drafts a plan, critiques it, and waits for human approval
- freezes a quality policy before coding starts
- implements, gathers evidence, and runs adversarial review
- loops through rework while the quality gate permits it
- finishes with a final judge pass, docs update, and human signoff

## Review And Fix

Use this when the run should start as a review, then remediate only the
accepted findings, with bounded iteration between review and repair.

```mermaid
flowchart TD
    A[iteration_policy] --> B[review_scan]
    B --> C[write_findings]
    C --> D{human_curate_findings}
    D -- rescan --> B
    D -- complete --> Z[complete]
    D -- accepted --> E[capture_selection]
    E --> F[fix_plan]
    F --> G[implement_fixes]
    G --> H[evidence]
    H --> I{judge}
    I -- retry --> G
    I -- replan --> F
    I -- accept --> J{evaluate_iteration}
    I -- escalate --> X[escalate]
    J -- continue --> B
    J -- checkpoint --> K{human_iteration_checkpoint}
    J -- investigate_cycle --> L[prepare_cycle_investigation_request]
    J -- complete --> O[docs_update]
    J -- escalate --> X
    K -- continue --> B
    K -- complete --> O
    K -- escalate --> X
    L --> M[cycle_investigation subrun]
    M --> N{human_cycle_checkpoint}
    N -- continue --> B
    N -- complete --> O
    N -- escalate --> X
    O --> P{human_signoff}
    P -- changes_requested --> G
    P -- accepted --> Z
```

Process summary:

- freezes an iteration policy, then scans the repo for actionable findings
- requires a human to choose which findings are real and worth fixing
- plans and implements only the curated slice, then judges the result
- uses a second iteration decision to determine whether another round is worth doing
- can launch a child investigation workflow if it detects a likely repeated cycle

## Auto Review And Fix

Use this when a large branch should go through several unattended review/fix
cycles, with reviewer disagreement handled inside automation rather than by a
human checkpoint on every round.

```mermaid
flowchart TD
    A[initialize_round_policy] --> B[checkpoint_start_state]
    B --> C[prepare_review_consensus_request]
    C --> D[review_consensus subrun]
    D --> E{decide_round_action}
    E -- complete --> F[write_terminal_iteration_status]
    E -- escalate --> X[escalate]
    E -- implement --> G[fix_plan]
    F --> P[prepare_run_summary_context]
    G --> H[implement_fixes]
    H --> I[evidence]
    I --> J{verify_fix_round}
    J -- retry --> H
    J -- replan --> G
    J -- accept --> K[checkpoint_commit]
    J -- escalate --> X
    K --> L{decide_rabbit_hole_check}
    L -- run_check --> M[prepare_rabbit_hole_check_request]
    L -- skip_check --> N[write_skipped_rabbit_hole_check]
    M --> O[rabbit_hole_check subrun]
    N --> Q{evaluate_iteration}
    O --> Q
    Q -- continue --> C
    Q -- complete --> P
    Q -- escalate --> X
    P --> R[write_run_summary]
    R --> S{human_signoff}
    S -- accepted --> Z[complete]
    S -- escalate --> X
```

Process summary:

- freezes an automation policy, freezes any repo-local governance notes once, and optionally creates a baseline checkpoint commit before unattended review rounds begin
- runs a child review-consensus loop where one agent reviews the code and a second agent audits that review before any fix is attempted
- records terminal iteration status when review consensus decides the branch is already stable enough to stop without another fix round
- commits each accepted remediation round so the branch history shows how the code stabilized over time
- can launch a rabbit-hole investigation subrun when an accepted round suggests wider drift before deciding whether to continue
- writes a final run summary for a single human signoff at the end instead of human curation on every round

## Review Only

Use this when the run should stop at review output and should not implement
fixes.

```mermaid
flowchart TD
    A[review_scan] --> B[write_findings]
    B --> C{human_curate_findings}
    C -- rescan --> A
    C -- accepted --> D[complete]
```

Process summary:

- scans the repository for findings
- rewrites those findings into a human-readable summary
- lets a human either accept the review output or request one more scan

## QA Gap Guard

Use this when the run should audit QA and testing posture, then produce a clean
follow-up package for later engineering work.

```mermaid
flowchart TD
    A[detect_qa_gaps] --> B[write_qa_gap_summary]
    B --> C{human_curate_gaps}
    C -- rescan --> A
    C -- complete --> Z[complete]
    C -- accepted --> D[capture_selection]
    D --> E[write_gap_closure_brief]
    E --> F[write_engineering_feedback]
    F --> Z
```

Process summary:

- detects meaningful QA gaps against the current request and governance artifact
- summarizes the gaps for human curation
- packages the selected gaps into structured state
- emits a remediation brief and engineering feedback instead of applying code changes

## Architecture Guard

Use this when the source of truth is an architecture governance contract and the
run should detect and remediate selected contract drift.

```mermaid
flowchart TD
    A[detect_boundary_drift] --> B[write_boundary_summary]
    B --> C{human_curate_findings}
    C -- rescan --> A
    C -- complete --> Z[complete]
    C -- accepted --> D[capture_selection]
    D --> E[plan_guardrail_fix]
    E --> F[implement_guardrail_fix]
    F --> G{judge_guardrail_fix}
    G -- replan --> E
    G -- accept --> H{human_signoff}
    G -- escalate --> X[escalate]
    H -- changes_requested --> E
    H -- accepted --> Z
```

Process summary:

- detects drift against the architecture contract and related governance inputs
- summarizes the findings and waits for human curation
- plans and implements fixes only for the selected violations
- requires both machine judgement and final human signoff before completion
