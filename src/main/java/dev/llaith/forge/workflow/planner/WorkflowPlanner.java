package dev.llaith.forge.workflow.planner;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.workflow.runner.PendingDispatch;
import dev.llaith.forge.workflow.runner.PendingHumanReview;
import dev.llaith.forge.workflow.runner.PendingSubrun;
import dev.llaith.forge.workflow.runner.RunAction;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WorkflowPlanner {
    private WorkflowPlanner() {
    }

    public static RunAction project(WorkflowSpec spec, DerivedRunState state) {
        if (state.pendingOperation() != null) {
            return pendingAction(state.pendingOperation());
        }
        if (state.runStatus() == RunStatus.COMPLETED
                || state.runStatus() == RunStatus.ESCALATED
                || state.runStatus() == RunStatus.FAILED) {
            return terminalAction(state);
        }
        if (state.runStatus() == RunStatus.IDLE) {
            return RunAction.noop("run has not entered a node yet");
        }
        String nodeId = state.currentNode() == null ? spec.entryNode() : state.currentNode();
        JsonNode node = findNode(spec, nodeId);
        return switch (requiredText(node, "kind")) {
            case "agent", "judge" -> RunAction.dispatch(agentDispatch(spec, state, node));
            case "command" -> RunAction.dispatch(commandDispatch(state, node));
            case "human" -> RunAction.humanReview(humanReview(node));
            case "subrun" -> RunAction.subrun(subrun(node));
            default -> throw new ForgeException("unsupported workflow node kind: " + requiredText(node, "kind"));
        };
    }

    private static RunAction pendingAction(dev.llaith.forge.workflow.state.PendingOperation operation) {
        JsonNode payload = operation.payload();
        return switch (operation.kind()) {
            case "node_dispatch" -> RunAction.dispatch(new PendingDispatch(
                    operation.operationId(),
                    requiredText(payload, "node_id"),
                    textOr(payload, "runner", "command"),
                    stringArray(payload.get("command")),
                    textOrNull(payload, "cwd"),
                    stringMap(payload.get("env")),
                    stringArray(payload.get("input_paths")),
                    stringArray(payload.get("output_paths")),
                    textOrNull(payload, "stdin_path"),
                    textOrNull(payload, "message"),
                    longOrNull(payload, "timeout_ms")));
            case "notification" -> RunAction.dispatch(new PendingDispatch(
                    operation.operationId(),
                    operation.nodeId() == null ? "" : operation.nodeId(),
                    "notification_hook",
                    stringArray(payload.get("command")),
                    textOrNull(payload, "cwd"),
                    stringMap(payload.get("env")),
                    List.of(),
                    List.of(),
                    null,
                    textOrNull(payload, "message"),
                    null));
            case "human_review" -> RunAction.humanReview(new PendingHumanReview(
                    requiredText(payload, "node_id"),
                    textOr(payload, "message", "Human review required"),
                    jsonArray(payload.get("fields")),
                    textOrNull(payload, "instructions")));
            case "subrun" -> RunAction.subrun(new PendingSubrun(
                    requiredText(payload, "node_id"),
                    requiredText(payload, "frozen_child_spec_path"),
                    textOr(payload, "request_artifact", ""),
                    textOr(payload, "summary_artifact", ""),
                    stringMap(payload.get("import_artifacts")),
                    textOrNull(payload, "message"),
                    textOrNull(payload, "child_slug"),
                    textOrNull(payload, "frozen_child_spec_path"),
                    textOrNull(payload, "frozen_child_ir_path"),
                    textOrNull(payload, "frozen_child_interface_path"),
                    textOrNull(payload, "request_artifact_path"),
                    textOrNull(payload, "child_run_dir")));
            default -> throw new ForgeException("unsupported pending operation kind: " + operation.kind());
        };
    }

    private static RunAction terminalAction(DerivedRunState state) {
        if (state.runStatus() == RunStatus.COMPLETED) {
            return RunAction.complete(state.completedMessage() == null ? "run complete" : state.completedMessage());
        }
        String reason = state.escalationReason() == null ? "run failed" : state.escalationReason();
        if (state.runStatus() == RunStatus.ESCALATED) {
            return RunAction.escalate(reason);
        }
        return RunAction.escalate(reason);
    }

    public static RunAction route(JsonNode route, String decisionValue) {
        String mode = requiredText(route, "mode");
        if ("always".equals(mode)) {
            return terminalOrTransition(requiredText(route, "to"));
        }
        if ("by_field".equals(mode)) {
            JsonNode cases = route.get("cases");
            if (cases != null && cases.isArray()) {
                for (JsonNode routeCase : cases) {
                    JsonNode equals = routeCase.get("equals");
                    if (equals != null && equals.asText().equals(decisionValue)) {
                        if (routeCase.hasNonNull("continue_loop")) {
                            return new RunAction(
                                    "continue_loop",
                                    null,
                                    null,
                                    null,
                                    null,
                                    routeCase.get("continue_loop").asText(),
                                    null);
                        }
                        return terminalOrTransition(requiredText(routeCase, "to"));
                    }
                }
            }
            JsonNode defaultRoute = route.get("default");
            if (defaultRoute != null && defaultRoute.hasNonNull("to")) {
                return terminalOrTransition(requiredText(defaultRoute, "to"));
            }
            return RunAction.escalate("no route case matched decision value '" + decisionValue + "'");
        }
        throw new ForgeException("unsupported route mode: " + mode);
    }

    private static RunAction terminalOrTransition(String target) {
        return switch (target) {
            case "__complete__" -> RunAction.complete("workflow completed");
            case "__escalate__" -> RunAction.escalate("workflow escalated");
            default -> new RunAction("transition", null, null, null, null, target, null);
        };
    }

    private static PendingDispatch agentDispatch(WorkflowSpec spec, DerivedRunState state, JsonNode node) {
        String agentId = requiredText(node, "agent_id");
        JsonNode agent = spec.agents() == null ? null : spec.agents().get(agentId);
        if (agent == null) {
            throw new ForgeException("unknown agent '" + agentId + "'");
        }
        return new PendingDispatch(
                dispatchId(state, requiredText(node, "id")),
                requiredText(node, "id"),
                textOr(agent, "runner", "agent"),
                stringArray(agent.get("command")),
                textOrNull(agent, "cwd"),
                stringMap(agent.get("env")),
                stringArray(node.get("inputs")),
                stringArray(node.get("outputs")),
                null,
                textOrNull(node, "message"),
                longOrNull(node, "timeout_ms"));
    }

    private static PendingDispatch commandDispatch(DerivedRunState state, JsonNode node) {
        return new PendingDispatch(
                dispatchId(state, requiredText(node, "id")),
                requiredText(node, "id"),
                "command",
                stringArray(node.get("command")),
                textOrNull(node, "cwd"),
                stringMap(node.get("env")),
                stringArray(node.get("inputs")),
                stringArray(node.get("outputs")),
                null,
                textOrNull(node, "message"),
                longOrNull(node, "timeout_ms"));
    }

    private static PendingHumanReview humanReview(JsonNode node) {
        List<JsonNode> fields = new ArrayList<>();
        JsonNode rawFields = node.get("fields");
        if (rawFields != null && rawFields.isArray()) {
            rawFields.forEach(field -> fields.add(field.deepCopy()));
        }
        return new PendingHumanReview(
                requiredText(node, "id"),
                textOr(node, "message", "Human review required"),
                fields,
                textOrNull(node, "instructions"));
    }

    private static PendingSubrun subrun(JsonNode node) {
        return new PendingSubrun(
                requiredText(node, "id"),
                requiredWorkflowRef(node),
                requiredText(node, "request_artifact"),
                requiredText(node, "summary_artifact"),
                stringMap(node.get("import_artifacts")),
                textOrNull(node, "message"));
    }

    private static String requiredWorkflowRef(JsonNode node) {
        JsonNode value = node.get("workflow_ref");
        if (value == null || value.isNull()) {
            throw new ForgeException("missing required workflow field: workflow_ref");
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (!text.isBlank()) {
                return text;
            }
        }
        if (value.isObject()) {
            JsonNode path = value.get("path");
            if (path != null && path.isTextual() && !path.asText().isBlank()) {
                return path.asText();
            }
            JsonNode templateId = value.get("template_id");
            if (templateId != null && templateId.isTextual() && !templateId.asText().isBlank()) {
                return "template:" + templateId.asText();
            }
        }
        throw new ForgeException("missing required workflow field: workflow_ref");
    }

    private static String dispatchId(DerivedRunState state, String nodeId) {
        long visit = state.nodeVisitCounts().getOrDefault(nodeId, 1L);
        return nodeId + "-" + visit;
    }

    private static JsonNode findNode(WorkflowSpec spec, String nodeId) {
        for (JsonNode node : spec.nodes()) {
            if (nodeId.equals(textOrNull(node, "id"))) {
                return node;
            }
        }
        throw new ForgeException("unknown current node '" + nodeId + "'");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            throw new ForgeException("missing required workflow field: " + field);
        }
        return value;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0L) {
            throw new ForgeException(field + " must be a non-negative integer");
        }
        return value.asLong();
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static List<JsonNode> jsonArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(value -> values.add(value.deepCopy()));
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            values.put(entry.getKey(), entry.getValue().asText());
        }
        return Map.copyOf(values);
    }
}
