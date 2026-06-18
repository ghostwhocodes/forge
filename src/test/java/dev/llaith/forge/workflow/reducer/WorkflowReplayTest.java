package dev.llaith.forge.workflow.reducer;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.storage.ArtifactStore;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.ArtifactRole;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorkflowReplayTest {
    @Test
    void replayDerivesTerminalStateArtifactsAndRequestRole() {
        ArtifactRecord request = new ArtifactRecord(
                "task_packet",
                "artifacts/task-packet.json",
                "intake",
                "application/json");
        var state = WorkflowReducer.deriveState(List.of(
                EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                        "run_id", "run-1",
                        "slug", "sample",
                        "workflow_id", "review_only",
                        "spec_version", 2,
                        "entry_node", "review"
                )),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "artifact_written", Map.of(
                        "artifact", request
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "artifact_metadata_recorded", Map.of(
                        "artifact_name", "task_packet",
                        "blob_id", "blob-1",
                        "binding", ArtifactBinding.local(),
                        "role", ArtifactRole.REQUEST
                )),
                EventEnvelope.of(4, "2026-04-20T12:00:03Z", "run_completed", Map.of(
                        "message", "done"
                ))
        ));

        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.runId()).isEqualTo("run-1");
        assertThat(state.currentNode()).isEqualTo("review");
        assertThat(state.lastEventSeq()).isEqualTo(4);
        assertThat(state.completedMessage()).isEqualTo("done");
        assertThat(ArtifactStore.requestArtifact(state)).isEqualTo(request);
    }

    @Test
    void replayRejectsNonMonotonicSequences() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                        "run_id", "run-1",
                        "slug", "sample",
                        "workflow_id", "review_only",
                        "spec_version", 2,
                        "entry_node", "review"
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                        "node_id", "review"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("event sequence mismatch");
    }

    @Test
    void replayRejectsUnknownEventTypes() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "future_event", Map.of(
                        "value", "unsupported"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unsupported event type: future_event");
    }

    @Test
    void replayTracksEscalatedAndFailedStates() {
        var escalated = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "run_escalated", Map.of(
                        "reason", "needs operator"
                ))
        ));
        assertThat(escalated.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(escalated.escalationReason()).isEqualTo("needs operator");

        var failed = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "node_failed", Map.of(
                        "node_id", "review",
                        "reason", "boom",
                        "retryable", false
                ))
        ));
        assertThat(failed.runStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.escalationReason()).isEqualTo("boom");
    }

    @Test
    void replayPreservesUnsignedLoopCounters() {
        var state = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "loop_instance_opened", Map.of(
                        "loop_id", "review_loop",
                        "instance_id", "review_loop-2147483648",
                        "controller_node", "review",
                        "entry_node", "review",
                        "controller_visit", 2_147_483_648L,
                        "opened_at_node", "review"
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "loop_budget_resolved", Map.of(
                        "loop_id", "review_loop",
                        "instance_id", "review_loop-2147483648",
                        "resolved_budget", 4_294_967_295L
                )),
                EventEnvelope.of(4, "2026-04-20T12:00:03Z", "loop_continued", Map.of(
                        "loop_id", "review_loop",
                        "instance_id", "review_loop-2147483648",
                        "iteration", 4_294_967_295L
                ))
        ));

        var loop = state.loopStates().get("review_loop-2147483648");
        assertThat(loop.controllerVisit()).isEqualTo(2_147_483_648L);
        assertThat(loop.resolvedBudget()).isEqualTo(4_294_967_295L);
        assertThat(loop.iterationCount()).isEqualTo(4_294_967_295L);
    }

    @Test
    void replayRejectsInvalidUnsignedLoopCounters() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "loop_instance_opened", Map.of(
                        "loop_id", "review_loop",
                        "instance_id", "review_loop-1",
                        "controller_node", "review",
                        "entry_node", "review",
                        "controller_visit", 4_294_967_296L,
                        "opened_at_node", "review"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("controller_visit must be an unsigned 32-bit integer");
    }

    @Test
    void replayPreservesOperationExecutionState() {
        var state = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "review-1"),
                                Map.entry("operation_kind", "node_dispatch"),
                                Map.entry("node_id", "review"),
                                Map.entry("dispatch", Map.of(
                                        "dispatch_id", "review-1",
                                        "node_id", "review",
                                        "runner", "command",
                                        "command", List.of("sh", "-c", "exit 7"))))
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "review-1",
                        "execution", rustProcessExecution("review-1", "node_dispatch", true, null)
                )),
                EventEnvelope.of(4, "2026-04-20T12:00:03Z", "operation_failed", Map.of(
                        "operation_id", "review-1",
                        "execution", rustProcessExecution("review-1", "node_dispatch", false, false)
                )),
                EventEnvelope.of(5, "2026-04-20T12:00:04Z", "operation_completed", Map.of(
                        "operation_id", "review-1"
                ))
        ));

        assertThat(state.operationExecutions()).containsKey("review-1");
        assertThat(state.operationExecutions().get("review-1").get("success").asBoolean()).isFalse();
        assertThat(state.operationExecutions().get("review-1").get("progress_path").asText())
                .isEqualTo("dispatch/output/review-1.progress.ndjson");
        assertThat(state.pendingOperation()).isNull();
        assertThat(state.runStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void replayAppliesHumanInputFieldsWithoutDecisionEvents() {
        var state = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", "human_review",
                                "node_id", "review",
                                "review", Map.of(
                                        "node_id", "review",
                                        "fields", List.of(Map.of("name", "status"))))
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "human_input_recorded", Map.of(
                        "node_id", "review",
                        "fields", Map.of("status", "approved")
                )),
                EventEnvelope.of(4, "2026-04-20T12:00:03Z", "operation_completed", Map.of(
                        "operation_id", "review-1"
                ))
        ));

        assertThat(state.decisions()).containsEntry("status", "approved");
        assertThat(state.pendingOperation()).isNull();
        assertThat(state.runStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void replayRejectsOperationLifecycleEventsBeforeInitializationOrWithoutMatchingPendingOperation() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "run_initialized", Map.of(
                        "run_id", "run-2",
                        "slug", "sample-2",
                        "workflow_id", "review_only",
                        "spec_version", 2,
                        "entry_node", "review"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("run already initialized");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                EventEnvelope.of(1, "2026-04-20T12:00:00Z", "operation_heartbeat", Map.of(
                        "operation_id", "op-1",
                        "execution", Map.of("kind", "process")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("run not initialized");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_completed", Map.of(
                        "operation_id", "review-1"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("no current operation recorded");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-2")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation already pending");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "other-1",
                        "execution", Map.of("kind", "process")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation mismatch");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "review-1",
                        "execution", Map.of(
                                "kind", "process",
                                "operation_id", "review-1",
                                "operation_kind", "subrun")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation kind mismatch");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "review-1",
                        "execution", Map.of(
                                "kind", "subrun",
                                "operation_id", "review-1")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("execution is missing operation_kind");
    }

    @Test
    void replayRejectsLegacyOperationIdentityFields() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "id", "review-1",
                                "operation_kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch", nodeDispatchPayload("review-1", "review")
                        )
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation payload field 'operation_id' must be a nonblank string");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch", nodeDispatchPayload("review-1", "review")
                        )
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation payload field 'operation_kind' must be a nonblank string");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "review-1",
                        "execution", Map.of(
                                "kind", "process",
                                "operation_kind", "node_dispatch")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("execution is missing operation_id");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", nodeDispatchOperation("review-1")
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_started", Map.of(
                        "operation_id", "review-1",
                        "execution", Map.of(
                                "kind", "process",
                                "operation_id", "review-1")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("execution is missing operation_kind");
    }

    @Test
    void replayRejectsNonCanonicalPreparedOperationPayloads() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", 17,
                                "operation_kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch", nodeDispatchPayload("review-1", "review"))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation payload field 'operation_id' must be a nonblank string");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", 17,
                                "node_id", "review",
                                "dispatch", nodeDispatchPayload("review-1", "review"))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("operation payload field 'operation_kind' must be a nonblank string");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", "legacy_dispatch",
                                "node_id", "review",
                                "dispatch", nodeDispatchPayload("review-1", "review"))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unsupported operation_kind 'legacy_dispatch'");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch_id", "review-1",
                                "runner", "command",
                                "command", List.of("sh", "-c", "exit 7"))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("requires nested object field 'dispatch'");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch", "flat")
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("requires nested object field 'dispatch'");
    }

    @Test
    void replayPreservesRustShapedSubrunPendingOperationPayload() {
        var state = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "sample-delegate-1"),
                                Map.entry("operation_kind", "subrun"),
                                Map.entry("node_id", "review"),
                                Map.entry("subrun", Map.ofEntries(
                                        Map.entry("node_id", "review"),
                                        Map.entry("child_slug", "sample-delegate-1"),
                                        Map.entry("frozen_child_spec_path", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_ir_path", "subruns/frozen/workflow-ir.json"),
                                        Map.entry("frozen_child_interface_path", "subruns/frozen/workflow-interface.json"),
                                        Map.entry("child_run_dir", "subruns/children/sample-delegate-1"),
                                        Map.entry("request_artifact", "request"),
                                        Map.entry("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json"),
                                        Map.entry("summary_artifact", "summary"),
                                        Map.entry("import_artifacts", Map.of("child_report", "imported_report"))))
                        )
                ))
        ));

        assertThat(state.runStatus()).isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(state.pendingOperation()).isNotNull();
        assertThat(state.pendingOperation().operationId()).isEqualTo("sample-delegate-1");
        assertThat(state.pendingOperation().kind()).isEqualTo("subrun");
        assertThat(state.pendingOperation().payload().get("child_slug").asText()).isEqualTo("sample-delegate-1");
        assertThat(state.pendingOperation().payload().get("request_artifact_path").asText())
                .endsWith("prepared/request.json");
        assertThat(state.pendingOperation().payload().get("import_artifacts").get("child_report").asText())
                .isEqualTo("imported_report");
    }

    @Test
    void replayRejectsNonCanonicalSubrunPayloadFields() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "sample-delegate-1"),
                                Map.entry("operation_kind", "subrun"),
                                Map.entry("node_id", "review"),
                                Map.entry("subrun", Map.ofEntries(
                                        Map.entry("node_id", "review"),
                                        Map.entry("child_slug", "sample-delegate-1"),
                                        Map.entry("workflow_ref", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_spec_path", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_ir_path", "subruns/frozen/workflow-ir.json"),
                                        Map.entry("frozen_child_interface_path", "subruns/frozen/workflow-interface.json"),
                                        Map.entry("child_run_dir", "subruns/children/sample-delegate-1"),
                                        Map.entry("request_artifact", "request"),
                                        Map.entry("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json"),
                                        Map.entry("summary_artifact", "summary"),
                                        Map.entry("import_artifacts", Map.of("child_report", "imported_report"))))
                        )
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unsupported payload field 'workflow_ref'");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "sample-delegate-1"),
                                Map.entry("operation_kind", "subrun"),
                                Map.entry("node_id", "review"),
                                Map.entry("subrun", Map.ofEntries(
                                        Map.entry("node_id", "review"),
                                        Map.entry("child_slug", "sample-delegate-1"),
                                        Map.entry("frozen_child_spec_path", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_ir_path", "subruns/frozen/workflow-ir.json"),
                                        Map.entry("frozen_child_interface_path", "subruns/frozen/workflow-interface.json"),
                                        Map.entry("child_run_dir", "subruns/children/sample-delegate-1"),
                                        Map.entry("request_artifact", "request"),
                                        Map.entry("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json"),
                                        Map.entry("summary_artifact", "summary"),
                                        Map.entry("required_import_artifacts", List.of("child_report")),
                                        Map.entry("import_artifacts", Map.of("child_report", "imported_report"))))
                        )
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unsupported payload field 'required_import_artifacts'");
    }

    @Test
    void replayRejectsMalformedSubrunImportArtifactsPayload() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "sample-delegate-1"),
                                Map.entry("operation_kind", "subrun"),
                                Map.entry("node_id", "review"),
                                Map.entry("subrun", Map.ofEntries(
                                        Map.entry("node_id", "review"),
                                        Map.entry("child_slug", "sample-delegate-1"),
                                        Map.entry("frozen_child_spec_path", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_ir_path", "subruns/frozen/workflow-ir.json"),
                                        Map.entry("frozen_child_interface_path", "subruns/frozen/workflow-interface.json"),
                                        Map.entry("child_run_dir", "subruns/children/sample-delegate-1"),
                                        Map.entry("request_artifact", "request"),
                                        Map.entry("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json"),
                                        Map.entry("summary_artifact", "summary"),
                                        Map.entry("import_artifacts", "not-an-object"))))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("requires object payload field 'import_artifacts'");

        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "operation_prepared", Map.of(
                        "operation", Map.ofEntries(
                                Map.entry("operation_id", "sample-delegate-1"),
                                Map.entry("operation_kind", "subrun"),
                                Map.entry("node_id", "review"),
                                Map.entry("subrun", Map.ofEntries(
                                        Map.entry("node_id", "review"),
                                        Map.entry("child_slug", "sample-delegate-1"),
                                        Map.entry("frozen_child_spec_path", "subruns/frozen/spec.json"),
                                        Map.entry("frozen_child_ir_path", "subruns/frozen/workflow-ir.json"),
                                        Map.entry("frozen_child_interface_path", "subruns/frozen/workflow-interface.json"),
                                        Map.entry("child_run_dir", "subruns/children/sample-delegate-1"),
                                        Map.entry("request_artifact", "request"),
                                        Map.entry("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json"),
                                        Map.entry("summary_artifact", "summary"),
                                        Map.entry("import_artifacts", Map.of("child_report", "")))))
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("import_artifacts field 'child_report' must be a nonblank string");
    }

    @Test
    void replayRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                        "run_id", "run-1",
                        "slug", "sample",
                        "workflow_id", "review_only",
                        "spec_version", 2
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("event run_initialized is missing entry_node");
    }

    @Test
    void requestArtifactFallsBackToRequestNameAndRejectsAmbiguity() {
        ArtifactRecord fallback = new ArtifactRecord("request", "artifacts/request.json", null, "application/json");
        DerivedRunState fallbackState = new DerivedRunState(
                RunStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("request", fallback),
                Map.of());
        assertThat(ArtifactStore.requestArtifact(fallbackState)).isEqualTo(fallback);

        ArtifactRecord a = new ArtifactRecord("a", "a", null, null);
        ArtifactRecord b = new ArtifactRecord("b", "b", null, null);
        DerivedRunState ambiguous = new DerivedRunState(
                RunStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("a", a, "b", b),
                Map.of(
                        "a", new ArtifactMetadata("blob-a", ArtifactBinding.local(), ArtifactRole.REQUEST),
                        "b", new ArtifactMetadata("blob-b", ArtifactBinding.local(), ArtifactRole.REQUEST)));
        assertThatThrownBy(() -> ArtifactStore.requestArtifact(ambiguous))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("multiple artifacts are marked as the request input");
    }

    private static EventEnvelope initialized() {
        return EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "review_only",
                "spec_version", 2,
                "entry_node", "review"
        ));
    }

    private static Map<String, Object> nodeDispatchOperation(String operationId) {
        return Map.of(
                "operation_id", operationId,
                "operation_kind", "node_dispatch",
                "node_id", "review",
                "dispatch", nodeDispatchPayload(operationId, "review"));
    }

    private static Map<String, Object> nodeDispatchPayload(String operationId, String nodeId) {
        return Map.of(
                "dispatch_id", operationId,
                "node_id", nodeId,
                "runner", "command",
                "command", List.of("sh", "-c", "exit 7"));
    }

    private static Map<String, Object> rustProcessExecution(
            String operationId,
            String operationKind,
            boolean active,
            Boolean success) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("kind", "process");
        execution.put("operation_id", operationId);
        execution.put("operation_kind", operationKind);
        execution.put("started_at", "2026-04-20T12:00:01Z");
        execution.put("latest_activity_at", "2026-04-20T12:00:03Z");
        execution.put("latest_output_at", "2026-04-20T12:00:03Z");
        execution.put("progress_path", "dispatch/output/" + operationId + ".progress.ndjson");
        execution.put("stdout_path", "dispatch/output/" + operationId + ".stdout");
        execution.put("stderr_path", "dispatch/output/" + operationId + ".stderr");
        execution.put("result_path", "dispatch/output/" + operationId + ".result.json");
        execution.put("stdout_bytes", 0);
        execution.put("stderr_bytes", 0);
        execution.put("progress_bytes", 0);
        execution.put("progress_entries", 0);
        execution.put("active", active);
        execution.put("launched", true);
        execution.put("success", success);
        execution.put("exit_code", success == null ? null : 1);
        execution.put("failure_reason", Boolean.FALSE.equals(success) ? "dispatch failed" : null);
        execution.put("completed_at", success == null ? null : "2026-04-20T12:00:03Z");
        return execution;
    }
}
