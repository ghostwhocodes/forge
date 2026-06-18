package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

public record DerivedRunState(
        RunStatus runStatus,
        @Nullable String runId,
        @Nullable String slug,
        @Nullable String workflowId,
        @Nullable String currentNode,
        @Nullable Long lastEventSeq,
        @Nullable String completedMessage,
        @Nullable String escalationReason,
        Map<String, ArtifactRecord> artifactIndex,
        Map<String, ArtifactMetadata> artifactMetadata,
        @Nullable PendingOperation pendingOperation,
        Map<String, Long> nodeVisitCounts,
        Map<String, Long> edgeVisitCounts,
        Map<String, String> decisions,
        Map<String, String> routeFields,
        Map<String, LoopState> loopStates,
        Map<String, String> deliveredNotifications,
        Map<String, String> failedNotifications,
        Map<String, JsonNode> operationExecutions
) {
    public DerivedRunState {
        artifactIndex = Map.copyOf(new TreeMap<>(artifactIndex));
        artifactMetadata = Map.copyOf(new TreeMap<>(artifactMetadata));
        nodeVisitCounts = Map.copyOf(new TreeMap<>(nodeVisitCounts));
        edgeVisitCounts = Map.copyOf(new TreeMap<>(edgeVisitCounts));
        decisions = Map.copyOf(new TreeMap<>(decisions));
        routeFields = Map.copyOf(new TreeMap<>(routeFields));
        loopStates = Map.copyOf(new TreeMap<>(loopStates));
        deliveredNotifications = Map.copyOf(new TreeMap<>(deliveredNotifications));
        failedNotifications = Map.copyOf(new TreeMap<>(failedNotifications));
        operationExecutions = Map.copyOf(new TreeMap<>(operationExecutions));
    }

    public DerivedRunState(
            RunStatus runStatus,
            @Nullable String runId,
            @Nullable String slug,
            @Nullable String workflowId,
            @Nullable String currentNode,
            @Nullable Long lastEventSeq,
            @Nullable String completedMessage,
            @Nullable String escalationReason,
            Map<String, ArtifactRecord> artifactIndex,
            Map<String, ArtifactMetadata> artifactMetadata) {
        this(
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
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    public DerivedRunState(
            RunStatus runStatus,
            @Nullable String runId,
            @Nullable String slug,
            @Nullable String workflowId,
            @Nullable String currentNode,
            @Nullable Long lastEventSeq,
            @Nullable String completedMessage,
            @Nullable String escalationReason,
            Map<String, ArtifactRecord> artifactIndex,
            Map<String, ArtifactMetadata> artifactMetadata,
            @Nullable PendingOperation pendingOperation,
            Map<String, Long> nodeVisitCounts,
            Map<String, String> decisions,
            Map<String, String> routeFields,
            Map<String, LoopState> loopStates) {
        this(
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
                Map.of(),
                decisions,
                routeFields,
                loopStates,
                Map.of(),
                Map.of(),
                Map.of());
    }

    public static DerivedRunState idle() {
        return new DerivedRunState(
                RunStatus.IDLE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }
}
