package dev.llaith.forge.workflow.runner;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

public record PendingSubrun(
        String nodeId,
        String workflowRef,
        String requestArtifact,
        String summaryArtifact,
        Map<String, String> importArtifacts,
        @Nullable String message,
        @Nullable String childSlug,
        @Nullable String frozenChildSpecPath,
        @Nullable String frozenChildIrPath,
        @Nullable String frozenChildInterfacePath,
        @Nullable String requestArtifactPath,
        @Nullable String childRunDir
) {
    public PendingSubrun(
            String nodeId,
            String workflowRef,
            String requestArtifact,
            String summaryArtifact,
            Map<String, String> importArtifacts,
            @Nullable String message) {
        this(nodeId, workflowRef, requestArtifact, summaryArtifact, importArtifacts, message, null, null, null, null, null, null);
    }

    public PendingSubrun {
        importArtifacts = Map.copyOf(new TreeMap<>(importArtifacts));
    }
}
