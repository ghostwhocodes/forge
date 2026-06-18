# Forge Quickstart

This guide is for people and agents operating Forge against a real repository.
It keeps to the common execution path. For the full command, runtime, and
data-model reference, see [docs/USAGE.md](docs/USAGE.md).

## Before You Start

- Run the installed CLI as `forge`, or use `./forge-dev` from this repo to
  build and run the Java launcher.
- Do not run `./forge-dev` concurrently. It rebuilds shared `target/` state;
  for parallel smoke checks, run `mvn -q -DskipTests package` once and then
  use `target/forge`.
- Prefer absolute repo paths.
- Treat each run directory as durable state; do not edit files inside it by
  hand.
- Make sure workflow runner commands exist on `PATH`. Template validation
  reports declared tool availability, and run initialization fails before
  publication if a required tool is missing.
- The shipped implementation-oriented templates expect a `codex` runner for
  agent steps.

## Pick A Template

Built-in templates:

- `implement-change`: general implementation flow with planning, critique,
  review, remediation, documentation, and signoff
- `review-only`: discovery-only review that stops for human curation
- `review-and-fix`: review first, then apply selected fixes
- `auto-review-and-fix`: unattended multi-round review/fix flow with internal
  review consensus and checkpoint commits
- `architecture-guard`: repository guardrail against architecture drift
- `qa-gap-guard`: testing and verification gap review

Inspect them with:

```bash
forge template list
```

`forge intake review --fix` defaults to `review-and-fix`. Use
`--template=auto-review-and-fix` when you want unattended review/fix rounds
with internal review consensus and checkpoint commits.

For unattended runs, optional JSON config can be embedded in the request with
`--config=PATH` or `--config-json=JSON`. The `auto-review-and-fix` template
uses `auto_review_and_fix.max_rounds` as the accepted-round cap, and Codex
dispatches use `codex.model` plus `codex.reasoning_effort` when present.

## Fastest Demo

Run the checked-in minimal workflow without scaffolding another repository:

```bash
tmp=$(mktemp -d)

forge run init \
  --spec=docs/examples/minimal/software-loop.json \
  --runs="$tmp/runs" \
  --slug=sample \
  --artifact=request=docs/examples/minimal/request-simple.json

forge run next --runs="$tmp/runs" --slug=sample
forge run show-progress --runs="$tmp/runs" --slug=sample
```

`run next` is the safe inspection step. Use `run auto` only when the workflow's
runner commands are available in your environment.

## Run Forge Against A Real Repository

1. Scaffold repo-local templates:

```bash
repo=/abs/path/to/target-repo
forge scaffold repo --repo="$repo"
```

This writes editable workflow assets under `$repo/.forge/`.

By default that scaffolds `implement-change`, `review-only`, and
`review-and-fix`. Add `--all` to scaffold every built-in template, or use
`--template=auto-review-and-fix`, `--template=architecture-guard`, or
`--template=qa-gap-guard` when you want only selected extras scaffolded too.

2. Create a normalized request packet:

```bash
tmp=$(mktemp -d)

forge intake simple \
  --repo="$repo" \
  --goal="Add YAML support" \
  --check="mvn -q verify" \
  --out="$tmp"
```

Forge writes `$tmp/artifacts/request.json` and reports the selected workflow.

3. Initialize a run:

```bash
forge run init \
  --spec="$repo/.forge/templates/implement-change/workflow.json" \
  --runs="$tmp/runs" \
  --slug=yaml-support \
  --artifact=request="$tmp/artifacts/request.json"
```

Initialization is staged. Forge writes the frozen run package under
`$tmp/runs/.staging/yaml-support` and only publishes
`$tmp/runs/yaml-support` after the spec, interface, artifacts, metadata, and
init events are durable. A failed init leaves no partial run at the final slug,
so the same command can be retried after fixing the input problem.

The example uses the conventional `request` artifact name. Workflows may also
declare a different single interface input, such as `task_packet`; in that
case Forge marks that artifact as the request packet and uses it for
`$request.repo_root` and Codex request configuration.

4. Drive the run:

```bash
forge run auto --runs="$tmp/runs" --slug=yaml-support --watch=summary
```

`run auto` advances prepared dispatches and subruns, but it still stops for
explicit human-review nodes.

## Day-To-Day Run Commands

Common inspection commands:

- `forge run next --runs=DIR --slug=NAME`
- `forge run status --runs=DIR --slug=NAME`
- `forge run show-progress --runs=DIR --slug=NAME`
- `forge run watch --runs=DIR --slug=NAME --summary --until-terminal`
- `forge run show-human --runs=DIR --slug=NAME`

Common mutation commands:

- `forge run auto --runs=DIR --slug=NAME --watch=summary`
- `forge run exec-dispatch --runs=DIR --slug=NAME [--tee]`
- `forge run complete-dispatch --runs=DIR --slug=NAME --dispatch-id=ID`
- `forge run fail-dispatch --runs=DIR --slug=NAME --dispatch-id=ID --reason=TEXT`
- `forge run resolve-human --runs=DIR --slug=NAME --field=key=value`
- `forge run recover --runs=DIR --slug=NAME`

Use `exec-dispatch` plus `complete-dispatch` or `fail-dispatch` when you want
manual control instead of `run auto`.

## Human Review Steps

When a run pauses for human input:

```bash
forge run show-human --runs=DIR --slug=NAME
forge run resolve-human --runs=DIR --slug=NAME --field=key=value --dry-run
forge run resolve-human --runs=DIR --slug=NAME --field=key=value
```

If the workflow defines a human-review notification hook, deliver that pending
notification dispatch first, then resolve the human fields.

Notification hook paths and hook `cwd` values are frozen during `run init`
against the workflow file's source directory. That means repo-local hooks keep
working even though later notification dispatches are prepared from inside the
durable run directory.

## Recovery And Inspection

- `forge run recover` republishes pending projection backlog entries after an
  interruption.
- `dispatch/output/*.stdout`, `*.stderr`, and `*.progress.ndjson` hold machine
  step output.
- `events.ndjson` is the append-only execution log.
- `spec.json`, `workflow-ir.json`, and `workflow-interface.json` are the frozen
  workflow inputs for the run.
- `subruns/frozen-workflows/` stores child workflow snapshots. When a child
  completes, any imported child export marked `required=true` must exist;
  otherwise Forge records the parent subrun as failed.

## Where To Go Deeper

- [docs/examples/minimal/README.md](docs/examples/minimal/README.md)
- [docs/examples/hooks/README.md](docs/examples/hooks/README.md)
- [docs/EXECUTION.md](docs/EXECUTION.md)
- [docs/USAGE.md](docs/USAGE.md)
