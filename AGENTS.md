# Repository Guidelines

## Read Order

Always read `AGENTS.md` first.

For long-horizon tasks, prefer task-local documents under `ai/tasks/<task-slug>/`:

1. `ai/tasks/<task-slug>/Prompt.md`
2. `ai/tasks/<task-slug>/Contract.md`
3. `ai/tasks/<task-slug>/Plan.md`
4. `ai/tasks/<task-slug>/Implement.md`
5. `ai/tasks/<task-slug>/Documentation.md`
6. `ai/tasks/<task-slug>/Closeout.md`

Treat task-local `Prompt.md` as the product spec, task-local `Contract.md` as
the completion boundary, task-local `Plan.md` as advisory milestone
decomposition, task-local `Implement.md` as the operating runbook, task-local
`Documentation.md` as the live status log, and task-local `Closeout.md` as the
structured closeout evidence the wrapper verifies before completion.

## Project Structure And Module Organization

`forge` is a Java 25 Maven project centered on the CLI in
`src/main/java/dev/llaith/forge/Main.java`. Core Java code lives under
`src/main/java/dev/llaith/forge/`, with the main domains split into:

- `runtime/`: CLI runtime orchestration and durable run lifecycle
- `workflow/`: canonical workflow event, state, reducer, runner, and planner
- `spec/`: authored workflow parsing, lowering, validation, and interface logic
- `template/`: built-in and external template bundle handling
- `intake/`: request normalization into workflow input artifacts
- `storage/`: run persistence, event IO, and artifact storage

Captured parity fixtures live under `src/test/resources/parity/`. The active
CLI, dev shim, source tree, and closeout gate are Java/Maven only.

Built-in workflow bundles live in `templates/`. Runnable examples and user docs
live in `docs/` and `docs/examples/`. Java tests live under `src/test/java/`
with fixtures under `src/test/resources/`.

Important current architectural rule:

- the canonical workflow surface is
  `src/main/java/dev/llaith/forge/workflow/{event,state,reducer,runner,planner}`
- do not add a new public `workflow.v2` namespace or a second user-facing
  runtime API surface

## Build, Test, And Development Commands

- `mvn -q test` runs the Java unit test suite.
- `mvn -q verify` runs warning-denied compilation, tests, coverage,
  SpotBugs, packaging, integration-test hooks, and launcher generation.
- `mvn -q -DskipTests package` builds the shaded Java artifact and `target/forge` launcher.
- `./forge-dev <args>` builds the Maven package and runs the Java launcher.
- `just ci` is the Java closeout gate.
- `just ci-logged` runs the same gate and writes logs under `target/validation/`.
- `./codex/init-long-horizon-task.sh --task <slug>` scaffolds a reusable
  long-horizon Codex task under `ai/tasks/<slug>/`.
- `./codex/run-long-horizon.sh --task <slug>` runs the unattended long-horizon
  Codex wrapper for an existing task.
- `./codex/start-long-horizon-worktree.sh --task <slug>` creates a dedicated
  worktree and optionally launches the wrapper there.

Any code change is not complete until `just ci` has been run successfully in the
workspace after the edit.

`just ci` currently runs:

- `mvn -q verify`

## Coding Style And Naming Conventions

Use the established Java style in `src/main/java/dev/llaith/forge`: 4-space
indentation, explicit types where they clarify public contracts, and Jackson
models for structured JSON.

Naming:

- `lowercase` Java packages and descriptive class names matching their domain
- `camelCase` for methods, local variables, and fields
- `CamelCase` for types
- `SCREAMING_SNAKE_CASE` for constants

Implementation guidance:

- keep modules focused by domain
- prefer `ForgeException` for CLI-grade user failures and checked exception
  wrapping at IO/process boundaries
- delete dead compatibility code instead of hiding it behind new shims

## Global Quality Policy

All work must improve the codebase, not merely add or change behavior.

- If you touch an area of code, leave it better than you found it.
- Do not preserve or introduce legacy surfaces unless compatibility is explicitly required.
- Remove dead, transitional, duplicated, or inferior code instead of hiding it behind new abstractions.
- If adjacent code in the touched area is clearly suboptimal, refactor it as part of the change when safe to do so.
- If adjacent code has obvious performance issues, improve them as part of the same change when safe to do so.
- Prefer strong separation of concerns, explicit boundaries, low coupling, and focused modules.
- Reduce unnecessary reliance on third-party libraries where the same outcome can be achieved more simply or more maintainably.
- Favor clean canonical design over historical baggage, migration-minded structure, or temporary compatibility layers.
- “Out of scope” is not a valid reason to leave behind obvious local design or performance problems that could have been fixed safely during the change.
- If a worthwhile cleanup cannot be completed, document the blocker and the exact next action required.

A change is incomplete if it adds behavior while leaving obvious nearby maintainability or performance problems unaddressed without documented justification.

## Release Hardening Policy

Forge is still prerelease. Until the eventual squashed `v1` release project,
current commit history, dev-build artifacts, and earlier internal shapes are
not compatibility obligations.

- do not add or preserve compatibility, migration code, fallback readers or
  writers, dual-field payloads, dual-directory startup paths, or duplicate
  public/runtime API surfaces for prerelease or dev-build history unless the
  active task prompt requires it explicitly
- if a cleaner release contract requires changing current prerelease docs,
  tests, startup artifacts, stored payloads, or internal APIs, change them and
  keep one canonical surface instead of carrying legacy behavior forward
- when touching a code path, include adjacent cleanup for dead compatibility
  code, redundant abstractions, misleading build surface, unnecessary
  dependencies, obvious design debt, or missing invariants in the same change
  when safe; leaving nearby known debt in place without recording a concrete
  blocker is a review failure
- apply the same rule to performance: if touched or adjacent code has an
  obvious avoidable hotspot, repeated full replay, unbounded buffering, or
  similarly poor scaling behavior, improve it in the same change or record the
  blocker explicitly
- prefer fewer third-party dependencies, explicit internal boundaries,
  extracted domain services, and hexagonal/DDD-aligned separation of concerns;
  do not let god classes or utility-driven coupling grow when a change gives a
  reasonable chance to reduce them

## Long-Horizon Workflow

- Prefer creating one task directory per long-horizon effort under
  `ai/tasks/<task-slug>/`.
- Work one milestone at a time, in the active task plan order.
- Keep diffs scoped to the current milestone.
- Run the milestone validation commands before moving on.
- If validation fails, repair the failure before continuing.
- Run contract-required validations through
  `./codex/run-task-command.sh --task <slug> --validation-id <id> -- <command>`
  so `events.jsonl` records structured evidence.
- Update the active task `Documentation.md` after every milestone with:
  - status
  - decisions made
  - validation commands run
  - failures encountered and repairs applied
  - next milestone
- Keep `Closeout.md` current once requirement, review, or handoff evidence changes.
- Default unattended sandbox for this repo is `workspace-write`. Raise it only
  if the task proves it needs broader filesystem access.

## Testing Guidelines

Add tests with every behavior change, especially around:

- run lifecycle
- routing and loop semantics
- spec lowering and interface validation
- subrun preparation and reconciliation
- template resolution

Keep fast unit tests under `src/test/java/` adjacent to the Java package they
exercise. Put CLI or end-to-end regressions in Java integration-style tests or
focused command-path tests under `src/test/java/`.

Name tests for the behavior they lock down, for example
`projected_dispatch_preserves_native_codex_runner`.

When touching the runtime/workflow core, prefer at least one engine-level test
that exercises the real command path.

## Git Hygiene

- Prefer running long-horizon work from a dedicated Git worktree or a clean branch.
- If existing edits overlap with the current milestone, stop and record the
  conflict clearly in the active task `Documentation.md` before proceeding.

## Documentation Responsibilities

Keep the documentation split clean:

- `README.md`: orientation and repo map
- `QUICKSTART.md`: operator quickstart for humans and agents
- `docs/USAGE.md`: authoritative command and runtime reference
- `AGENTS.md`: contributor and coding workflow guidance
- `ai/tasks/README.md`: task layout for long-horizon Codex work
- `codex/LONG_HORIZON.md`: long-horizon wrapper flow, contract model, and helper scripts

When CLI behavior, workflow semantics, template behavior, or required
environment variables change, update the relevant docs in the same change.

## Commit And Pull Request Guidelines

Use short, imperative commit subjects such as
`Separate minimal test fixtures from docs examples`.

Keep each commit scoped to one change. PRs should explain:

- the behavior change
- affected commands, templates, or runtime semantics
- doc updates
- sample output or JSON when changing spec shape or generated artifacts

## Configuration Notes

Do not commit generated output from:

- `target/`
- `ai/workflow/`

When editing hooks or helper scripts under `templates/` or
`docs/examples/hooks/`, preserve executable behavior and document any new
`FORGE_*` environment requirements in `docs/USAGE.md`.

## Stop Conditions

Stop only for a real blocker, such as:

- required repository context is missing
- the environment cannot run the required validation commands
- the milestone cannot be completed without a product decision not covered by
  the active task `Prompt.md`

When stopped, record the blocker and the exact next action in the active task
`Documentation.md`.
