# Auto Review And Fix Template

This template is the shipped source bundle for `auto-review-and-fix`.

Use it for large branches that need several review/fix rounds
before the remaining issues stabilize, and where repeated human finding
curation would slow the loop down more than it helps.

The workflow removes the human from the middle of the cycle, but it does not
remove accountability:

- one agent reviews the current branch
- a second agent audits that review before any fix is accepted
- review consensus now distinguishes "real issue" from "right unattended response"
- accepted remediation rounds are checkpoint-committed so the branch history
  shows how the code stabilized
- repo-local governance notes under `ai/governance/` are packaged into review
  consensus when present
- repeated or drifting fix rounds can trigger a dedicated rabbit-hole
  investigation before the loop continues
- a final run summary is written for human signoff at the end

Bundle layout:

- `workflow.json`: current source workflow definition
- `prompts/`: extracted prompt source for top-level planning, implementation,
  verification, iteration evaluation, and final summary
- `subruns/review-consensus/`: child workflow that handles reviewer versus
  review-auditor consensus without a human checkpoint
- `hooks/notify`: repo-local notification hook baseline
- `scripts/write-round-policy.sh`: freezes the unattended review policy and
  seeds cumulative run history
- `scripts/write-terminal-iteration-status.sh`: refreshes the final iteration
  status when the workflow finishes without running another remediation round
- `scripts/checkpoint-start-state.sh`: optionally creates a baseline commit if
  the branch starts dirty so later fix commits are inspectable
- `scripts/prepare-review-consensus-request.sh`: packages the current parent
  run state for the review-consensus child workflow
- `scripts/write-rabbit-hole-check-request.sh`: packages a wider
  post-remediation design-drift request when the loop needs a rabbit-hole
  investigation
- `scripts/write-skipped-rabbit-hole-check.sh`: writes placeholder rabbit-hole
  summary/report artifacts when no investigation is needed so later nodes keep
  stable inputs
- `scripts/collect-evidence.sh`: gathers execution evidence after each fix
  round, excluding the active Forge run directory from tracked and untracked
  evidence
- `scripts/checkpoint-commit.sh`: creates a commit after each accepted fix round
- accepted fix rounds also preserve the exact accepted finding set for the final
  signoff summary, even if a later review-only pass ends the run
- `scripts/prepare-run-summary-context.sh`: packages the latest remediation
  artifacts and preserved accepted findings for the final signoff summary when
  they exist

Current flow:

- freeze the unattended round policy and initialize cumulative run history
- create a baseline checkpoint commit if the branch is dirty before automated
  fixes begin
- run a child review-consensus subworkflow:
  reviewer scans the branch, review-auditor challenges weak or repeated review
  output, calibrates whether the next fix still fits the component complexity
  budget, and escalation happens if the reviewer ignores prior pushback; all
  child agents run inside `request.repo_root`
- decide whether the accepted review output should trigger remediation or stop
  the run as already stable enough
- plan and implement the accepted fix slice
- verify the fix round, checkpoint-commit it if accepted, optionally run a
  rabbit-hole investigation when the loop shows drift, and then evaluate
  whether another round is still worthwhile
- package the latest remediation artifacts and write a final run summary with
  failures, fixes, checkpoints, guardrail decisions, impact, checks, and
  residual risks
- require a single final human signoff or human escalation

Important policy notes:

- the outer unattended remediation loop defaults to `max_rounds: 5`
- request-local `workflow_config.auto_review_and_fix.max_rounds` can override
  that cap; when `max_round_continuations` is omitted it is derived as
  `max_rounds - 1`
- when `max_round_continuations` is omitted, `max_rounds` must be at most
  `4294967296`, keeping derived continuation budgets within the engine's
  loop-budget range
- request-local `workflow_config.codex.model` and
  `workflow_config.codex.reasoning_effort` configure `codex exec` dispatches
  for the run
- `max_rounds` is the total accepted-round cap; the workflow budgets only the
  four legal `continue` re-entries that can follow round 1
- the fix-retry, replan, and review-consensus disagreement loops all read their
  budgets from the same frozen round policy artifact that is written at run
  start
- the frozen round policy also carries explicit guardrails for complexity-budget
  boundaries and when a valid issue should stop as `complete` instead of being
  auto-patched
- review consensus may accept an empty finding set when the correct unattended
  result is "real issue, wrong local response"
- `request.repo_root` may be a standard checkout or a linked Git worktree; the
  helper scripts validate it through Git instead of assuming `.git/` is a
  directory, and the baseline checkpoint step also supports freshly `git init`'d
  repositories that do not have an initial commit yet
- checkpoint steps automatically exclude the active `FORGE_RUN_DIR` when that
  run lives under `request.repo_root`, so nested `.forge/runs/...` state does
  not trigger bogus baseline commits or get swept into accepted-round
  checkpoints
- evidence collection applies the same active-run exclusion, so Forge's own
  bookkeeping does not appear as repository change evidence for a remediation
  round
- checkpoint steps intentionally fail if the parent repo only has dirty
  submodule worktree content, because those nested changes cannot be captured in
  the parent branch history until they are committed or discarded inside the
  submodule itself
- checkpoint commits use configured Git author identity when available and fall
  back to `Forge Auto Review <forge-auto-review@local>` only when Git cannot
  resolve an author/committer identity for the unattended commit
- if the auditor rejects a review and the reviewer returns materially the same
  review again, the auditor should escalate instead of silently spinning
- if repo-local governance files exist under `ai/governance/`, the workflow
  freezes that context once near run start and passes the frozen snapshot to
  review consensus and the rabbit-hole investigation as additive context; they
  are not required intake fields
- once an accepted round reaches the configured rabbit-hole threshold, the
  workflow can pause for a wider design-drift investigation before deciding to
  continue unattended patching
- final run summaries always receive the latest remediation implementation,
  evidence, verification artifacts, and remediated finding set when at least
  one fix round executes, plus any rabbit-hole summary/report artifacts
- terminal `complete` after review-consensus means no further worthwhile
  unattended slice remains; it does not imply that broader or human-only
  follow-up is zero
- final human signoff is intentionally the only human checkpoint in the normal
  success path
