package dev.llaith.forge.workflow.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.runner.RunAction;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.PendingOperation;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorkflowPlannerTest {
    private static final ObjectMapper MAPPER = Json.mapper();

    @Test
    void plannerProjectsAgentDispatchFromCurrentNode() {
        DerivedRunState state = stateAt("review_scan", Map.of("review_scan", 2L), RunStatus.RUNNING);

        RunAction action = WorkflowPlanner.project(reviewSpec(), state);

        assertThat(action.type()).isEqualTo("dispatch");
        assertThat(action.dispatch()).isNotNull();
        assertThat(action.dispatch().dispatchId()).isEqualTo("review_scan-2");
        assertThat(action.dispatch().runner()).isEqualTo("codex");
        assertThat(action.dispatch().command()).containsExactly("codex", "exec");
        assertThat(action.dispatch().inputPaths()).containsExactly("request");
        assertThat(action.dispatch().outputPaths()).containsExactly("review_findings");
        assertThat(action.dispatch().timeoutMs()).isEqualTo(900000L);
    }

    @Test
    void plannerProjectsJudgeAsAgentBackedDispatch() {
        RunAction action = WorkflowPlanner.project(judgeSpec(), stateAt("judge", Map.of("judge", 3L), RunStatus.RUNNING));

        assertThat(action.type()).isEqualTo("dispatch");
        assertThat(action.dispatch()).isNotNull();
        assertThat(action.dispatch().dispatchId()).isEqualTo("judge-3");
        assertThat(action.dispatch().nodeId()).isEqualTo("judge");
        assertThat(action.dispatch().runner()).isEqualTo("codex");
        assertThat(action.dispatch().command()).containsExactly("codex", "exec");
        assertThat(action.dispatch().outputPaths()).containsExactly("judge_result");
        assertThat(action.dispatch().message()).isEqualTo("Judge result");
    }

    @Test
    void plannerProjectsHumanReviewAndTerminalNoop() {
        RunAction action = WorkflowPlanner.project(reviewSpec(), stateAt("human_curate_findings", Map.of(), RunStatus.RUNNING));

        assertThat(action.type()).isEqualTo("human_review");
        assertThat(action.humanReview()).isNotNull();
        assertThat(action.humanReview().nodeId()).isEqualTo("human_curate_findings");
        assertThat(action.humanReview().fields()).hasSize(1);

        RunAction terminal = WorkflowPlanner.project(reviewSpec(), stateAt("review_scan", Map.of(), RunStatus.COMPLETED));
        assertThat(terminal.type()).isEqualTo("complete");
        assertThat(terminal.message()).isEqualTo("run complete");
    }

    @Test
    void plannerProjectsObjectWorkflowRefSubrun() {
        RunAction action = WorkflowPlanner.project(subrunSpec(), stateAt("delegate", Map.of("delegate", 1L), RunStatus.RUNNING));

        assertThat(action.type()).isEqualTo("subrun");
        assertThat(action.subrun()).isNotNull();
        assertThat(action.subrun().nodeId()).isEqualTo("delegate");
        assertThat(action.subrun().workflowRef()).isEqualTo("subruns/child/workflow.json");
        assertThat(action.subrun().requestArtifact()).isEqualTo("request");
        assertThat(action.subrun().summaryArtifact()).isEqualTo("summary");
        assertThat(action.subrun().importArtifacts()).containsEntry("child_report", "imported_report");

        RunAction textRef = WorkflowPlanner.project(
                subrunSpecWithWorkflowRef(MAPPER.getNodeFactory().textNode("subruns/text/workflow.json")),
                stateAt("delegate", Map.of("delegate", 1L), RunStatus.RUNNING));
        assertThat(textRef.subrun().workflowRef()).isEqualTo("subruns/text/workflow.json");

        RunAction templateRef = WorkflowPlanner.project(
                subrunSpecWithWorkflowRef(MAPPER.createObjectNode().put("template_id", "implement-change")),
                stateAt("delegate", Map.of("delegate", 1L), RunStatus.RUNNING));
        assertThat(templateRef.subrun().workflowRef()).isEqualTo("template:implement-change");
    }

    @Test
    void plannerRehydratesPendingAndTerminalActions() {
        var dispatchPayload = MAPPER.createObjectNode()
                .put("node_id", "review_scan")
                .put("runner", "command")
                .put("message", "Run command");
        dispatchPayload.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("true"));
        dispatchPayload.set("env", MAPPER.createObjectNode().put("FORGE_TEST", "1"));
        dispatchPayload.set("input_paths", MAPPER.createArrayNode().add("request"));
        dispatchPayload.set("output_paths", MAPPER.createArrayNode().add("report"));
        RunAction pendingDispatch = WorkflowPlanner.project(
                reviewSpec(),
                stateWithPending(new PendingOperation("review_scan-4", "node_dispatch", "review_scan", dispatchPayload)));
        assertThat(pendingDispatch.type()).isEqualTo("dispatch");
        assertThat(pendingDispatch.dispatch().dispatchId()).isEqualTo("review_scan-4");
        assertThat(pendingDispatch.dispatch().command()).containsExactly("sh", "-c", "true");
        assertThat(pendingDispatch.dispatch().outputPaths()).containsExactly("report");

        var notificationPayload = MAPPER.createObjectNode()
                .put("message", "Notify operator");
        notificationPayload.set("command", MAPPER.createArrayNode().add("notify").add("done"));
        notificationPayload.set("env", MAPPER.createObjectNode().put("FORGE_NOTIFY", "1"));
        RunAction pendingNotification = WorkflowPlanner.project(
                reviewSpec(),
                stateWithPending(new PendingOperation("notify-1", "notification", "review_scan", notificationPayload)));
        assertThat(pendingNotification.type()).isEqualTo("dispatch");
        assertThat(pendingNotification.dispatch().dispatchId()).isEqualTo("notify-1");
        assertThat(pendingNotification.dispatch().runner()).isEqualTo("notification_hook");
        assertThat(pendingNotification.dispatch().command()).containsExactly("notify", "done");
        assertThat(pendingNotification.dispatch().env()).containsEntry("FORGE_NOTIFY", "1");

        var reviewPayload = MAPPER.createObjectNode()
                .put("node_id", "human_curate_findings")
                .put("message", "Curate");
        reviewPayload.set("fields", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("name", "status")));
        RunAction pendingHuman = WorkflowPlanner.project(
                reviewSpec(),
                stateWithPending(new PendingOperation("human_curate_findings", "human_review", "human_curate_findings", reviewPayload)));
        assertThat(pendingHuman.type()).isEqualTo("human_review");
        assertThat(pendingHuman.humanReview().fields()).hasSize(1);

        var subrunPayload = MAPPER.createObjectNode()
                .put("node_id", "delegate")
                .put("child_slug", "sample-delegate-1")
                .put("frozen_child_spec_path", "subruns/frozen/spec.json")
                .put("frozen_child_ir_path", "subruns/frozen/workflow-ir.json")
                .put("frozen_child_interface_path", "subruns/frozen/workflow-interface.json")
                .put("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json")
                .put("child_run_dir", "subruns/children/sample-delegate-1")
                .put("request_artifact", "request")
                .put("summary_artifact", "summary");
        subrunPayload.set("import_artifacts", MAPPER.createObjectNode().put("report", "child_report"));
        RunAction pendingSubrun = WorkflowPlanner.project(
                subrunSpec(),
                stateWithPending(new PendingOperation("sample-delegate-1", "subrun", "delegate", subrunPayload)));
        assertThat(pendingSubrun.type()).isEqualTo("subrun");
        assertThat(pendingSubrun.subrun().childSlug()).isEqualTo("sample-delegate-1");
        assertThat(pendingSubrun.subrun().workflowRef()).isEqualTo("subruns/frozen/spec.json");
        assertThat(pendingSubrun.subrun().childRunDir()).isEqualTo("subruns/children/sample-delegate-1");

        assertThat(stateAt("review_scan", Map.of(), RunStatus.COMPLETED).runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(WorkflowPlanner.project(reviewSpec(), terminalState(RunStatus.COMPLETED, "done")).type())
                .isEqualTo("complete");
        assertThat(WorkflowPlanner.project(reviewSpec(), terminalState(RunStatus.ESCALATED, "needs operator")).type())
                .isEqualTo("escalate");
        assertThat(WorkflowPlanner.project(reviewSpec(), terminalState(RunStatus.FAILED, "failed")).type())
                .isEqualTo("escalate");
    }

    @Test
    void plannerProjectsIdleAsNoop() {
        RunAction idle = WorkflowPlanner.project(reviewSpec(), stateAt(null, Map.of(), RunStatus.IDLE));
        assertThat(idle.type()).isEqualTo("noop");
        assertThat(idle.message()).isEqualTo("run has not entered a node yet");
    }

    @Test
    void pendingSubrunPayloadRequiresCanonicalFrozenWorkflowFields() {
        var subrunPayload = MAPPER.createObjectNode()
                .put("node_id", "delegate")
                .put("child_slug", "sample-delegate-1")
                .put("workflow_ref", "subruns/frozen/spec.json")
                .put("frozen_child_ir_path", "subruns/frozen/workflow-ir.json")
                .put("frozen_child_interface_path", "subruns/frozen/workflow-interface.json")
                .put("request_artifact_path", "subruns/children/sample-delegate-1/prepared/request.json")
                .put("child_run_dir", "subruns/children/sample-delegate-1")
                .put("request_artifact", "request")
                .put("summary_artifact", "summary");
        subrunPayload.set("import_artifacts", MAPPER.createObjectNode().put("report", "child_report"));

        assertThatThrownBy(() -> new PendingOperation("sample-delegate-1", "subrun", "delegate", subrunPayload))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unsupported payload field 'workflow_ref'");
    }

    @Test
    void plannerRoutesAlwaysByFieldAndLoopContinuation() {
        var always = MAPPER.createObjectNode()
                .put("mode", "always")
                .put("to", "write_findings");
        assertThat(WorkflowPlanner.route(always, "").type()).isEqualTo("transition");
        assertThat(WorkflowPlanner.route(always, "").target()).isEqualTo("write_findings");

        var byField = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "finding_selection_status");
        var cases = byField.putArray("cases");
        cases.addObject().put("equals", "accepted").put("to", "__complete__");
        cases.addObject().put("equals", "rescan").put("continue_loop", "rescan");

        assertThat(WorkflowPlanner.route(byField, "accepted").type()).isEqualTo("complete");
        assertThat(WorkflowPlanner.route(byField, "rescan").type()).isEqualTo("continue_loop");
        assertThat(WorkflowPlanner.route(byField, "rescan").target()).isEqualTo("rescan");
        assertThat(WorkflowPlanner.route(byField, "other").type()).isEqualTo("escalate");
    }

    @Test
    void plannerRejectsUnknownAgentsAndUnsupportedNodes() {
        WorkflowSpec spec = reviewSpec();
        var brokenNode = spec.nodes().getFirst().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) brokenNode).put("agent_id", "missing");
        WorkflowSpec broken = new WorkflowSpec(
                spec.version(),
                spec.routingMode(),
                spec.workflowId(),
                spec.entryNode(),
                spec.interfaceSpec(),
                spec.agents(),
                List.of(brokenNode),
                spec.loops(),
                spec.artifacts(),
                spec.notifications());

        assertThatThrownBy(() -> WorkflowPlanner.project(broken, stateAt("review_scan", Map.of(), RunStatus.RUNNING)))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unknown agent");
    }

    private static DerivedRunState stateAt(String node, Map<String, Long> visits, RunStatus status) {
        return new DerivedRunState(
                status,
                "run-1",
                "sample",
                "review_only",
                node,
                2L,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                visits,
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static DerivedRunState stateWithPending(PendingOperation operation) {
        return new DerivedRunState(
                RunStatus.WAITING_FOR_AGENT,
                "run-1",
                "sample",
                "review_only",
                operation.nodeId(),
                3L,
                null,
                null,
                Map.of(),
                Map.of(),
                operation,
                Map.of(operation.nodeId(), 4L),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static DerivedRunState terminalState(RunStatus status, String message) {
        return new DerivedRunState(
                status,
                "run-1",
                "sample",
                "review_only",
                "review_scan",
                9L,
                status == RunStatus.COMPLETED ? message : null,
                status == RunStatus.COMPLETED ? null : message,
                Map.of(),
                Map.of(),
                null,
                Map.of("review_scan", 1L),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static WorkflowSpec reviewSpec() {
        var agents = MAPPER.createObjectNode();
        agents.set("reviewer", MAPPER.createObjectNode()
                .put("runner", "codex")
                .set("command", MAPPER.createArrayNode().add("codex").add("exec")));
        var agentNode = MAPPER.createObjectNode()
                .put("kind", "agent")
                .put("id", "review_scan")
                .put("agent_id", "reviewer")
                .put("message", "Review")
                .put("timeout_ms", 900000);
        agentNode.set("inputs", MAPPER.createArrayNode().add("request"));
        agentNode.set("outputs", MAPPER.createArrayNode().add("review_findings"));

        var humanNode = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "human_curate_findings")
                .put("message", "Curate")
                .put("instructions", "Choose");
        var fields = MAPPER.createArrayNode();
        fields.addObject().put("name", "status");
        humanNode.set("fields", fields);

        List<JsonNode> nodes = List.of(agentNode, humanNode);
        return new WorkflowSpec(
                2,
                "structured",
                "review_only",
                "review_scan",
                MAPPER.createObjectNode(),
                agents,
                nodes,
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
    }

    private static WorkflowSpec judgeSpec() {
        var agents = MAPPER.createObjectNode();
        agents.set("judge", MAPPER.createObjectNode()
                .put("runner", "codex")
                .set("command", MAPPER.createArrayNode().add("codex").add("exec")));
        var judgeNode = MAPPER.createObjectNode()
                .put("kind", "judge")
                .put("id", "judge")
                .put("agent_id", "judge")
                .put("message", "Judge result")
                .put("decision_schema", "judge_result_v1");
        judgeNode.set("inputs", MAPPER.createArrayNode().add("request"));
        judgeNode.set("outputs", MAPPER.createArrayNode().add("judge_result"));
        var decisionFields = MAPPER.createArrayNode();
        var decision = decisionFields.addObject().put("name", "decision");
        decision.set("allowed_values", MAPPER.createArrayNode().add("accept").add("replan"));
        judgeNode.set("decision_fields", decisionFields);

        return new WorkflowSpec(
                2,
                "structured",
                "judge_flow",
                "judge",
                MAPPER.createObjectNode(),
                agents,
                List.of((JsonNode) judgeNode),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
    }

    private static WorkflowSpec subrunSpec() {
        return subrunSpecWithWorkflowRef(MAPPER.createObjectNode().put("path", "subruns/child/workflow.json"));
    }

    private static WorkflowSpec subrunSpecWithWorkflowRef(JsonNode workflowRef) {
        var node = MAPPER.createObjectNode()
                .put("kind", "subrun")
                .put("id", "delegate")
                .put("request_artifact", "request")
                .put("summary_artifact", "summary");
        node.set("workflow_ref", workflowRef);
        node.set("import_artifacts", MAPPER.createObjectNode().put("child_report", "imported_report"));
        return new WorkflowSpec(
                2,
                "structured",
                "subrun_flow",
                "delegate",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
    }
}
