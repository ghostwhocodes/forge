package dev.llaith.forge.workflow;

import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.template.Templates;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.planner.WorkflowPlanner;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class WorkflowEngineTest {
    @Test
    void builtInTemplateEntryNodeProjectsDispatchThroughReducerState() {
        WorkflowSpec spec = Templates.loadBuiltInTemplate("review-only").workflow();
        WorkflowSpecs.validate(spec);

        var state = WorkflowReducer.deriveState(List.of(
                EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                        "run_id", "sample",
                        "slug", "sample",
                        "workflow_id", spec.workflowId(),
                        "spec_version", spec.version(),
                        "entry_node", spec.entryNode()
                )),
                EventEnvelope.of(2, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                        "node_id", spec.entryNode()
                ))
        ));

        var action = WorkflowPlanner.project(spec, state);

        assertThat(action.type()).isEqualTo("dispatch");
        assertThat(action.dispatch()).isNotNull();
        assertThat(action.dispatch().dispatchId()).isEqualTo("review_scan-1");
        assertThat(action.dispatch().runner()).isEqualTo("codex");
        assertThat(action.dispatch().inputPaths()).contains("request");
        assertThat(action.dispatch().outputPaths()).contains("review_findings");
    }
}
