# Forge Execution Contract

This note describes the process-launch side of the runtime.

## Separation

Forge has two distinct concerns:

1. workflow semantics
2. execution semantics

Workflow semantics decide which node is active and which action should happen next.

Those semantics are evaluated from the frozen run package captured at `run init`: `spec.json`, `workflow-ir.json`, and `workflow-interface.json`.

Execution semantics define how a prepared dispatch is launched.

`run init` is transactional at the run-directory level. Forge builds the frozen
run package under `.staging/<slug>` first, including copied seed artifacts and
init events, then promotes it to the final run directory only after those files
are durable. A failed init does not leave a partial final run directory behind.

## Supported Runners

Prepared dispatches currently use one of four explicit runners:

- `codex`
- `agent`
- `command`
- `notification_hook`

`codex` is a normal runtime runner, not a separate task-management mode. It
uses the same dispatch contract as other runners while preserving explicit
runner identity in the frozen run package.

For `codex` runner dispatches whose command is `codex exec`, Forge also reads
the frozen request artifact for optional `workflow_config.codex` overrides. If
present, `codex.model` is appended as `--model <value>`, and
`codex.reasoning_effort` is appended as a Codex `model_reasoning_effort`
configuration override. Supported effort values are `low`, `medium`, `high`,
and `xhigh`.

The request artifact is role-based. If a workflow interface has exactly one
input, `run init` marks that artifact as the request packet even when the
artifact name is not literally `request`. For workflows without a single
interface input, Forge uses the conventional `request` artifact fallback.

## Current Dispatch Contract

A projected dispatch includes:

- `dispatch_id`
- `dispatch_kind`
- `runner`
- `command`
- `cwd`
- `env`
- `input_paths`
- `output_paths`
- `stdin_path`
- `message`
- `timeout_ms`

Different runners must not reinterpret this contract in incompatible ways.

`input_paths`, `output_paths`, and `stdin_path` are executor-facing absolute
paths. `output_paths` point at canonical artifact paths under the run
directory, not the user-visible projection paths from the frozen spec. Visible
artifact paths are republished from canonical outputs after commit and may lag
behind durable workflow state until `forge run recover` is used.

`cwd` is a concrete execution path in the projected dispatch. Repo-local hook
paths and hook/command/agent `cwd` values are frozen against the source
workflow file during `run init`, so later dispatch preparation does not try to
resolve authored relative paths under the run directory. Repo-targeted
workflows may derive execution cwd from the request artifact by using the
reserved token `$request.repo_root` in the frozen spec; Forge resolves that
token to the absolute `request.repo_root` path before launch.

Every prepared dispatch also carries `FORGE_PROGRESS_FILE`, pointing at the
absolute run-owned progress file whose run-relative suffix is:

- `dispatch/output/<dispatch_id>.progress.ndjson`

Forge streams process stdout and stderr directly to run-owned files:

- `dispatch/output/<dispatch_id>.stdout`
- `dispatch/output/<dispatch_id>.stderr`

The recorded execution payload stores byte counts and paths for those files.
Those recorded file paths are run-relative strings so restart and recovery do
not depend on whether the operator passed `--runs` as an absolute or relative
path, while child processes always receive absolute paths so node `cwd` cannot
move run-owned writes outside the run.
Durable operation lifecycle events use `operation_id` and `operation_kind` as
their required operation identity fields. Replay rejects missing or unsupported
fallback identity fields instead of deriving operation identity from `id` or
`kind`.
Before launching a prepared dispatch, `exec-dispatch` validates the durable
`operation_prepared` payload and fails closed if child-facing run-owned paths
are missing, non-text, blank, relative, or outside the run directory. A
prepared `cwd`, when present, must be a concrete absolute path; it may point at
a request workspace, but it cannot change where run-owned env, input, output,
progress, prompt, or stdin paths resolve.
Dispatch execution does not buffer full stdout or stderr in heap before
persisting them, and `--tee` replays the recorded files as streams.

This same dispatch contract is used for:

- node execution dispatches
- terminal completion/escalation notification hooks
- pending human-review notification hooks

Human-review notification hooks are best-effort operator aids.

- if a pending human-review hook succeeds, Forge records delivery and leaves the run waiting at the human node
- if a pending human-review hook fails, Forge records the failed attempt but still leaves the run curatable through `show-human` and `resolve-human`

Terminal notification hooks remain failure-significant because they are part of the terminal run outcome.

Packaged launchers resolve built-in templates from `target/forge-assets` in the
release layout. `FORGE_SOURCE_ROOT` remains an explicit development override,
but release template lookup no longer depends on finding the repository source
tree from the process working directory.

For `command` nodes, Forge also injects runtime env vars that make artifact handoff explicit:

- `FORGE_RUN_DIR`
- `FORGE_SPEC_FILE`
- `FORGE_EVENTS_FILE`
- `FORGE_PROGRESS_FILE`
- `FORGE_CURRENT_NODE`
- `FORGE_WORKFLOW_ID`
- `FORGE_RUN_ID`
- `FORGE_SLUG`
- `FORGE_RUN_SLUG`
- `FORGE_INPUT_<ARTIFACT_NAME>`
- `FORGE_OUTPUT_<ARTIFACT_NAME>`
- `FORGE_DECISION_<KEY>`

Artifact names are uppercased and normalized with underscores.
Decision keys use the same normalization.

Example:

- `implementation_report` -> `FORGE_INPUT_IMPLEMENTATION_REPORT`
- `evidence` -> `FORGE_OUTPUT_EVIDENCE`
- `finding_selection_status` -> `FORGE_DECISION_FINDING_SELECTION_STATUS`

`FORGE_INPUT_<ARTIFACT_NAME>` and `FORGE_OUTPUT_<ARTIFACT_NAME>` point at
absolute canonical run-owned artifact paths, not at declared visible projection
paths.

## Reference Executor

`forge run exec-dispatch --runs=DIR --slug=NAME [--tee]`

It:

1. reloads the frozen run package
2. replays `events.ndjson`
3. projects the current action
4. requires that the action is `dispatch`
5. launches the declared process using:
   - `command`
   - `cwd`
   - `env`
   - `stdin_path` when present
6. writes:
   - `dispatch/output/<id>.stdout`
   - `dispatch/output/<id>.stderr`
   - `dispatch/output/<id>.progress.ndjson`
   - `dispatch/output/<id>.result.json`
7. emits `DispatchHeartbeat` events while the child remains active so observers can distinguish quiet work from stalled work
8. when `--tee` is enabled, mirrors child stdout and stderr to parent stderr without breaking structured stdout output

It appends `operation_succeeded` or `operation_failed` for the process
execution result, including stale-result recovery when a completed result file
is already durable. It does not complete the pending operation or apply
workflow routing; callers still finish the dispatch with `complete-dispatch` or
`fail-dispatch`.

`exec-dispatch` is only for `dispatch` actions. Prepared `subrun` actions are driven by `forge run auto`, which uses the same frozen parent and child contracts on resume, including the frozen child `workflow-interface.json`.
The prepared subrun operation carries the selected `import_artifacts` mapping.
During reconciliation, required child exports are derived from the frozen child
interface and checked against that mapping. If a child run completes
successfully without producing one of those required imported exports, parent
reconciliation records the subrun as failed and routes with
`subrun_status=failed`. Optional imports may be absent.

## Observer Surface

Use:

- `forge run show-progress --runs=DIR --slug=NAME` for a single projected snapshot
- `forge run watch --runs=DIR --slug=NAME [--interval-ms=1000] [--jsonl|--summary] [--until-terminal]` for repeated read-only observation
- `forge run auto --runs=DIR --slug=NAME [--watch|--watch=jsonl|--watch=summary] [--tee]` to drive the run while rendering the same progress model

`watch` emits progress snapshots continuously by default; add `--until-terminal` when a script should return after the first terminal snapshot. `--watch=summary` is the lower-noise progress mode for humans, while `--watch=jsonl` remains the best fit for scripts and tooling. `auto` emits one final full-fidelity human-review or terminal frame when the run stops advancing, and `auto --watch` also streams progress frames while work is active. Combining `--watch` with `--tee` is supported, but it intentionally mixes observer snapshots with the child process's live output and is therefore the noisiest operator experience.

Those observer surfaces cover more than dispatches. Structured workflows may expose current route-field and loop-state details, and subrun execution may expose child lineage and terminal reconciliation state.

When projection publication lags, the observer payloads may also report
`projection_backlog` entries. That backlog is advisory repair work; it does not
change run terminality or the projected workflow action.

## Completion And Failure

Use:

- `complete-dispatch` for successful node or notification dispatches
- `fail-dispatch` for failed node dispatches

This keeps execution mechanics separate from workflow mutation while still giving the runtime one canonical process-launch implementation.
