package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.Set;

public record PendingOperation(
        String operationId,
        String kind,
        @Nullable String nodeId,
        JsonNode payload
) {
    public PendingOperation {
        if (operationId == null || operationId.isBlank()) {
            throw new ForgeException("operation_id must be a nonblank string");
        }
        if (kind == null || kind.isBlank()) {
            throw new ForgeException("operation_kind must be a nonblank string");
        }
        if (!isSupportedKind(kind)) {
            throw new ForgeException("unsupported operation_kind '" + kind + "' for operation '" + operationId + "'");
        }
        if (nodeId != null && nodeId.isBlank()) {
            throw new ForgeException("operation field 'node_id' must be a nonblank string");
        }
        if (payload == null || !payload.isObject()) {
            throw new ForgeException("operation '" + operationId + "' of kind '" + kind + "' requires an object payload");
        }
        validatePayloadShape(operationId, kind, payload);
        payload = payload.deepCopy();
    }

    public static PendingOperation from(JsonNode operation) {
        if (operation == null || !operation.isObject()) {
            throw new ForgeException("operation payload must be an object");
        }
        String id = requiredText(operation, "operation_id");
        String kind = requiredText(operation, "operation_kind");
        JsonNode payload = payloadFor(operation, id, kind);
        String nodeId = optionalText(operation, "node_id");
        if (nodeId == null) {
            nodeId = optionalText(payload, "node_id");
        }
        return new PendingOperation(id, kind, nodeId, payload.deepCopy());
    }

    private static JsonNode payloadFor(JsonNode operation, String id, String kind) {
        String field = payloadField(kind, id);
        JsonNode nested = operation.get(field);
        if (nested == null || !nested.isObject()) {
            throw new ForgeException("operation '" + id + "' of kind '" + kind
                    + "' requires nested object field '" + field + "'");
        }
        return nested;
    }

    private static void validatePayloadShape(String id, String kind, JsonNode payload) {
        if ("subrun".equals(kind)) {
            validateSubrunPayload(id, payload);
        }
    }

    private static void validateSubrunPayload(String id, JsonNode payload) {
        Set<String> allowedFields = Set.of(
                "node_id",
                "child_slug",
                "frozen_child_spec_path",
                "frozen_child_ir_path",
                "frozen_child_interface_path",
                "child_run_dir",
                "request_artifact",
                "request_artifact_path",
                "summary_artifact",
                "import_artifacts");
        Iterator<String> fieldNames = payload.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!allowedFields.contains(field)) {
                throw new ForgeException("operation '" + id + "' of kind 'subrun'"
                        + " has unsupported payload field '" + field + "'");
            }
        }
        requiredText(payload, "node_id");
        requiredText(payload, "child_slug");
        requiredText(payload, "frozen_child_spec_path");
        requiredText(payload, "frozen_child_ir_path");
        requiredText(payload, "frozen_child_interface_path");
        requiredText(payload, "child_run_dir");
        requiredText(payload, "request_artifact");
        requiredText(payload, "request_artifact_path");
        requiredText(payload, "summary_artifact");
        JsonNode imports = payload.get("import_artifacts");
        if (imports == null || !imports.isObject()) {
            throw new ForgeException("operation '" + id + "' of kind 'subrun'"
                    + " requires object payload field 'import_artifacts'");
        }
        imports.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new ForgeException("operation '" + id + "' of kind 'subrun'"
                        + " import_artifacts field '" + entry.getKey() + "' must be a nonblank string");
            }
        });
    }

    private static boolean isSupportedKind(String kind) {
        return switch (kind) {
            case "node_dispatch", "notification", "subrun", "human_review" -> true;
            default -> false;
        };
    }

    private static String payloadField(String kind, String id) {
        return switch (kind) {
            case "node_dispatch" -> "dispatch";
            case "notification" -> "notification";
            case "subrun" -> "subrun";
            case "human_review" -> "review";
            default -> throw new ForgeException("unsupported operation_kind '" + kind + "' for operation '" + id + "'");
        };
    }

    private static @Nullable String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new ForgeException("operation field '" + field + "' must be a nonblank string");
        }
        return value.asText();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ForgeException("operation payload field '" + field + "' must be a nonblank string");
        }
        return value.asText();
    }
}
