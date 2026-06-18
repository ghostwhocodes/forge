package dev.llaith.forge.workflow.runner;

import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WorkflowRunner {
    private WorkflowRunner() {
    }

    public static List<EventEnvelope> planInitEvents(
            WorkflowSpec spec,
            RunSlug slug,
            List<ArtifactRecord> seedArtifacts,
            Map<String, ArtifactMetadata> seedArtifactMetadata,
            String timestamp) {
        List<EventEnvelope> events = new ArrayList<>();
        long seq = 1;
        events.add(EventEnvelope.of(seq++, timestamp, "run_initialized", Map.of(
                "run_id", slug.asString(),
                "slug", slug.asString(),
                "workflow_id", spec.workflowId(),
                "spec_version", spec.version(),
                "entry_node", spec.entryNode()
        )));
        for (ArtifactRecord artifact : seedArtifacts) {
            events.add(EventEnvelope.of(seq++, timestamp, "artifact_written", Map.of("artifact", artifact)));
        }
        for (Map.Entry<String, ArtifactMetadata> entry : seedArtifactMetadata.entrySet()) {
            ArtifactMetadata metadata = entry.getValue();
            events.add(EventEnvelope.of(seq++, timestamp, "artifact_metadata_recorded", Map.of(
                    "artifact_name", entry.getKey(),
                    "blob_id", metadata.blobId(),
                    "binding", metadata.binding(),
                    "role", metadata.role()
            )));
        }
        events.add(EventEnvelope.of(seq, timestamp, "node_entered", Map.of("node_id", spec.entryNode())));
        return List.copyOf(events);
    }
}
