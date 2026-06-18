# Forge Usage Reference

This is the authoritative command and runtime reference for Forge.

## CLI Surface

Top-level commands:

- `forge scaffold repo ...`
- `forge intake ...`
- `forge template ...`
- `forge run ...`
- `forge spec ...`
- `forge version`

## Scaffold Commands

```bash
forge scaffold repo --repo=PATH [--all] [--template=NAME...] [--template-path=PATH...] [--workflow=PATH...] [--force]
```

Current scaffold behavior:

- writes repo-local workflow assets under `.forge/`
- copies built-in templates or explicit template bundles into
  `.forge/templates/<template-id>/`
- with no explicit `--template` or `--template-path`, scaffolds only the
  default bundle set: `implement-change`, `review-only`, and `review-and-fix`
- `--all` scaffolds every built-in template bundle in one pass
- preserves executable bits for copied hooks and scripts on Unix
- skips existing files by default
- overwrites scaffolded files only with `--force`

## Intake Commands

```bash
forge intake simple --repo=PATH --goal="..." [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--out=DIR] [--config=PATH|--config-json=JSON]
forge intake spec --repo=PATH --spec=PATH [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal="..."] [--out=DIR] [--config=PATH|--config-json=JSON]
forge intake issue --repo=PATH (--file=PATH | --text="...") [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal="..."] [--out=DIR] [--config=PATH|--config-json=JSON]
forge intake review --repo=PATH [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal="..."] [--out=DIR] [--config=PATH|--config-json=JSON] [--fix]
```

Each intake command writes `artifacts/request.json` and returns the selected
workflow path.

Default mappings:

- `intake simple` -> `implement-change`
- `intake spec` -> `implement-change`
- `intake issue` -> `implement-change`
- `intake review` -> `review-only`
- `intake review --fix` -> `review-and-fix`

Explicit review-oriented built-in:

- `forge intake review --template=qa-gap-guard`
- `forge intake review --fix --template=auto-review-and-fix`

Selection rules:

- `--template=NAME` resolves a built-in bundle
- `--template-path=PATH` loads an external template bundle
- `--workflow=PATH` bypasses template selection
- `--template`, `--template-path`, and `--workflow` are mutually exclusive

Optional workflow config:

- `--config=PATH` embeds a JSON object from a file into `request.workflow_config`
- `--config-json=JSON` embeds an inline JSON object into `request.workflow_config`
- `--config` and `--config-json` are mutually exclusive

`review-and-fix` and `auto-review-and-fix` currently read template-specific
round policy config:

```json
{
  "review_and_fix": {
    "max_rounds": 3
  },
  "auto_review_and_fix": {
    "max_rounds": 10
  },
  "codex": {
    "model": "gpt-5.5",
    "reasoning_effort": "high"
  }
}
```

`review_and_fix.max_rounds` and `auto_review_and_fix.max_rounds` are accepted
round caps for their respective templates. `review-and-fix` derives
`max_round_continuations` as `max_rounds - 1`; the `outer_iteration` loop
resolves its budget from that frozen policy and checkpointed continuations
reuse the same loop instance. Accepted-round evaluations still write
`remaining_round_continuations` as status telemetry. For
`auto-review-and-fix`, when `max_round_continuations` is omitted, it is also
derived as `max_rounds - 1`.
`review_and_fix.max_rounds` must be at most `4294967296`;
`auto_review_and_fix.max_rounds` has the same limit when
`max_round_continuations` is omitted, so derived continuation budgets fit the
engine's `u32` loop-budget limit.
`codex.reasoning_effort` must be one of `low`, `medium`, `high`, or `xhigh`.

## Template Commands

```bash
forge template list
forge template show (--template=NAME | --path=PATH)
forge template validate (--template=NAME | --path=PATH)
forge template analyze-runs (--template=NAME | --path=PATH) --runs=DIR [--slug=NAME...] [--out=DIR]
forge template propose-update (--template=NAME | --path=PATH) --runs=DIR --out=DIR [--slug=NAME...]
```

Built-in templates resolve from the Forge source root exported by the packaged
launcher as `FORGE_SOURCE_ROOT`, so template commands do not depend on the
caller working directory.

`forge template validate` reports declared `tool_requirements` with per-command
availability. Missing tools do not make template validation fail; they are
reported so operators can prepare the environment before initializing a run.

Current built-ins:

- `implement-change`
- `review-only`
- `review-and-fix`
- `auto-review-and-fix`
- `architecture-guard`
- `qa-gap-guard`

`auto-review-and-fix` is the unattended review/remediation template. It keeps
the top-level `implement|complete|escalate` route shape, packages optional
repo-local governance notes from `ai/governance/` when present, and can trigger
a rabbit-hole investigation after accepted rounds start drifting beyond a
component's intended complexity budget.

## Run Commands

```bash
forge run init --spec=PATH --runs=DIR --slug=NAME [--artifact=KEY=PATH...] [--force]
forge run auto --runs=DIR --slug=NAME [--watch|--watch=pretty|--watch=jsonl|--watch=summary] [--tee]
forge run next --runs=DIR --slug=NAME
forge run recover --runs=DIR --slug=NAME
forge run status --runs=DIR --slug=NAME
forge run show-human --runs=DIR --slug=NAME
forge run show-progress --runs=DIR --slug=NAME
forge run watch --runs=DIR --slug=NAME [--interval-ms=1000] [--jsonl|--summary] [--until-terminal]
forge run exec-dispatch --runs=DIR --slug=NAME [--tee]
forge run complete-dispatch --runs=DIR --slug=NAME --dispatch-id=ID [--decision=KEY=VALUE...]
forge run fail-dispatch --runs=DIR --slug=NAME --dispatch-id=ID --reason=TEXT [--retryable=true|false]
forge run resolve-human --runs=DIR --slug=NAME --field=KEY=VALUE [--field=KEY=VALUE...] [--dry-run]
```

Observer notes:

- `watch` emits progress snapshots continuously by default; `--until-terminal`
  returns after the first terminal progress snapshot
- `--summary` is a lower-noise progress view
- `auto --watch=pretty`, `auto --watch=jsonl`, and `auto --watch=summary`
  select the auto progress renderer explicitly; bare `auto --watch` is the
  pretty renderer
- `auto` emits full-fidelity final human or terminal payloads with or without
  `--watch`

The supported CLI has one user-facing runtime surface. `forge run init` does
not expose an engine selector.

Init durability:

- `forge run init` writes all new run content under `.staging/<slug>` first
- the final `<runs>/<slug>` directory is published only after frozen workflow
  files, seed artifacts, metadata, and init events are durable
- a failed init removes the staged directory and leaves no partial final run
- `--force` replaces an existing final run only when the staged replacement is
  ready to publish

## Runtime Model

Each run contains:

- frozen `spec.json`
- frozen `workflow-ir.json`
- frozen `workflow-interface.json`
- append-only `events.ndjson`

Derived runtime state is rebuilt from events. The canonical runtime/workflow
implementation lives under:

- `src/main/java/dev/llaith/forge/workflow/event/`
- `src/main/java/dev/llaith/forge/workflow/state/`
- `src/main/java/dev/llaith/forge/workflow/reducer/`
- `src/main/java/dev/llaith/forge/workflow/runner/`
- `src/main/java/dev/llaith/forge/workflow/planner/`
- `src/main/java/dev/llaith/forge/spec/WorkflowSpecCompiler.java`
- `src/main/java/dev/llaith/forge/runtime/run/`
- `src/main/java/dev/llaith/forge/runtime/dispatch/`
- `src/main/java/dev/llaith/forge/runtime/subrun/`

Captured parity baselines for release regression tests live under
`src/test/resources/parity/`; they are test fixtures, not runtime sources.

The runtime projects exactly one current action:

- `dispatch`
- `human_review`
- `subrun`
- `complete`
- `escalate`
- `noop`

Supported run behavior includes:

- human-review pauses and resolution
- process-backed node dispatch
- notification hook dispatch
- subrun preparation, child handoff, reconciliation, and recovery
- projection-backlog republishment through `forge run recover`

Request artifact selection:

- if the derived `workflow-interface.json` has exactly one input, the matching
  seed artifact is marked as the request artifact even if it is not named
  `request`
- otherwise Forge falls back to the conventional `request` artifact name
- `$request.repo_root` and request-scoped Codex configuration are read from the
  artifact marked as the request input

## Workflow Spec Shape

Forge workflows use version 2 specs. Canonical shape:

```json
{
  "version": 2,
  "routing_mode": "structured",
  "tool_requirements": {
    "all_of": ["bash", "codex"],
    "any_of": [["jq", "python3"]]
  },
  "workflow_id": "default",
  "entry_node": "plan",
  "interface": {
    "inputs": [],
    "exports": {}
  },
  "agents": {},
  "nodes": [],
  "loops": [],
  "artifacts": {},
  "notifications": {}
}
```

Supported node kinds:

- `agent`
- `command`
- `judge`
- `human`
- `subrun`

Shared node fields:

- `id`
- `inputs`
- `outputs`
- `timeout_ms`
- `retry_policy`
- `message`

Top-level tool requirement fields:

- `all_of`: command names that must all be present on `PATH`
- `any_of`: alternative command groups; at least one command in each group
  must be present on `PATH`

Command names must be non-empty, duplicate-free, and use only ASCII letters,
digits, `.`, `_`, `+`, or `-`. `forge run init` checks the root workflow and
frozen subrun workflows before publishing the final run directory.

Agent definition fields:

- `display_name`
- `runner`
- `command`
- `cwd`
- `env`
- `prompt_delivery`

Prompt delivery modes:

- `env_path`
- `stdin_file`
- `argv_path`

Runner values:

- `agent`
- `codex`
- `command`
- `notification_hook`

## Routing And Loops

Preferred routing surface:

- workflow: `routing_mode`, `loops`
- node: `route`

Supported route shapes:

- `always { to }`
- `by_field { field, cases, default }`

Route case fields:

- `equals`
- `to`
- `continue_loop`

Loop fields:

- `id`
- `controller_node`
- `entry_node`
- `budget`
- `on_exhaust`

Loop budget kinds:

- `literal { max_iterations }`
- `artifact_field { artifact, field }`

Forge accepts version 2 workflow specs only. Specs default to structured routing
when `routing_mode` is omitted, and `routing_mode` may only be `structured`.
Version 1 specs, `routing_mode=raw_edges`, and top-level `edges` are rejected
instead of migrated or interpreted.

## Subrun Interface Rules

Subrun node fields:

- `workflow_ref`
- `request_artifact`
- `summary_artifact`
- `import_artifacts`

Workflow interface fields:

- workflow: `interface`
- interface: `inputs`, `exports`
- export: `required`, `terminal_only`

Current rules:

- child workflows must declare externally supplied inputs and parent-visible
  exports in `interface`
- current runtime support requires `terminal_only=true` for declared exports
- `request_artifact` must match a declared child input
- imported child artifacts must match declared child exports
- additional required child inputs are rejected at init
- required imported child exports are derived from the frozen child
  `workflow-interface.json` during reconciliation; the prepared subrun
  operation carries `import_artifacts`, not a separate required-import list
- missing required child exports fail parent reconciliation when the child ends
  in successful completion, recording `subrun_status=failed`
- optional imported child exports may remain absent

## Notifications

Notification hooks:

- `default_hook`
- `complete_hook`
- `escalate_hook`
- `human_review_hook`

The runtime treats notification delivery as an operation in the same canonical
event/state model as dispatches and subruns.

## Path Freezing

At `forge run init`, Forge freezes execution-facing workflow data into the run.
Current defaults are conservative:

- notification hook paths are resolved against the source spec directory and
  frozen as concrete paths
- hook, command-node, and agent `cwd` values are frozen the same way
- `$request.repo_root` is preserved as a reserved cwd token and resolved from
  the request artifact at dispatch preparation time
- prompt and command file references are resolved into the frozen workflow
- prepared child-process env, input, output, progress, and prompt paths are
  absolute run-owned paths, so a request-derived `cwd` cannot redirect writes
  away from the run directory

Use `forge spec freeze-paths` when you want to inspect or rewrite authored
workflow path groups directly:

```bash
forge spec freeze-paths --spec=PATH [--check|--stdout|--write] [--out=PATH] [--freeze=hooks,cwd,commands,env]
```

## Validation Commands

Primary repo gates:

- `mvn -q test`
- `mvn -q verify`
- `just ci`
- `just release-package`

`just ci` runs the Java Maven `verify` phase, including warning-denied
compilation, tests, coverage, SpotBugs, packaging, and launcher generation.

`just release-package` builds the shaded jar, `target/forge` launcher,
`target/forge-assets`, and `target/release/forge-0.1.0`.

Do not run `./forge-dev` concurrently. It rebuilds shared `target/` state
before launching Forge. For parallel smoke checks, run
`mvn -q -DskipTests package` once and then use `target/forge` from each shell.

For unattended validation with logs:

- `just ci-logged`
