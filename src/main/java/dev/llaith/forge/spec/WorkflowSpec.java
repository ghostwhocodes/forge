package dev.llaith.forge.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WorkflowSpec(
        int version,
        @JsonProperty("routing_mode") String routingMode,
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("entry_node") String entryNode,
        @JsonProperty("tool_requirements") JsonNode toolRequirements,
        @JsonProperty("interface") JsonNode interfaceSpec,
        JsonNode agents,
        List<JsonNode> nodes,
        JsonNode loops,
        JsonNode artifacts,
        JsonNode notifications
) {
    public WorkflowSpec {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public WorkflowSpec(
            int version,
            String routingMode,
            String workflowId,
            String entryNode,
            JsonNode interfaceSpec,
            JsonNode agents,
            List<JsonNode> nodes,
            JsonNode loops,
            JsonNode artifacts,
            JsonNode notifications
    ) {
        this(
                version,
                routingMode,
                workflowId,
                entryNode,
                null,
                interfaceSpec,
                agents,
                nodes,
                loops,
                artifacts,
                notifications);
    }

    public String effectiveRoutingMode() {
        if (routingMode != null && !routingMode.isBlank()) {
            return routingMode;
        }
        return "structured";
    }

    public String routingModeSource() {
        if (routingMode != null && !routingMode.isBlank()) {
            return "explicit";
        }
        return "version_default";
    }

    public List<String> routingModeWarnings() {
        return List.of();
    }

    public ToolRequirements effectiveToolRequirements() {
        return ToolRequirements.fromJson(toolRequirements);
    }
}
