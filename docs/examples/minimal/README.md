# Minimal Workflow Sample

This directory is the checked-in minimal runnable sample for Forge's current
direct-spec workflow.

It contains:

- `software-loop.json`
- `request-simple.json`
- `request-review.json`
- `notify-hook`

It is documentation-first sample material for:

- understanding the intended authored workflow shape
- initializing a real run immediately
- adapting the sample notification hook to a real repository

The sample graph models this structured workflow:

1. `plan`
2. `critique`
3. `human_review_plan`
4. `implement`
5. `evidence`
6. `judge`

Terminal targets:

- `__complete__`
- `__escalate__`

The sample is intentionally small, but it still shows the current structured
version 2 model:

- node-local `route` tables instead of a top-level routing edge list
- explicit declared loops for `plan_rework`, `impl_retry`, and `replan`
- human routing by `plan_status`
- judge routing by `decision`

## What The Sample Is For

This is not a shipped built-in template.

It is a documentation sample for:

- the JSON schema shape
- the intended artifact-driven execution style
- the expected use of `forge run ...`

For the live command contract, read:

- `../../README.md`
- `../../docs/USAGE.md`

The request examples show the current normalized intake shape used by:

- `../../docs/reference/request.schema.json`
- `../../docs/reference/request-schema.md`
- `../../templates/implement-change/workflow.json`
- `../../templates/review-only/workflow.json`
- `../../templates/review-and-fix/workflow.json`

These checked-in request JSON files are schematic examples.

- `request-simple.json` and `request-review.json` intentionally use placeholder repo paths
- for a real repository exercise, prefer `forge intake ...` so `repo_root` and workflow selection are generated from the actual request
- the reference schema is descriptive documentation of the current intake
  output shape, not a runtime-enforced validation step today

You can generate the same shape with the CLI:

```bash
tmp=$(mktemp -d)
./forge-dev intake simple \
  --repo=/abs/path/to/target-repo \
  --goal="Add YAML support" \
  --out="$tmp"
```

That writes `$tmp/artifacts/request.json` and returns the matching workflow path.

## How To Run It

Create a throwaway runs directory and initialize a run:

```bash
tmp=$(mktemp -d)
./forge-dev run init \
  --spec=docs/examples/minimal/software-loop.json \
  --runs="$tmp/runs" \
  --slug=sample \
  --artifact=request=docs/examples/minimal/request-simple.json
```

Get the first action:

```bash
./forge-dev run next --runs="$tmp/runs" --slug=sample
```

The action will be one of:

- `dispatch`
- `human_review`
- `subrun`
- `complete`
- `escalate`
- `noop`

If the frozen spec includes notification hooks, `next` may first produce a notification `dispatch`:

- before a `human_review` action when `human_review_hook` is configured
- before terminal `complete` or `escalate` actions when the corresponding terminal hook is configured

## Human Step

The operator loop around a human node is:

1. `run next`
2. If that projected action is a notification dispatch, acknowledge it
3. `run show-human`
4. optional `resolve-human --dry-run`
5. final `resolve-human`

When the run reaches `human_review_plan`, inspect it with:

```bash
./forge-dev run show-human \
  --runs="$tmp/runs" \
  --slug=sample
```

Validate the response without mutating the run:

```bash
./forge-dev run resolve-human \
  --runs="$tmp/runs" \
  --slug=sample \
  --field=plan_status=approved \
  --dry-run
```

Then record the final decision:

```bash
./forge-dev run resolve-human \
  --runs="$tmp/runs" \
  --slug=sample \
  --field=plan_status=approved
```

Or send it back for rework:

```bash
./forge-dev run resolve-human \
  --runs="$tmp/runs" \
  --slug=sample \
  --field=plan_status=rework
```

Canonical built-in human examples:

- `templates/review-only/workflow.json`: `finding_selection_status=accepted` or `finding_selection_status=rescan`
- `templates/review-and-fix/workflow.json`: `finding_selection_status`, `selected_finding_ids`, and `selection_notes`

## Machine Step

When `next` returns a `dispatch`, execute the returned command and write the declared output artifacts.

For `agent` and `judge` nodes, prompt delivery is controlled by the agent definition:

- `prompt_delivery: "env_path"`: the dispatch env includes `FORGE_PROMPT_FILE`
- `prompt_delivery: "stdin_file"`: the dispatch also includes `stdin_path`
- `prompt_delivery: "argv_path"`: Forge appends the prompt path to the dispatched command argv

Then acknowledge the finished dispatch:

```bash
./forge-dev run exec-dispatch \
  --runs="$tmp/runs" \
  --slug=sample \
  --tee

./forge-dev run show-progress \
  --runs="$tmp/runs" \
  --slug=sample

./forge-dev run watch \
  --runs="$tmp/runs" \
  --slug=sample \
  --until-terminal

./forge-dev run complete-dispatch \
  --runs="$tmp/runs" \
  --slug=sample \
  --dispatch-id=<ID>
```

During execution, Forge writes:

- `dispatch/output/<dispatch_id>.stdout`
- `dispatch/output/<dispatch_id>.stderr`
- `dispatch/output/<dispatch_id>.progress.ndjson`
- `dispatch/output/<dispatch_id>.result.json`

Dispatches also receive `FORGE_PROGRESS_FILE` so runners can append NDJSON progress updates for `show-progress`.

For foreground runs, `exec-dispatch --tee` mirrors child output to parent stderr and `auto --watch` or `auto --watch=jsonl` renders the same progress payload while the runtime advances the run.

For `judge` nodes, Forge reads routing decisions from top-level scalar fields in JSON output artifacts such as `artifacts/judge.json`.

If you need to override or supplement those extracted decisions, pass them explicitly:

```bash
./forge-dev run complete-dispatch \
  --runs="$tmp/runs" \
  --slug=sample \
  --dispatch-id=<ID> \
  --decision=decision=accept
```

Possible `judge` decisions in the sample:

- `accept`
- `retry`
- `replan`
- `escalate`

## Notifications

The sample spec includes a `notifications` block:

- `default_hook.path = "notify-hook"`
- `human_review_hook.path = "notify-hook"`

At `run init`, Forge resolves that relative path against the source spec location and freezes the absolute hook path into the run's `spec.json`.

When a run reaches a configured human-review checkpoint or a terminal target, `run next` prepares a durable notification dispatch with:

- `dispatch_kind = "notification"`
- `runner = "notification_hook"`
- `command = [hook_path, message]`
- env vars including `FORGE_LEVEL`, `FORGE_MESSAGE`, `FORGE_SLUG`, `FORGE_STAGE`, `FORGE_RUN_DIR`, `FORGE_SPEC_FILE`, and `FORGE_EVENTS_FILE`

After the hook runs, acknowledge it with the same command:

```bash
./forge-dev run exec-dispatch \
  --runs="$tmp/runs" \
  --slug=sample

./forge-dev run complete-dispatch \
  --runs="$tmp/runs" \
  --slug=sample \
  --dispatch-id=notify-complete
```

## Why The Example Matters

The runtime is centered on:

- frozen run spec
- frozen workflow IR
- append-only events
- explicit artifact flow
- explicit human decisions
- explicit routing keys
- explicit loop declarations and budgets

If a fresh model or engineer needs to understand the intended behavior quickly, start with:

- `../../docs/DESIGN.md`
- `software-loop.json`
- `../../src/main/java/dev/llaith/forge/runtime/run/`
- `../../src/main/java/dev/llaith/forge/workflow/{event,state,reducer,runner,planner}/`
- `../../src/main/java/dev/llaith/forge/spec/`

Captured parity fixtures live under `../../src/test/resources/parity/`; the
Java packages are the active implementation.
