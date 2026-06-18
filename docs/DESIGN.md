# Forge Design

This document describes the current runtime design.

## Product Goal

Forge is a small orchestration engine for artifact-driven software work.

It should let a run proceed unattended for 30-90 minutes, with human intervention only at explicit review nodes or hard failure boundaries.

## Core Principles

1. Every step runs with fresh context.
2. All correctness-critical state is explicit and durable.
3. A run uses frozen workflow semantics captured at `init`.
4. Steps communicate through named artifacts, not conversational memory.
5. State is derived from an append-only event log.
6. Human intervention happens only at explicit `human` nodes or hard failure boundaries.

Human-review notifications are advisory rather than correctness-critical. A failed human-review notification must not erase or block the underlying human checkpoint.

Repo-targeted workflows should execute repo-facing agent work from the requested repository root, not from the Forge repo or another launcher cwd. For built-in intake-driven templates, this means binding execution cwd to the frozen request artifact's `repo_root`.

## Run Shape

```text
<runs>/<slug>/
  spec.json
  workflow-ir.json
  workflow-interface.json
  events.ndjson
  artifacts/
  dispatch/
    input/
    output/
  subruns/
    frozen-workflows/<snapshot>/
      spec.json
      workflow-ir.json
      workflow-interface.json
      source/
    children/<child-slug>/
      spec.json
      workflow-ir.json
      workflow-interface.json
      events.ndjson
      artifacts/
      dispatch/
```

`spec.json`, `workflow-ir.json`, and `workflow-interface.json` are frozen
during `run init`. The init command writes the full package under
`.staging/<slug>` first and promotes it to `<runs>/<slug>` only after the
frozen workflow files, seed artifacts, metadata, and init events are durable.

- `spec.json` remains authoritative for execution-facing node details such as dispatch configuration, prompt material, human field definitions, artifact declarations, and frozen child workflow references
- `workflow-ir.json` is authoritative for routing and loop semantics
- `workflow-interface.json` is authoritative for explicit workflow inputs and parent-visible exports
- if `workflow-interface.json` declares exactly one input, the matching seed
  artifact is the run's request artifact even if it is not named `request`

`events.ndjson` is the source of truth for mutable run state.

## Supported Node Kinds

Forge currently supports exactly five node kinds:

1. `agent`
2. `command`
3. `judge`
4. `human`
5. `subrun`

## Supported Dispatch Runners

Prepared dispatches currently use one of four explicit runners:

1. `codex`
2. `agent`
3. `command`
4. `notification_hook`

Runner choice is part of the frozen dispatch contract. It does not change the
graph model or the event model, but it does preserve which executor path the
runtime should use for a given prepared dispatch.

## Shared Node Fields

All nodes use:

- `id`
- `inputs`
- `outputs`
- `timeout_ms`
- `retry_policy`
- `message`

## Actions

The runtime projects one of:

- `dispatch`
- `human_review`
- `subrun`
- `complete`
- `escalate`
- `noop`

## Hard Invariants

1. A run is structurally immutable after `init`.
2. Resume and replay reload the frozen run package and replay `events.ndjson`.
3. If a pending dispatch exists, the same dispatch must be projected on resume.
4. If a pending subrun exists, the same prepared child contract must be projected on resume.
5. Completion, failure, route snapshots, loop lifecycle, and subrun lifecycle are recorded by appending events.
6. Derived state must be rebuildable from the event log alone.
7. The runtime never reloads live repo config or live workflow sources for an existing run.
8. Failed init attempts must not leave partial final run directories.
9. Relative execution-facing hook paths and `cwd` values are frozen against the
   source workflow location before the run spec is persisted, except for
   reserved runtime tokens such as `$request.repo_root`.

## Routing Model

Forge workflows use structured routing.

Structured mode uses node-local route tables rather than a global edge list:

- `always` routes directly to a node or terminal target
- `by_field` routes on one explicit recorded field with ordered cases and an optional default

Structured routing is paired with explicit loop declarations:

- loops name a `controller_node`
- loops name an `entry_node`
- loop budgets are either literal or read from a frozen artifact field
- loop exhaustion routes through an explicit `on_exhaust` target

The runtime records structured route-field snapshots durably before transition selection. Loop budgets are frozen per loop instance the first time continuation is evaluated, so replay and resume do not recompute them from a newer artifact interpretation.

The release contract accepts version 2 workflow specs only. Authored specs must
use structured routing; version 1 specs, `routing_mode=raw_edges`, and
top-level edge lists are rejected instead of migrated.

## Failure Classes

Keep these distinct:

1. deterministic runtime failure
   Example: missing artifact, malformed event, unreadable loop-budget artifact, mismatched frozen IR
2. node execution failure
   Example: command exits non-zero, invalid agent output
3. semantic failure
   Example: judge rejects implementation, human requests rework, child subrun escalates

## Dispatch And Subrun Semantics

Prepared work is durable.

External execution is separate from workflow mutation:

- `exec-dispatch` launches the current dispatch and writes process results
- `complete-dispatch` records successful dispatch completion and applies routing
- `fail-dispatch` records failed node execution and applies retry-budget logic

Subruns are first-class workflow actions:

- parent `run init` freezes referenced child workflows into the parent run
- parent-child compatibility is validated against the frozen child `workflow-interface.json`
- subrun preparation is materialized on disk before `operation_prepared` is appended
- `run auto` starts or reconciles prepared child runs and appends parent completion or failure events
- the prepared operation persists the selected `import_artifacts` mapping;
  required child exports are derived from the frozen child
  `workflow-interface.json` during reconciliation and enforced against that
  mapping when a child completes successfully; optional exports may remain
  absent
- a completed child that omits a required imported export reconciles as `subrun_status=failed`

## Testing Strategy

Scenario coverage is the primary validation strategy.

Important scenarios include:

- init to first dispatch
- dispatch completion to next node
- human review pause and resolve
- structured route selection from judge and human fields
- bounded loops with frozen literal and artifact-backed budgets
- replay rebuild of loop instance bindings and route-field snapshots
- resume while waiting on a dispatch
- resume while waiting on human review
- resume while a subrun is prepared or already started
- child completion, escalation, and failure routing through `subrun_status`
- missing output artifacts
- malformed judge output
- retry-budget escalation
- transition dead-end escalation
- replay rebuild of derived state

## Current Sample Workflow

The checked-in documentation sample graph in [`docs/examples/minimal/software-loop.json`](./examples/minimal/software-loop.json) is:

1. `plan`
2. `critique`
3. `human_review_plan`
4. `implement`
5. `evidence`
6. `judge`

Sample structured routing:

- `plan -> critique`
- `critique -> human_review_plan`
- `human_review_plan -> implement` when `plan_status=approved`
- `human_review_plan -> plan` via loop `plan_rework` when `plan_status=rework`
- `implement -> evidence -> judge`
- `judge -> __complete__` when `decision=accept`
- `judge -> implement` via loop `impl_retry` when `decision=retry`
- `judge -> human_review_plan` via loop `replan` when `decision=replan`
- `judge -> __escalate__` when `decision=escalate`

The sample loop budgets are workflow policy, not implicit back-edge counters:

- `plan_rework` controls plan revision rounds
- `impl_retry` controls implementation retry rounds
- `replan` controls return-to-plan rounds

## Transition Safety

After a node completes, Forge must either take a legal transition or append a durable `RunEscalated` event with a concrete reason.

That includes:

- no route defined for the completed node
- no structured route case matching the recorded field snapshot and no default route
- deterministic loop-budget resolution failure
