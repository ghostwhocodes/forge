package dev.llaith.forge.workflow.reducer;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.ArtifactRole;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.LoopState;
import dev.llaith.forge.workflow.state.PendingOperation;
import dev.llaith.forge.workflow.state.RunStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WorkflowReducer {
    private static final long UNSIGNED_INT_MAX = 4_294_967_295L;

    private WorkflowReducer() {
    }

    public static DerivedRunState deriveState(List<EventEnvelope> events) {
        StateBuilder state = new StateBuilder();
        long expectedSeq = 1;
        for (EventEnvelope event : events) {
            if (event.seq() != expectedSeq) {
                throw new ForgeException("event sequence mismatch: expected " + expectedSeq + " but found " + event.seq());
            }
            state.lastEventSeq = event.seq();
            applyEvent(state, event);
            expectedSeq++;
        }
        return state.build();
    }

    private static void applyEvent(StateBuilder state, EventEnvelope event) {
        switch (event.type()) {
            case "run_initialized" -> {
                if (state.runId != null) {
                    throw new ForgeException("run already initialized");
                }
                state.runStatus = RunStatus.RUNNING;
                state.runId = requiredText(event, "run_id");
                state.slug = requiredText(event, "slug");
                state.workflowId = requiredText(event, "workflow_id");
                state.currentNode = requiredText(event, "entry_node");
            }
            case "node_entered" -> {
                ensureInitialized(state);
                state.runStatus = RunStatus.RUNNING;
                state.currentNode = requiredText(event, "node_id");
                state.nodeVisitCounts.merge(state.currentNode, 1L, Long::sum);
                state.pendingOperation = null;
                state.escalationReason = null;
            }
            case "operation_prepared" -> {
                ensureInitialized(state);
                if (state.pendingOperation != null) {
                    throw new ForgeException("operation already pending");
                }
                PendingOperation operation = PendingOperation.from(requiredNode(event, "operation"));
                if (operation.nodeId() != null
                        && state.currentNode != null
                        && !state.currentNode.equals(operation.nodeId())) {
                    throw new ForgeException("current node mismatch: expected '"
                            + operation.nodeId()
                            + "', found '"
                            + state.currentNode
                            + "'");
                }
                state.pendingOperation = operation;
                state.runStatus = switch (state.pendingOperation.kind()) {
                    case "node_dispatch", "notification" -> RunStatus.WAITING_FOR_AGENT;
                    case "human_review" -> RunStatus.WAITING_FOR_HUMAN;
                    case "subrun" -> RunStatus.WAITING_FOR_SUBRUN;
                    default -> throw new ForgeException("unsupported operation_kind '" + state.pendingOperation.kind() + "'");
                };
            }
            case "operation_started", "operation_heartbeat", "operation_succeeded", "operation_failed" -> {
                ensureInitialized(state);
                String operationId = requiredText(event, "operation_id");
                PendingOperation pending = ensureCurrentOperation(state, operationId);
                JsonNode execution = requiredNode(event, "execution");
                validateExecutionMatches(event, pending, execution);
                state.operationExecutions.put(operationId, execution.deepCopy());
            }
            case "operation_completed" -> {
                ensureInitialized(state);
                String operationId = requiredText(event, "operation_id");
                ensureCurrentOperation(state, operationId);
                state.pendingOperation = null;
                state.runStatus = RunStatus.RUNNING;
            }
            case "artifact_written" -> {
                ensureInitialized(state);
                ArtifactRecord artifact = convertRequired(event, "artifact", ArtifactRecord.class);
                state.artifactIndex.put(artifact.name(), artifact);
            }
            case "artifact_metadata_recorded" -> {
                ensureInitialized(state);
                String artifactName = requiredText(event, "artifact_name");
                String blobId = requiredText(event, "blob_id");
                ArtifactBinding binding = convertOptional(event, "binding", ArtifactBinding.class);
                ArtifactRole role = convertOptional(event, "role", ArtifactRole.class);
                state.artifactMetadata.put(artifactName, new ArtifactMetadata(blobId, binding, role));
            }
            case "run_completed" -> {
                ensureInitialized(state);
                state.pendingOperation = null;
                state.runStatus = RunStatus.COMPLETED;
                state.completedMessage = requiredText(event, "message");
            }
            case "run_escalated" -> {
                ensureInitialized(state);
                state.pendingOperation = null;
                state.runStatus = RunStatus.ESCALATED;
                state.escalationReason = requiredText(event, "reason");
            }
            case "decision_recorded" -> {
                ensureInitialized(state);
                state.decisions.put(requiredText(event, "key"), requiredText(event, "value"));
            }
            case "human_input_recorded" -> {
                ensureInitialized(state);
                String nodeId = requiredText(event, "node_id");
                if (state.currentNode == null || !state.currentNode.equals(nodeId)) {
                    throw new ForgeException("current node mismatch: expected '"
                            + nodeId
                            + "', found '"
                            + state.currentNode
                            + "'");
                }
                state.decisions.putAll(convertRequiredMap(event, "fields"));
                state.runStatus = RunStatus.WAITING_FOR_HUMAN;
            }
            case "notification_delivered" -> {
                ensureInitialized(state);
                String notificationId = requiredText(event, "notification_id");
                state.deliveredNotifications.put(notificationId, requiredText(event, "level"));
                state.failedNotifications.remove(notificationId);
            }
            case "notification_failed" -> {
                ensureInitialized(state);
                String notificationId = requiredText(event, "notification_id");
                state.failedNotifications.put(notificationId, requiredText(event, "level"));
            }
            case "transition_taken" -> {
                ensureInitialized(state);
                String from = requiredText(event, "from");
                String to = requiredText(event, "to");
                state.edgeVisitCounts.merge(from + "->" + to, 1L, Long::sum);
            }
            case "route_field_recorded" -> {
                ensureInitialized(state);
                state.routeFields.put(
                        requiredText(event, "node_id") + "." + requiredText(event, "field"),
                        requiredNode(event, "value").asText());
            }
            case "loop_instance_opened" -> {
                ensureInitialized(state);
                String instanceId = requiredText(event, "instance_id");
                state.loopStates.put(instanceId, new LoopState(
                        requiredText(event, "loop_id"),
                        instanceId,
                        requiredText(event, "controller_node"),
                        requiredText(event, "entry_node"),
                        requiredUnsignedInt(event, "controller_visit"),
                        0,
                        null));
            }
            case "loop_budget_resolved" -> {
                ensureInitialized(state);
                String instanceId = requiredText(event, "instance_id");
                LoopState loop = state.loopStates.get(instanceId);
                if (loop != null) {
                    state.loopStates.put(instanceId, loop.withResolvedBudget(requiredUnsignedInt(event, "resolved_budget")));
                }
            }
            case "loop_continued" -> {
                ensureInitialized(state);
                String instanceId = requiredText(event, "instance_id");
                LoopState loop = state.loopStates.get(instanceId);
                if (loop != null) {
                    state.loopStates.put(instanceId, loop.withIteration(requiredUnsignedInt(event, "iteration")));
                }
            }
            case "node_failed" -> {
                ensureInitialized(state);
                state.pendingOperation = null;
                state.escalationReason = requiredText(event, "reason");
                state.runStatus = RunStatus.FAILED;
            }
            default -> throw new ForgeException("unsupported event type: " + event.type());
        }
    }

    private static void ensureInitialized(StateBuilder state) {
        if (state.runId == null) {
            throw new ForgeException("run not initialized");
        }
    }

    private static PendingOperation ensureCurrentOperation(StateBuilder state, String operationId) {
        PendingOperation pending = state.pendingOperation;
        if (pending == null) {
            throw new ForgeException("no current operation recorded");
        }
        if (!pending.operationId().equals(operationId)) {
            throw new ForgeException("operation mismatch: expected '"
                    + pending.operationId()
                    + "', got '"
                    + operationId
                    + "'");
        }
        return pending;
    }

    private static void validateExecutionMatches(EventEnvelope event, PendingOperation pending, JsonNode execution) {
        String executionOperationId = requiredExecutionText(event, execution, "operation_id");
        if (!executionOperationId.equals(pending.operationId())) {
            throw new ForgeException("operation mismatch: expected '"
                    + pending.operationId()
                    + "', got '"
                    + executionOperationId
                    + "'");
        }
        String operationKind = requiredExecutionText(event, execution, "operation_kind");
        if (!operationKind.equals(pending.kind())) {
            throw new ForgeException("operation kind mismatch: pending operation '"
                    + pending.operationId()
                    + "' is '"
                    + pending.kind()
                    + "', execution is '"
                    + operationKind
                    + "'");
        }
    }

    private static String requiredText(EventEnvelope event, String name) {
        String value = event.textField(name);
        if (value == null || value.isBlank()) {
            throw new ForgeException("event " + event.type() + " is missing " + name);
        }
        return value;
    }

    private static JsonNode requiredNode(EventEnvelope event, String name) {
        JsonNode value = event.field(name);
        if (value == null || value.isNull()) {
            throw new ForgeException("event " + event.type() + " is missing " + name);
        }
        return value;
    }

    private static long requiredUnsignedInt(EventEnvelope event, String name) {
        JsonNode value = requiredNode(event, name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ForgeException("event " + event.type() + " field " + name
                    + " must be an unsigned 32-bit integer");
        }
        long numeric = value.asLong();
        if (numeric < 0L || numeric > UNSIGNED_INT_MAX) {
            throw new ForgeException("event " + event.type() + " field " + name
                    + " must be an unsigned 32-bit integer");
        }
        return numeric;
    }

    private static <T> T convertRequired(EventEnvelope event, String name, Class<T> type) {
        JsonNode value = event.field(name);
        if (value == null || value.isNull()) {
            throw new ForgeException("event " + event.type() + " is missing " + name);
        }
        return Json.mapper().convertValue(value, type);
    }

    private static <T> @Nullable T convertOptional(EventEnvelope event, String name, Class<T> type) {
        JsonNode value = event.field(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return Json.mapper().convertValue(value, type);
    }

    private static Map<String, String> convertRequiredMap(EventEnvelope event, String name) {
        JsonNode value = requiredNode(event, name);
        if (!value.isObject()) {
            throw new ForgeException("event " + event.type() + " field " + name + " must be an object");
        }
        Map<String, String> map = new TreeMap<>();
        var fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return map;
    }

    private static String requiredExecutionText(EventEnvelope event, JsonNode execution, String name) {
        JsonNode value = execution.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ForgeException("event " + event.type() + " execution is missing " + name);
        }
        return value.asText();
    }

    private static final class StateBuilder {
        private RunStatus runStatus = RunStatus.IDLE;
        private @Nullable String runId;
        private @Nullable String slug;
        private @Nullable String workflowId;
        private @Nullable String currentNode;
        private @Nullable Long lastEventSeq;
        private @Nullable String completedMessage;
        private @Nullable String escalationReason;
        private @Nullable PendingOperation pendingOperation;
        private final Map<String, ArtifactRecord> artifactIndex = new TreeMap<>();
        private final Map<String, ArtifactMetadata> artifactMetadata = new TreeMap<>();
        private final Map<String, Long> nodeVisitCounts = new TreeMap<>();
        private final Map<String, Long> edgeVisitCounts = new TreeMap<>();
        private final Map<String, String> decisions = new TreeMap<>();
        private final Map<String, String> routeFields = new TreeMap<>();
        private final Map<String, LoopState> loopStates = new TreeMap<>();
        private final Map<String, String> deliveredNotifications = new TreeMap<>();
        private final Map<String, String> failedNotifications = new TreeMap<>();
        private final Map<String, JsonNode> operationExecutions = new TreeMap<>();

        private DerivedRunState build() {
            return new DerivedRunState(
                    runStatus,
                    runId,
                    slug,
                    workflowId,
                    currentNode,
                    lastEventSeq,
                    completedMessage,
                    escalationReason,
                    artifactIndex,
                    artifactMetadata,
                    pendingOperation,
                    nodeVisitCounts,
                    edgeVisitCounts,
                    decisions,
                    routeFields,
                    loopStates,
                    deliveredNotifications,
                    failedNotifications,
                    operationExecutions);
        }
    }
}
