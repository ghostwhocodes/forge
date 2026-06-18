# Forge Context

## Terms

### Workflow Replay

Definition: Deriving `DerivedRunState` from the append-only run event log.

Important invariants:

- Replay is workflow semantics, not storage mechanics.
- Replay rejects malformed or unsupported durable events instead of guessing.
- Callers should cross the workflow reducer seam to derive run state.

Related terms: Run Event Log, Workflow Reducer, Derived Run State.

### Dispatch Lifecycle

Definition: The runtime behavior that advances one prepared dispatch through payload validation, process launch, progress observation, result recording, stale recovery, idempotent replay, and output streaming.

Important invariants:

- Prepared dispatch payloads must fail closed when durable run-owned paths, cwd, env, timeout, or result shape are invalid.
- Re-running dispatch execution must not duplicate side effects after a completed or live execution is already recorded.
- Tests may use an internal adapter for process execution, but product-level behavior must still be covered through the real command path.

Related terms: Runtime Engine, Prepared Dispatch, Run Event Log.

### Subrun Lifecycle

Definition: The runtime behavior that advances one prepared child workflow run, including child run initialization, recursive advancement, terminal reconciliation, required import validation, imported artifact metadata, and parent route decision recording.

Important invariants:

- Subrun behavior remains internal to the canonical runtime surface.
- Parent and child run state must remain restartable from frozen workflow files and event logs.
- Required child exports are enforced during reconciliation.

Related terms: Runtime Engine, Workflow Interface, Artifact Store.

### Workflow Spec Compiler

Definition: The module responsibility that turns authored workflow source into the frozen run package facts used by runtime execution: resolved workflow spec, workflow IR, workflow interface, and canonical source hash.

Important invariants:

- Runtime and template callers should not need to know the ordering of materialization, validation, lowering, interface derivation, or hashing phases.
- The compiler must preserve one canonical version 2 structured workflow surface.
- Compatibility shims for removed prerelease spec shapes are not part of this release contract.

Related terms: Workflow Spec, Workflow IR, Workflow Interface, Template Bundle.
