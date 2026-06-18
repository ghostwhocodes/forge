package dev.llaith.forge.workflow.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record PendingHumanReview(
        String nodeId,
        String message,
        List<JsonNode> fields,
        @Nullable String instructions
) {
    public PendingHumanReview {
        fields = List.copyOf(fields);
    }
}
