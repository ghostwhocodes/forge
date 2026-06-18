package dev.llaith.forge.storage;

import dev.llaith.forge.workflow.event.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EventLogStorageTest {
    @TempDir
    private Path tempDir;

    @Test
    void missingEventLogReadsAsEmpty() {
        assertThat(EventLog.readEvents(tempDir.resolve("missing/events.ndjson"))).isEmpty();
    }

    @Test
    void appendEventWritesFlatNdjsonAndReadIgnoresBlankLines() throws Exception {
        Path events = tempDir.resolve("run/events.ndjson");
        EventEnvelope first = EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "review_only",
                "spec_version", 2,
                "entry_node", "review"
        ));
        EventEnvelope second = EventEnvelope.of(2, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                "node_id", "review"
        ));

        EventLog.appendEvent(events, first);
        Files.writeString(events, "\n", StandardOpenOption.APPEND);
        EventLog.appendEvent(events, second);

        String raw = Files.readString(events);
        assertThat(raw).contains("\"type\":\"run_initialized\"");
        assertThat(raw).doesNotContain("\"event\"");
        assertThat(EventLog.readEvents(events)).hasSize(2);
        assertThat(EventLog.readEvents(events).get(1).textField("node_id")).isEqualTo("review");
    }

    @Test
    void lastEventSeqReadsTailRecordAndIgnoresTrailingBlankLines() throws Exception {
        Path events = tempDir.resolve("run/events.ndjson");
        assertThat(EventLog.lastEventSeq(events).isEmpty()).isTrue();
        EventLog.appendEvent(events, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "review_only",
                "spec_version", 2,
                "entry_node", "review"
        )));
        EventLog.appendEvent(events, EventEnvelope.of(2, "2026-04-20T12:00:01Z", "node_entered", Map.of(
                "node_id", "review"
        )));
        Files.writeString(events, "\n\n", StandardOpenOption.APPEND);

        assertThat(EventLog.lastEventSeq(events).orElseThrow()).isEqualTo(2L);
    }

    @Test
    void readEventsReturnsStoredTerminalEventPayload() {
        Path events = tempDir.resolve("run/events.ndjson");

        EventLog.appendEvent(events, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "review_only",
                "spec_version", 2,
                "entry_node", "review"
        )));
        EventLog.appendEvent(events, EventEnvelope.of(2, "2026-04-20T12:00:01Z", "run_completed", Map.of(
                "message", "done"
        )));

        assertThat(EventLog.readEvents(events))
                .extracting(EventEnvelope::type)
                .containsExactly("run_initialized", "run_completed");
        assertThat(EventLog.readEvents(events).getLast().textField("message")).isEqualTo("done");
    }

    @Test
    void readEventsReportsParseErrorsWithPath() throws Exception {
        Path events = tempDir.resolve("run/events.ndjson");
        Files.createDirectories(events.getParent());
        Files.writeString(events, "{not-json}\n");

        assertThatThrownBy(() -> EventLog.readEvents(events))
                .hasMessageContaining("failed to parse event line in " + events);
    }
}
