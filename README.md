# Forge

<p align="center">
  <img src="./.github/assets/banner.webp" alt="Forge banner">
</p>

<p align="center">
  <a href="https://ghost-who-codes.blog/open-source/forge/">Project page on Ghost Who Codes</a>
</p>

Forge is a Java CLI and runtime for durable, artifact-driven workflows.
It is built for long-running engineering tasks that need:

- frozen workflow inputs
- append-only execution history
- explicit machine, human, and subrun boundaries
- safe interruption and recovery

Forge exposes one canonical Java workflow/runtime surface in the repo:

- workflow model: `src/main/java/dev/llaith/forge/workflow/{event,state,reducer,runner,planner}`
- workflow package compiler: `src/main/java/dev/llaith/forge/spec/WorkflowSpecCompiler.java`
- runtime entrypoints: `src/main/java/dev/llaith/forge/runtime/run/`
- runtime dispatch and subrun internals: `src/main/java/dev/llaith/forge/runtime/{dispatch,subrun}/`
- run format: frozen workflow files plus `events.ndjson`

## Audience Guide

- Operators and evaluators: start with [QUICKSTART.md](QUICKSTART.md)
- Contributors and agents changing Forge itself: read [AGENTS.md](AGENTS.md)
- Detailed command and runtime reference: [docs/USAGE.md](docs/USAGE.md)
- Dispatch and runner contract: [docs/EXECUTION.md](docs/EXECUTION.md)
- Runtime/design notes: [docs/DESIGN.md](docs/DESIGN.md)
- Local release verification: [docs/RELEASE.md](docs/RELEASE.md)

## Quick Start

Use the installed CLI as `forge`. From this repo, use `./forge-dev`, which
builds the Maven package and runs the Java launcher.

Do not run `./forge-dev` concurrently. It rebuilds shared `target/` state
before launching Forge. For parallel smoke checks, run
`mvn -q -DskipTests package` once and then use `target/forge` from each shell.

List built-in templates:

```bash
forge template list
```

Run the minimal checked-in sample:

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

For a real repository:

```bash
repo=/abs/path/to/target-repo
tmp=$(mktemp -d)

forge scaffold repo --repo="$repo"

forge intake simple \
  --repo="$repo" \
  --goal="Add YAML support" \
  --out="$tmp"

forge run init \
  --spec="$repo/.forge/templates/implement-change/workflow.json" \
  --runs="$tmp/runs" \
  --slug=yaml-support \
  --artifact=request="$tmp/artifacts/request.json"

forge run auto --runs="$tmp/runs" --slug=yaml-support --watch=summary
```

Bare `forge scaffold repo --repo=...` currently scaffolds
`implement-change`, `review-only`, and `review-and-fix`. Use
`forge scaffold repo --repo=... --all` to scaffold every built-in template, or
add `--template=` to include only selected extras such as
`auto-review-and-fix`, `architecture-guard`, or `qa-gap-guard`.

## How Runs Work

Each run freezes the authored workflow into:

- `spec.json`
- `workflow-ir.json`
- `workflow-interface.json`
- `events.ndjson`

`forge run init` writes that package under `.staging/<slug>` first and
publishes the real run directory only after the frozen workflow files,
seed artifacts, metadata, and init events are durable. If initialization fails,
Forge removes the staged directory instead of leaving a partial run at the
requested slug.

Runtime state is rebuilt from events. The runtime then projects one current
action:

- `dispatch`
- `human_review`
- `subrun`
- `complete`
- `escalate`
- `noop`

Operators can inspect with `run next`, `run status`, `run show-progress`, and
`run show-human`, or advance with `run auto`, `run exec-dispatch`,
`run complete-dispatch`, `run fail-dispatch`, and `run resolve-human`.

Execution-facing paths are frozen at init. Repo-local notification hooks and
`cwd` values are resolved against the source workflow location, while the
reserved `$request.repo_root` token is resolved at dispatch time from the
request artifact. If a workflow declares exactly one interface input, Forge
treats that seed artifact as the request packet even when it is not named
`request`; otherwise the conventional `request` artifact name is used.

Subruns use frozen child workflow snapshots. Required child interface exports
must be produced when a child completes successfully; if a required imported
artifact is absent, parent reconciliation records `subrun_status=failed` and
routes from that failure state.

Workflow specs can declare external runner dependencies with
`tool_requirements`. `forge template validate` reports availability on `PATH`,
and `forge run init` refuses to publish a run when required tools are missing.

## Built-In Templates

`forge template list` currently ships:

- `implement-change`
- `review-only`
- `review-and-fix`
- `auto-review-and-fix`
- `architecture-guard`
- `qa-gap-guard`

`forge intake review --fix` defaults to `review-and-fix`. Use
`--template=auto-review-and-fix` when you want unattended review/remediation
rounds instead.

## Development

Primary validation gates:

- `mvn -q test`
- `mvn -q verify`
- `just ci`

`just ci` is the repo closeout gate. It runs the Maven `verify` phase, which
enforces Java 25/Maven versions, warning-denied compilation with Error Prone,
unit and integration tests, JaCoCo coverage, SpotBugs, packaging, and launcher
generation. For unattended runs, prefer `just ci-logged`, which writes logs
under `target/validation/`.

## Repository Layout

- `src/main/java/`: CLI, runtime, workflow, spec, intake, template, storage
- `src/test/java/` and `src/test/resources/`: Java tests and fixtures,
  including captured parity baselines under `src/test/resources/parity/`
- `templates/`: built-in workflow bundles
- `docs/`: user-facing reference, design notes, runnable examples
- `justfile`: Java validation aliases such as `just ci` and `just ci-logged`

## License

Copyright 2026 Nos Doughty.

Licensed under either of:

- Apache License, Version 2.0, see [LICENSE-APACHE](LICENSE-APACHE)
- MIT license, see [LICENSE-MIT](LICENSE-MIT)

at your option.

## Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this work by you, as defined in the Apache License, Version
2.0, shall be dual licensed as above, without any additional terms or
conditions.
