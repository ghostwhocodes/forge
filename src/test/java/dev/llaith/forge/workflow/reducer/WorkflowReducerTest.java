package dev.llaith.forge.workflow.reducer;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorkflowReducerTest {
    @Test
    void reducerReplaysOperationsDecisionsRoutesAndLoops() {
        var state = WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                        "node_id", "review"
                )),
                EventEnvelope.of(3, "2026-04-20T12:00:02Z", "operation_prepared", Map.of(
                        "operation", Map.of(
                                "operation_id", "review-1",
                                "operation_kind", "node_dispatch",
                                "node_id", "review",
                                "dispatch", Map.of(
                                        "dispatch_id", "review-1",
                                        "node_id", "review",
                                        "runner", "command",
                                        "command", List.of("sh", "-c", "exit 0"))
                        )
                )),
                EventEnvelope.of(4, "2026-04-20T12:00:03Z", "decision_recorded", Map.of(
                        "key", "status",
                        "value", "accepted"
                )),
                EventEnvelope.of(5, "2026-04-20T12:00:04Z", "route_field_recorded", Map.of(
                        "node_id", "review",
                        "field", "status",
                        "value", "accepted"
                )),
                EventEnvelope.of(6, "2026-04-20T12:00:05Z", "loop_instance_opened", Map.of(
                        "loop_id", "rescan",
                        "instance_id", "rescan-1",
                        "controller_node", "human",
                        "entry_node", "review",
                        "controller_visit", 1,
                        "opened_at_node", "human"
                )),
                EventEnvelope.of(7, "2026-04-20T12:00:06Z", "loop_budget_resolved", Map.of(
                        "loop_id", "rescan",
                        "instance_id", "rescan-1",
                        "resolved_budget", 2
                )),
                EventEnvelope.of(8, "2026-04-20T12:00:07Z", "loop_continued", Map.of(
                        "loop_id", "rescan",
                        "instance_id", "rescan-1",
                        "iteration", 1
                )),
                EventEnvelope.of(9, "2026-04-20T12:00:08Z", "operation_completed", Map.of(
                        "operation_id", "review-1"
                ))
        ));

        assertThat(state.runStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(state.currentNode()).isEqualTo("review");
        assertThat(state.nodeVisitCounts()).containsEntry("review", 1L);
        assertThat(state.pendingOperation()).isNull();
        assertThat(state.decisions()).containsEntry("status", "accepted");
        assertThat(state.routeFields()).containsEntry("review.status", "accepted");
        assertThat(state.loopStates()).containsKey("rescan-1");
        assertThat(state.loopStates().get("rescan-1").resolvedBudget()).isEqualTo(2L);
        assertThat(state.loopStates().get("rescan-1").iterationCount()).isEqualTo(1L);
    }

    @Test
    void reducerRejectsSequenceGaps() {
        assertThatThrownBy(() -> WorkflowReducer.deriveState(List.of(
                initialized(),
                EventEnvelope.of(3, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                        "node_id", "review"
                ))
        )))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("event sequence mismatch");
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
}
