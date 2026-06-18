package dev.llaith.forge.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record ProjectionBacklogEntry(
        @JsonProperty("first_event_seq") long firstEventSeq,
        @JsonProperty("artifact_name") @Nullable String artifactName,
        @JsonProperty("canonical_path") String canonicalPath,
        @JsonProperty("projection_path") String projectionPath
) {
}
