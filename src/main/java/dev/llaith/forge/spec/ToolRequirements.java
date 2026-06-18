package dev.llaith.forge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public record ToolRequirements(List<String> allOf, List<List<String>> anyOf) {
    public ToolRequirements {
        allOf = allOf == null ? List.of() : List.copyOf(allOf);
        List<List<String>> groups = new ArrayList<>();
        if (anyOf != null) {
            for (List<String> group : anyOf) {
                groups.add(group == null ? List.of() : List.copyOf(group));
            }
        }
        anyOf = List.copyOf(groups);
    }

    public static ToolRequirements empty() {
        return new ToolRequirements(List.of(), List.of());
    }

    public static ToolRequirements fromJson(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return empty();
        }
        if (!(node instanceof ObjectNode object)) {
            throw new ForgeException("tool_requirements must be a JSON object");
        }
        assertAllowedFields(object, "tool_requirements", Set.of("all_of", "any_of"));
        List<String> allOf = commandArray(object.get("all_of"), "tool_requirements.all_of");
        List<List<String>> anyOf = anyOfGroups(object.get("any_of"));
        validateDuplicateFree(allOf, anyOf);
        return new ToolRequirements(allOf, anyOf);
    }

    public boolean isEmpty() {
        return allOf.isEmpty() && anyOf.isEmpty();
    }

    public ObjectNode toJson() {
        ObjectNode root = Json.mapper().createObjectNode();
        ArrayNode all = Json.mapper().createArrayNode();
        allOf.forEach(all::add);
        root.set("all_of", all);
        ArrayNode any = Json.mapper().createArrayNode();
        for (List<String> group : anyOf) {
            ArrayNode alternatives = Json.mapper().createArrayNode();
            group.forEach(alternatives::add);
            any.add(alternatives);
        }
        root.set("any_of", any);
        return root;
    }

    private static List<List<String>> anyOfGroups(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ForgeException("tool_requirements.any_of must be a JSON array");
        }
        List<List<String>> groups = new ArrayList<>();
        int index = 0;
        for (JsonNode group : node) {
            List<String> commands = commandArray(group, "tool_requirements.any_of[" + index + "]");
            if (commands.isEmpty()) {
                throw new ForgeException("tool_requirements.any_of[" + index + "] must not be empty");
            }
            groups.add(commands);
            index++;
        }
        return List.copyOf(groups);
    }

    private static List<String> commandArray(@Nullable JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ForgeException(label + " must be a JSON array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new ForgeException(label + " must contain only command names");
            }
            values.add(validateCommandName(value.asText(), label));
        }
        return List.copyOf(values);
    }

    private static String validateCommandName(String value, String label) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ForgeException(label + " command names must not be empty");
        }
        if (!trimmed.equals(value)) {
            throw new ForgeException(label + " command name '" + value + "' must not contain surrounding whitespace");
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean allowed = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.'
                    || ch == '_'
                    || ch == '+'
                    || ch == '-';
            if (!allowed) {
                throw new ForgeException(label + " command name '" + value
                        + "' must use only ASCII letters, digits, '.', '_', '+', or '-'");
            }
        }
        return value;
    }

    private static void validateDuplicateFree(List<String> allOf, List<List<String>> anyOf) {
        Set<String> seen = new TreeSet<>();
        for (String command : allOf) {
            if (!seen.add(command)) {
                throw new ForgeException("tool_requirements command name '" + command + "' is duplicated");
            }
        }
        for (List<String> group : anyOf) {
            for (String command : group) {
                if (!seen.add(command)) {
                    throw new ForgeException("tool_requirements command name '" + command + "' is duplicated");
                }
            }
        }
    }

    private static void assertAllowedFields(ObjectNode object, String label, Set<String> allowed) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            String field = fields.next().getKey();
            if (!allowed.contains(field)) {
                throw new ForgeException(label + " contains unknown field '" + field + "'");
            }
        }
    }
}
