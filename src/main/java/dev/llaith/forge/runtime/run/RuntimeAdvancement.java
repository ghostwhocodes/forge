package dev.llaith.forge.runtime.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.storage.ArtifactStore;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.StoredRunContext;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.planner.WorkflowPlanner;
import dev.llaith.forge.workflow.runner.RunAction;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.LoopState;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;

final class RuntimeAdvancement {
    private static final long UNSIGNED_INT_MAX = 4_294_967_295L;

    private final TerminalNotificationPlanner notificationPlanner;

    RuntimeAdvancement(TerminalNotificationPlanner notificationPlanner) {
        this.notificationPlanner = notificationPlanner;
    }

    long appendRouteEvents(
            StoredRunContext context,
            long seq,
            String timestamp,
            Map<String, String> decisions) {
        JsonNode node = currentNode(context.spec(), context.state());
        JsonNode route = node.get("route");
        if (route == null || route.isNull()) {
            return appendTerminalEventAndNotification(
                    context,
                    seq,
                    timestamp,
                    RunAction.complete("workflow completed"));
        }
        String decision = routeDecisionValue(route, decisions);
        if ("by_field".equals(text(route, "mode"))) {
            String field = text(route, "field");
            if (!field.isBlank() && decisions.containsKey(field)) {
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "route_field_recorded", Map.of(
                        "node_id", context.state().currentNode() == null ? "" : context.state().currentNode(),
                        "field", field,
                        "value", decisions.get(field))));
            }
        }
        RunAction routeAction = WorkflowPlanner.route(route, decision);
        switch (routeAction.type()) {
            case "transition" -> {
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "transition_taken", Map.of(
                        "from", context.state().currentNode(),
                        "to", routeAction.target()
                )));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "node_entered", Map.of(
                        "node_id", routeAction.target()
                )));
                return seq + 1;
            }
            case "complete" -> {
                String from = context.state().currentNode() == null ? "" : context.state().currentNode();
                String message = completionMessage(context);
                RunAction terminalAction = RunAction.complete(message);
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "transition_taken", Map.of(
                        "from", from,
                        "to", "__complete__")));
                return appendTerminalEventAndNotification(context, seq, timestamp, terminalAction);
            }
            case "escalate" -> {
                String from = context.state().currentNode() == null ? "" : context.state().currentNode();
                String reason = routeAction.reason() == null || "workflow escalated".equals(routeAction.reason())
                        ? "node '" + from + "' escalated"
                        : routeAction.reason();
                RunAction terminalAction = RunAction.escalate(reason);
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "transition_taken", Map.of(
                        "from", from,
                        "to", "__escalate__")));
                return appendTerminalEventAndNotification(context, seq, timestamp, terminalAction);
            }
            case "continue_loop" -> {
                return appendContinueLoopEvents(context, seq, timestamp, routeAction.target());
            }
            default -> throw new ForgeException("unsupported route action: " + routeAction.type());
        }
    }

    long appendTerminalEventAndNotification(
            StoredRunContext context,
            long seq,
            String timestamp,
            RunAction action) {
        if ("complete".equals(action.type())) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq++,
                    timestamp,
                    "run_completed",
                    Map.of("message", action.message() == null ? "workflow completed" : action.message())));
        } else if ("escalate".equals(action.type())) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq++,
                    timestamp,
                    "run_escalated",
                    Map.of("reason", action.reason() == null ? "workflow escalated" : action.reason())));
        } else {
            throw new ForgeException("terminal notification helper received non-terminal action: " + action.type());
        }

        ObjectNode notification = notificationPlanner.notificationOperationFor(context, action);
        if (notification != null) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq++,
                    timestamp,
                    "operation_prepared",
                    Map.of("operation", notification)));
        }
        return seq;
    }

    private long appendContinueLoopEvents(
            StoredRunContext context,
            long seq,
            String timestamp,
            @Nullable String loopId) {
        if (loopId == null || loopId.isBlank()) {
            throw new ForgeException("continue_loop route is missing loop id");
        }
        JsonNode loop = findLoop(context.spec(), loopId);
        String from = context.state().currentNode() == null ? "" : context.state().currentNode();
        String controllerNode = requiredText(loop, "controller_node");
        String entryNode = requiredText(loop, "entry_node");
        long controllerVisit = context.state().nodeVisitCounts().getOrDefault(controllerNode, 1L);
        LoopRuntimeState loopState = currentLoopRuntimeState(context, loopId, controllerNode, controllerVisit);
        if (loopState.opened()) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "loop_instance_opened", Map.of(
                    "loop_id", loopId,
                    "instance_id", loopState.instanceId(),
                    "controller_node", controllerNode,
                    "entry_node", entryNode,
                    "controller_visit", controllerVisit,
                    "opened_at_node", from)));
        }
        long budget = loopState.resolvedBudget();
        if (loopState.needsBudgetResolution()) {
            budget = resolveLoopBudget(context, loop);
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "loop_budget_resolved", Map.of(
                    "loop_id", loopId,
                    "instance_id", loopState.instanceId(),
                    "resolved_budget", budget)));
        }

        long nextIteration = loopState.iterationCount() + 1L;
        if (nextIteration > budget) {
            JsonNode onExhaust = loop.get("on_exhaust");
            String target = requiredText(onExhaust, "to");
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "transition_taken", Map.of(
                    "from", from,
                    "to", target)));
            return appendLoopExhaustTarget(
                    context,
                    seq,
                    timestamp,
                    from,
                    target,
                    textOr(onExhaust, "reason", "loop '" + loopId + "' exhausted"));
        }

        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "loop_continued", Map.of(
                "loop_id", loopId,
                "instance_id", loopState.instanceId(),
                "iteration", nextIteration)));
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "transition_taken", Map.of(
                "from", from,
                "to", entryNode)));
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "node_entered", Map.of(
                "node_id", entryNode)));
        return seq + 1;
    }

    private long appendLoopExhaustTarget(
            StoredRunContext context,
            long seq,
            String timestamp,
            String from,
            String target,
            String reason) {
        if ("__complete__".equals(target)) {
            RunAction action = RunAction.complete(reason);
            return appendTerminalEventAndNotification(context, seq, timestamp, action);
        }
        if ("__escalate__".equals(target)) {
            RunAction action = RunAction.escalate(reason);
            return appendTerminalEventAndNotification(context, seq, timestamp, action);
        }
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "node_entered", Map.of(
                "node_id", target,
                "from", from)));
        return seq + 1;
    }

    private static JsonNode findLoop(WorkflowSpec spec, String loopId) {
        for (JsonNode loop : spec.loops()) {
            if (loopId.equals(text(loop, "id"))) {
                return loop;
            }
        }
        throw new ForgeException("loop '" + loopId + "' not found");
    }

    private static LoopRuntimeState currentLoopRuntimeState(
            StoredRunContext context,
            String loopId,
            String controllerNode,
            long controllerVisit) {
        LoopState selected = null;
        for (LoopState loopState : context.state().loopStates().values()) {
            if (!loopId.equals(loopState.loopId())
                    || !controllerNode.equals(loopState.controllerNode())
                    || loopState.controllerVisit() > controllerVisit) {
                continue;
            }
            if (selected == null
                    || loopState.controllerVisit() > selected.controllerVisit()
                    || (loopState.controllerVisit() == selected.controllerVisit()
                    && loopState.instanceId().compareTo(selected.instanceId()) > 0)) {
                selected = loopState;
            }
        }
        if (selected == null) {
            return new LoopRuntimeState(
                    loopId + "-" + controllerVisit,
                    true,
                    true,
                    0,
                    0);
        }
        return new LoopRuntimeState(
                selected.instanceId(),
                false,
                selected.resolvedBudget() == null,
                selected.resolvedBudget() == null ? 0 : selected.resolvedBudget(),
                selected.iterationCount());
    }

    private static long resolveLoopBudget(StoredRunContext context, JsonNode loop) {
        JsonNode budget = loop.get("budget");
        String kind = text(budget, "kind");
        if ("literal".equals(kind)) {
            return budget.get("max_iterations").asLong();
        }
        if ("artifact_field".equals(kind)) {
            String loopId = requiredText(loop, "id");
            String artifactName = requiredText(budget, "artifact");
            String field = requiredText(budget, "field");
            ArtifactRecord record = context.state().artifactIndex().get(artifactName);
            if (record == null) {
                throw new ForgeException("loop '"
                        + loopId
                        + "' references artifact '"
                        + artifactName
                        + "' for budget, but it has not been produced");
            }
            Path artifactPath = ArtifactStore.resolveArtifactPath(context.runDir(), context.state(), record);
            JsonNode value = Json.read(artifactPath, JsonNode.class);
            if (!value.isObject()) {
                throw new ForgeException("loop '"
                        + loopId
                        + "' budget artifact '"
                        + artifactName
                        + "' must be a JSON object");
            }
            JsonNode fieldValue = value.get(field);
            if (fieldValue == null
                    || !fieldValue.isIntegralNumber()
                    || !fieldValue.canConvertToLong()
                    || fieldValue.asLong() < 0L) {
                throw new ForgeException("loop '"
                        + loopId
                        + "' budget field '"
                        + field
                        + "' in artifact '"
                        + artifactName
                        + "' must be a non-negative integer");
            }
            long resolved = fieldValue.asLong();
            if (resolved > UNSIGNED_INT_MAX) {
                throw new ForgeException("loop '"
                        + loopId
                        + "' budget field '"
                        + field
                        + "' value "
                        + resolved
                        + " exceeds u32 maximum");
            }
            return resolved;
        }
        throw new ForgeException("loop '" + requiredText(loop, "id") + "' budget kind must be literal or artifact_field");
    }

    private static JsonNode currentNode(WorkflowSpec spec, dev.llaith.forge.workflow.state.DerivedRunState state) {
        return findNode(spec, state.currentNode());
    }

    private static JsonNode findNode(WorkflowSpec spec, @Nullable String nodeId) {
        for (JsonNode node : spec.nodes()) {
            if (nodeId != null && nodeId.equals(text(node, "id"))) {
                return node;
            }
        }
        throw new ForgeException("unknown current node '" + nodeId + "'");
    }

    private static String routeDecisionValue(JsonNode route, Map<String, String> decisions) {
        if ("by_field".equals(text(route, "mode"))) {
            String field = text(route, "field");
            String value = decisions.get(field);
            return value == null ? "" : value;
        }
        return decisions.values().stream().findFirst().orElse("");
    }

    private static String completionMessage(StoredRunContext context) {
        String slug = context.state().slug() == null || context.state().slug().isBlank()
                ? "unknown"
                : context.state().slug();
        return "run '" + slug + "' completed";
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new ForgeException("subrun operation is missing " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    interface TerminalNotificationPlanner {
        @Nullable ObjectNode notificationOperationFor(StoredRunContext context, RunAction action);
    }

    private record LoopRuntimeState(
            String instanceId,
            boolean opened,
            boolean needsBudgetResolution,
            long resolvedBudget,
            long iterationCount
    ) {
    }
}
