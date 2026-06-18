package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record ArtifactRecord(
        String name,
        String path,
        @JsonProperty("producer_node") @Nullable String producerNode,
        @JsonProperty("media_type") @Nullable String mediaType
) {
}
