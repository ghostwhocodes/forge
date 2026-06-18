package dev.llaith.forge.workflow.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.ArtifactRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorkflowRunnerTest {
    private static final ObjectMapper MAPPER = Json.mapper();

    @Test
    void planInitEventsMatchesRustOrderingForSeedArtifacts() {
        WorkflowSpec spec = sampleSpec();
        ArtifactRecord request = new ArtifactRecord(
                "request",
                "artifacts/__canonical/request.json",
                null,
                "application/json");

        var events = WorkflowRunner.planInitEvents(
                spec,
                RunSlug.parse("sample"),
                List.of(request),
                Map.of("request", new ArtifactMetadata("blob-1", ArtifactBinding.local(), ArtifactRole.REQUEST)),
                "2026-04-20T12:00:00Z");

        assertThat(events).hasSize(4);
        assertThat(events).extracting(event -> event.seq()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).extracting(event -> event.type()).containsExactly(
                "run_initialized",
                "artifact_written",
                "artifact_metadata_recorded",
                "node_entered");
        assertThat(events.getFirst().textField("run_id")).isEqualTo("sample");
        assertThat(events.getLast().textField("node_id")).isEqualTo("plan");
    }

    @Test
    void artifactRoleRejectsUnknownJsonValue() {
        assertThatThrownBy(() -> ArtifactRole.fromJson("mystery"))
                .hasMessage("unknown artifact role: mystery");
    }

    private static WorkflowSpec sampleSpec() {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan");
        return new WorkflowSpec(
                2,
                "structured",
                "runner_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of(node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
    }
}
