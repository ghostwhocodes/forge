package dev.llaith.forge.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Hashing;
import dev.llaith.forge.util.Json;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class WorkflowSpecs {
    private static final String COMPLETE_TARGET = "__complete__";
    private static final String ESCALATE_TARGET = "__escalate__";
    private static final long UNSIGNED_INT_MAX = 4_294_967_295L;

    private WorkflowSpecs() {
    }

    public static WorkflowSpec load(Path path) {
        ObjectNode resolved = readResolvedTree(path);
        try {
            WorkflowSpec spec = Json.mapper().treeToValue(resolved, WorkflowSpec.class);
            validate(spec);
            return spec;
        } catch (JsonProcessingException error) {
            throw new ForgeException("failed to parse workflow spec at " + path, error);
        }
    }

    public static ObjectNode readResolvedTree(Path path) {
        ObjectMapper mapper = Json.mapper();
        try {
            JsonNode root = mapper.readTree(path.toFile());
            if (!(root instanceof ObjectNode object)) {
                throw new ForgeException("workflow spec must be a JSON object");
            }
            ObjectNode copy = object.deepCopy();
            rejectRemovedReleaseFields(copy, path);
            resolveTemplateFiles(copy, path);
            return copy;
        } catch (IOException error) {
            throw new ForgeException("failed to read JSON from " + path, error);
        }
    }

    private static void rejectRemovedReleaseFields(ObjectNode root, Path path) {
        if (root.has("edges")) {
            throw new ForgeException("workflow spec at " + path
                    + " uses removed top-level 'edges'; use node-local structured routes instead");
        }
    }

    public static void write(Path path, WorkflowSpec spec) {
        Filesystem.writeUtf8(path, Json.toPrettyJson(spec));
    }

    public static void validate(WorkflowSpec spec) {
        if (spec.version() != 2) {
            throw new ForgeException("workflow spec version must be 2");
        }
        if (spec.workflowId() == null || spec.workflowId().isBlank()) {
            throw new ForgeException("workflow_id is required");
        }
        if (spec.entryNode() == null || spec.entryNode().isBlank()) {
            throw new ForgeException("entry_node is required");
        }
        if (spec.routingMode() != null && !"structured".equals(spec.routingMode())) {
            throw new ForgeException("routing_mode must be structured");
        }
        ToolRequirements.fromJson(spec.toolRequirements());
        validateArtifacts(spec);
        validateAgents(spec);

        if (spec.nodes() == null || spec.nodes().isEmpty()) {
            throw new ForgeException("nodes must not be empty");
        }

        Set<String> nodeIds = new TreeSet<>();
        for (JsonNode node : spec.nodes()) {
            validateNodeObject(node);
            String id = requiredText(node, "id", "node id");
            validateNodeId(id);
            if (!nodeIds.add(id)) {
                throw new ForgeException("duplicate node id '" + id + "'");
            }
        }
        if (!nodeIds.contains(spec.entryNode())) {
            throw new ForgeException("entry_node '" + spec.entryNode() + "' does not match a node id");
        }

        for (JsonNode node : spec.nodes()) {
            validateNodeReferences(spec, node);
        }
        validateNotifications(spec);
        validateWorkflowInterfaceSpec(spec);
        validateStructuredSpec(spec);
    }

    private static void resolveTemplateFiles(ObjectNode value, Path specPath) {
        JsonNode nodes = value.get("nodes");
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        Path baseDir = specPath.toAbsolutePath().normalize().getParent();
        if (baseDir == null) {
            baseDir = Path.of("").toAbsolutePath();
        }
        for (JsonNode rawNode : nodes) {
            if (!(rawNode instanceof ObjectNode node)) {
                continue;
            }
            String kind = text(node, "kind");
            if ("agent".equals(kind) || "judge".equals(kind)) {
                materializePromptFile(node, baseDir, specPath);
            }
            if ("command".equals(kind)) {
                materializeCommandFile(node, baseDir, specPath);
            }
        }
    }

    private static void materializePromptFile(ObjectNode node, Path baseDir, Path specPath) {
        JsonNode promptFileNode = node.get("prompt_file");
        if (promptFileNode == null) {
            return;
        }
        String nodeId = textOr(node, "id", "<unknown>");
        if (!promptFileNode.isTextual()) {
            throw new ForgeException("node '" + nodeId + "' in " + specPath + " has a non-string prompt_file");
        }
        String promptFile = promptFileNode.asText();
        if (promptFile.trim().isEmpty()) {
            throw new ForgeException("node '" + nodeId + "' in " + specPath + " has an empty prompt_file");
        }
        String existingTemplate = text(node, "prompt_template");
        if (existingTemplate != null && !existingTemplate.trim().isEmpty()) {
            throw new ForgeException("node '" + nodeId + "' in " + specPath
                    + " cannot declare both prompt_template and prompt_file");
        }
        Path promptPath = baseDir.resolve(promptFile).normalize();
        String promptText;
        try {
            promptText = Files.readString(promptPath);
        } catch (IOException error) {
            throw new ForgeException("failed to read prompt file '" + promptPath
                    + "' for node '" + nodeId + "' in " + specPath, error);
        }
        if (promptText.trim().isEmpty()) {
            throw new ForgeException("prompt file '" + promptPath
                    + "' for node '" + nodeId + "' in " + specPath + " is empty");
        }
        node.put("prompt_template", promptText);
        node.remove("prompt_file");
    }

    private static void materializeCommandFile(ObjectNode node, Path baseDir, Path specPath) {
        JsonNode commandFileNode = node.get("command_file");
        if (commandFileNode == null) {
            return;
        }
        String nodeId = textOr(node, "id", "<unknown>");
        if (!commandFileNode.isTextual()) {
            throw new ForgeException("node '" + nodeId + "' in " + specPath + " has a non-string command_file");
        }
        String commandFile = commandFileNode.asText();
        if (commandFile.trim().isEmpty()) {
            throw new ForgeException("node '" + nodeId + "' in " + specPath + " has an empty command_file");
        }
        JsonNode existingCommand = node.get("command");
        if (existingCommand != null && !existingCommand.isNull()) {
            if (!existingCommand.isArray()) {
                throw new ForgeException("node '" + nodeId + "' in " + specPath + " has a non-array command");
            }
            if (!existingCommand.isEmpty()) {
                throw new ForgeException("node '" + nodeId + "' in " + specPath
                        + " cannot declare both command and command_file");
            }
        }
        Path scriptPath = baseDir.resolve(commandFile).normalize();
        if (!Files.isRegularFile(scriptPath)) {
            throw new ForgeException("failed to read command file '" + scriptPath
                    + "' for node '" + nodeId + "' in " + specPath);
        }
        ArrayNode command = Json.mapper().createArrayNode();
        command.add(scriptPath.toString());
        node.set("command", command);
        node.remove("command_file");
    }

    private static void validateArtifacts(WorkflowSpec spec) {
        JsonNode artifacts = spec.artifacts();
        if (artifacts == null || artifacts.isNull()) {
            return;
        }
        ObjectNode object = requireObject(artifacts, "artifacts");
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode artifact = requireObject(entry.getValue(), "artifact '" + entry.getKey() + "'");
            assertAllowedFields(artifact, "artifact '" + entry.getKey() + "'", Set.of("path", "media_type", "schema"));
            requiredText(artifact, "path", "artifact '" + entry.getKey() + "' path");
            requiredText(artifact, "media_type", "artifact '" + entry.getKey() + "' media_type");
            optionalText(artifact.get("schema"), "artifact '" + entry.getKey() + "' schema");
        }
    }

    private static void validateAgents(WorkflowSpec spec) {
        JsonNode agents = spec.agents();
        if (agents == null || agents.isNull()) {
            return;
        }
        ObjectNode object = requireObject(agents, "agents");
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode agent = requireObject(entry.getValue(), "agent '" + entry.getKey() + "'");
            assertAllowedFields(
                    agent,
                    "agent '" + entry.getKey() + "'",
                    Set.of("display_name", "runner", "command", "cwd", "env", "prompt_delivery"));
            String runner = textOr(agent, "runner", "agent");
            if (!"agent".equals(runner) && !"codex".equals(runner)) {
                throw new ForgeException("agent '" + entry.getKey()
                        + "' uses unsupported runner '" + runner
                        + "'; agents may only use 'agent' or 'codex'");
            }
            List<String> command = stringArray(agent.get("command"), "agent '" + entry.getKey() + "' command");
            if ("codex".equals(runner) && !commandContainsCodexExec(command)) {
                throw new ForgeException("agent '" + entry.getKey()
                        + "' declares runner 'codex' but command must include ['codex', 'exec']");
            }
            optionalText(agent.get("display_name"), "agent '" + entry.getKey() + "' display_name");
            optionalText(agent.get("cwd"), "agent '" + entry.getKey() + "' cwd");
            validateStringMap(agent.get("env"), "agent '" + entry.getKey() + "' env");
            String promptDelivery = textOr(agent, "prompt_delivery", "env_path");
            if (!Set.of("env_path", "stdin_file", "argv_path").contains(promptDelivery)) {
                throw new ForgeException("agent '" + entry.getKey()
                        + "' prompt_delivery must be one of env_path, stdin_file, argv_path");
            }
        }
    }

    private static void validateNodeObject(JsonNode rawNode) {
        ObjectNode node = requireObject(rawNode, "node");
        String kind = requiredText(node, "kind", "node kind");
        Set<String> allowed = new HashSet<>(Set.of(
                "id",
                "kind",
                "inputs",
                "outputs",
                "timeout_ms",
                "retry_policy",
                "message",
                "route"));
        switch (kind) {
            case "agent" -> allowed.addAll(Set.of(
                    "agent_id",
                    "prompt_template",
                    "expected_output",
                    "status_key"));
            case "command" -> allowed.addAll(Set.of("command", "cwd", "env", "capture"));
            case "judge" -> allowed.addAll(Set.of(
                    "agent_id",
                    "prompt_template",
                    "decision_schema",
                    "decision_fields"));
            case "human" -> allowed.addAll(Set.of("fields", "instructions"));
            case "subrun" -> allowed.addAll(Set.of(
                    "workflow_ref",
                    "request_artifact",
                    "summary_artifact",
                    "import_artifacts"));
            default -> throw new ForgeException("unsupported workflow node kind: " + kind);
        }
        assertAllowedFields(node, "node '" + textOr(node, "id", "<unknown>") + "'", allowed);
        stringArray(node.get("inputs"), "node '" + textOr(node, "id", "<unknown>") + "' inputs");
        stringArray(node.get("outputs"), "node '" + textOr(node, "id", "<unknown>") + "' outputs");
        optionalNonNegativeLong(node.get("timeout_ms"), "node '" + textOr(node, "id", "<unknown>") + "' timeout_ms");
        validateRetryPolicy(node.get("retry_policy"), textOr(node, "id", "<unknown>"));
        optionalText(node.get("message"), "node message");
    }

    private static void validateNodeReferences(WorkflowSpec spec, JsonNode node) {
        String id = requiredText(node, "id", "node id");
        String kind = requiredText(node, "kind", "node kind");
        for (String input : stringArray(node.get("inputs"), "node '" + id + "' inputs")) {
            if (artifactDefinition(spec, input) == null) {
                throw new ForgeException("node '" + id + "' references unknown input '" + input + "'");
            }
        }
        for (String output : stringArray(node.get("outputs"), "node '" + id + "' outputs")) {
            if (artifactDefinition(spec, output) == null) {
                throw new ForgeException("node '" + id + "' references unknown output '" + output + "'");
            }
        }
        switch (kind) {
            case "agent" -> validateAgentNode(spec, node);
            case "command" -> validateCommandNode(node);
            case "judge" -> validateJudgeNode(spec, node);
            case "human" -> validateHumanNode(node);
            case "subrun" -> validateSubrunNode(spec, node);
            default -> throw new ForgeException("unsupported workflow node kind: " + kind);
        }
    }

    private static void validateAgentNode(WorkflowSpec spec, JsonNode node) {
        String id = requiredText(node, "id", "node id");
        String agentId = requiredText(node, "agent_id", "node '" + id + "' agent_id");
        if (spec.agents() == null || spec.agents().get(agentId) == null) {
            throw new ForgeException("node '" + id + "' references unknown agent '" + agentId + "'");
        }
        requiredText(node, "prompt_template", "node '" + id + "' prompt_template");
    }

    private static void validateCommandNode(JsonNode node) {
        String id = requiredText(node, "id", "node id");
        List<String> command = stringArray(node.get("command"), "node '" + id + "' command");
        if (command.isEmpty()) {
            throw new ForgeException("node '" + id + "' has an empty command");
        }
        optionalText(node.get("cwd"), "node '" + id + "' cwd");
        validateStringMap(node.get("env"), "node '" + id + "' env");
        String capture = textOr(node, "capture", "combined");
        if (!Set.of("combined", "stdout_only", "stderr_only", "none").contains(capture)) {
            throw new ForgeException("node '" + id + "' capture must be one of combined, stdout_only, stderr_only, none");
        }
    }

    private static void validateJudgeNode(WorkflowSpec spec, JsonNode node) {
        String id = requiredText(node, "id", "node id");
        validateAgentNode(spec, node);
        requiredText(node, "decision_schema", "node '" + id + "' decision_schema");
        validateDecisionFields(node.get("decision_fields"), id);
    }

    private static void validateHumanNode(JsonNode node) {
        String id = requiredText(node, "id", "node id");
        for (JsonNode field : arrayValues(node.get("fields"))) {
            ObjectNode fieldObject = requireObject(field, "human field");
            assertAllowedFields(
                    fieldObject,
                    "human field '" + textOr(fieldObject, "name", "<unknown>") + "'",
                    Set.of("name", "required", "kind", "allowed_values", "description", "example"));
            requiredTrimmedText(fieldObject, "name", "human field name");
            optionalText(fieldObject.get("description"), "human field description");
            optionalText(fieldObject.get("example"), "human field example");
            String kind = textOr(fieldObject, "kind", "string");
            if (!Set.of("string", "enum", "csv").contains(kind)) {
                throw new ForgeException("node '" + id + "' human field kind must be string, enum, or csv");
            }
            List<String> allowedValues = stringArray(fieldObject.get("allowed_values"), "human field allowed_values");
            if ("enum".equals(kind) && allowedValues.isEmpty()) {
                throw new ForgeException("node '" + id + "' human enum field must declare allowed_values");
            }
            if (!"enum".equals(kind) && !allowedValues.isEmpty()) {
                throw new ForgeException("node '" + id + "' human field may only use allowed_values with kind enum");
            }
        }
    }

    private static void validateDecisionFields(@Nullable JsonNode fields, String nodeId) {
        Set<String> names = new TreeSet<>();
        for (JsonNode field : arrayValues(fields)) {
            ObjectNode fieldObject = requireObject(field, "decision field");
            assertAllowedFields(fieldObject, "decision field", Set.of("name", "allowed_values"));
            String name = requiredTrimmedText(fieldObject, "name", "decision field name");
            if (!names.add(name)) {
                throw new ForgeException("node '" + nodeId + "' declares duplicate decision field '" + name + "'");
            }
            List<String> allowed = stringArray(fieldObject.get("allowed_values"), "decision field allowed_values");
            if (allowed.isEmpty()) {
                throw new ForgeException("node '" + nodeId + "' decision field '" + name
                        + "' must declare allowed_values");
            }
            requireUniqueTrimmed(allowed, "node '" + nodeId + "' decision field '" + name + "' allowed value");
        }
    }

    private static void validateSubrunNode(WorkflowSpec spec, JsonNode node) {
        String id = requiredText(node, "id", "node id");
        requiredWorkflowRef(node);
        String requestArtifact = requiredTrimmedText(node, "request_artifact", "subrun request_artifact");
        String summaryArtifact = requiredTrimmedText(node, "summary_artifact", "subrun summary_artifact");
        if (artifactDefinition(spec, requestArtifact) == null) {
            throw new ForgeException("subrun node '" + id
                    + "' references unknown request_artifact '" + requestArtifact + "'");
        }
        if (!stringArray(node.get("inputs"), "subrun inputs").contains(requestArtifact)) {
            throw new ForgeException("subrun node '" + id
                    + "' must declare request_artifact '" + requestArtifact + "' in inputs");
        }
        if (artifactDefinition(spec, summaryArtifact) == null) {
            throw new ForgeException("subrun node '" + id
                    + "' references unknown summary_artifact '" + summaryArtifact + "'");
        }
        if (!stringArray(node.get("outputs"), "subrun outputs").contains(summaryArtifact)) {
            throw new ForgeException("subrun node '" + id
                    + "' must declare summary_artifact '" + summaryArtifact + "' in outputs");
        }
        Set<String> claimedParentArtifacts = new TreeSet<>();
        claimedParentArtifacts.add(summaryArtifact);
        for (Map.Entry<String, String> entry : stringMap(node.get("import_artifacts"), "subrun import_artifacts").entrySet()) {
            String childArtifact = requireTrimmedValue(entry.getKey(), "subrun import child artifact");
            String parentArtifact = requireTrimmedValue(entry.getValue(), "subrun import parent artifact");
            if (artifactDefinition(spec, parentArtifact) == null) {
                throw new ForgeException("subrun node '" + id
                        + "' imports child artifact '" + childArtifact
                        + "' into unknown parent artifact '" + parentArtifact + "'");
            }
            if (!stringArray(node.get("outputs"), "subrun outputs").contains(parentArtifact)) {
                throw new ForgeException("subrun node '" + id
                        + "' must declare imported parent artifact '" + parentArtifact + "' in outputs");
            }
            if (!claimedParentArtifacts.add(parentArtifact)) {
                throw new ForgeException("subrun node '" + id
                        + "' imports into parent artifact '" + parentArtifact
                        + "' more than once or collides with summary_artifact");
            }
        }
    }

    private static void validateNotifications(WorkflowSpec spec) {
        JsonNode notifications = spec.notifications();
        if (notifications == null || notifications.isNull()) {
            return;
        }
        ObjectNode object = requireObject(notifications, "notifications");
        assertAllowedFields(
                object,
                "notifications",
                Set.of("default_hook", "complete_hook", "escalate_hook", "human_review_hook"));
        Iterator<Map.Entry<String, JsonNode>> hooks = object.fields();
        while (hooks.hasNext()) {
            Map.Entry<String, JsonNode> hook = hooks.next();
            ObjectNode hookObject = requireObject(hook.getValue(), "notifications." + hook.getKey());
            assertAllowedFields(hookObject, "notifications." + hook.getKey(), Set.of("path", "cwd", "env"));
            requiredText(hookObject, "path", "notifications." + hook.getKey() + ".path");
            optionalText(hookObject.get("cwd"), "notifications." + hook.getKey() + ".cwd");
            validateStringMap(hookObject.get("env"), "notifications." + hook.getKey() + ".env");
        }
    }

    private static void validateWorkflowInterfaceSpec(WorkflowSpec spec) {
        JsonNode interfaceSpec = spec.interfaceSpec();
        if (interfaceSpec == null || interfaceSpec.isNull()) {
            return;
        }
        ObjectNode object = requireObject(interfaceSpec, "interface");
        assertAllowedFields(object, "interface", Set.of("inputs", "exports"));
        Set<String> produced = producedArtifacts(spec);
        for (String input : interfaceInputs(spec)) {
            if (artifactDefinition(spec, input) == null) {
                throw new ForgeException("workflow '" + spec.workflowId()
                        + "' interface input '" + input + "' references unknown artifact");
            }
            if (produced.contains(input)) {
                throw new ForgeException("workflow '" + spec.workflowId()
                        + "' interface input '" + input + "' is also produced by a workflow node");
            }
        }
        for (Map.Entry<String, JsonNode> entry : interfaceExports(spec).entrySet()) {
            String artifact = entry.getKey();
            ObjectNode export = requireObject(entry.getValue(), "interface export '" + artifact + "'");
            assertAllowedFields(export, "interface export '" + artifact + "'", Set.of("required", "terminal_only"));
            if (artifactDefinition(spec, artifact) == null) {
                throw new ForgeException("workflow '" + spec.workflowId()
                        + "' interface export '" + artifact + "' references unknown artifact");
            }
            if (!produced.contains(artifact)) {
                throw new ForgeException("workflow '" + spec.workflowId()
                        + "' interface export '" + artifact + "' is not produced by any workflow node output");
            }
            if (export.has("terminal_only") && !export.get("terminal_only").asBoolean()) {
                throw new ForgeException("workflow '" + spec.workflowId()
                        + "' interface export '" + artifact
                        + "' must set terminal_only=true in the current runtime");
            }
        }
    }

    private static void validateStructuredSpec(WorkflowSpec spec) {
        Map<String, JsonNode> loopsById = validateStructuredLoops(spec);
        for (JsonNode node : spec.nodes()) {
            String id = requiredText(node, "id", "node id");
            JsonNode route = node.get("route");
            if (route == null || route.isNull()) {
                throw new ForgeException("structured mode requires a route on every node, but '" + id + "' has none");
            }
            String kind = requiredText(node, "kind", "node kind");
            if ("agent".equals(kind) || "command".equals(kind)) {
                validateAlwaysRoute(spec, id, route);
            } else if ("judge".equals(kind)) {
                validateJudgeRoute(spec, id, node, route, loopsById);
            } else if ("human".equals(kind)) {
                validateHumanRoute(spec, id, node, route, loopsById);
            } else if ("subrun".equals(kind)) {
                validateSubrunRoute(spec, id, route, loopsById);
            }
        }
    }

    private static Map<String, JsonNode> validateStructuredLoops(WorkflowSpec spec) {
        Map<String, JsonNode> loops = new TreeMap<>();
        for (JsonNode loop : arrayValues(spec.loops())) {
            ObjectNode loopObject = requireObject(loop, "loop");
            assertAllowedFields(
                    loopObject,
                    "loop",
                    Set.of("id", "controller_node", "entry_node", "budget", "on_exhaust"));
            String id = requiredTrimmedText(loopObject, "id", "loop id");
            if (loops.put(id, loopObject) != null) {
                throw new ForgeException("duplicate loop id '" + id + "'");
            }
            validateTargetExists(spec, id, "loop controller_node", requiredText(loopObject, "controller_node", "loop controller_node"));
            validateTargetExists(spec, id, "loop entry_node", requiredText(loopObject, "entry_node", "loop entry_node"));
            ObjectNode budget = requiredObject(loopObject, "budget", "loop budget");
            String budgetKind = requiredText(budget, "kind", "loop budget kind");
            if ("literal".equals(budgetKind)) {
                assertAllowedFields(budget, "loop budget", Set.of("kind", "max_iterations"));
                long maxIterations = requiredUnsignedInt(budget.get("max_iterations"), "loop max_iterations");
                if (maxIterations == 0L) {
                    throw new ForgeException("loop '" + id + "' literal max_iterations must be greater than zero");
                }
            } else if ("artifact_field".equals(budgetKind)) {
                assertAllowedFields(budget, "loop budget", Set.of("kind", "artifact", "field"));
                String artifact = requiredTrimmedText(budget, "artifact", "loop budget artifact");
                String field = requiredTrimmedText(budget, "field", "loop budget field");
                JsonNode artifactDefinition = artifactDefinition(spec, artifact);
                if (artifactDefinition == null) {
                    throw new ForgeException("loop '" + id + "' references unknown budget artifact '" + artifact + "'");
                }
                if (!isJsonMediaType(requiredText(artifactDefinition, "media_type", "artifact media_type"))) {
                    throw new ForgeException("loop '" + id + "' budget artifact '" + artifact
                            + "' must use a JSON media type");
                }
                if (field.isBlank()) {
                    throw new ForgeException("loop '" + id + "' budget field must not be empty");
                }
            } else {
                throw new ForgeException("loop '" + id + "' budget kind must be literal or artifact_field");
            }
            ObjectNode onExhaust = requiredObject(loopObject, "on_exhaust", "loop on_exhaust");
            assertAllowedFields(onExhaust, "loop on_exhaust", Set.of("to", "reason"));
            validateTargetExists(spec, id, "loop on_exhaust target", requiredText(onExhaust, "to", "loop on_exhaust target"));
            optionalText(onExhaust.get("reason"), "loop on_exhaust reason");
        }
        return Map.copyOf(loops);
    }

    private static void validateAlwaysRoute(WorkflowSpec spec, String nodeId, JsonNode route) {
        ObjectNode routeObject = routeObject(route, nodeId);
        String mode = requiredText(routeObject, "mode", "route mode");
        if (!"always".equals(mode)) {
            throw new ForgeException("node '" + nodeId + "' (agent/command) must use route mode 'always'");
        }
        assertAllowedFields(routeObject, "route", Set.of("mode", "to"));
        validateTargetExists(spec, nodeId, "route target", requiredText(routeObject, "to", "route target"));
    }

    private static void validateJudgeRoute(
            WorkflowSpec spec,
            String nodeId,
            JsonNode node,
            JsonNode route,
            Map<String, JsonNode> loopsById) {
        List<JsonNode> fields = arrayValues(node.get("decision_fields"));
        if (fields.size() != 1) {
            throw new ForgeException("structured judge node '" + nodeId + "' must declare exactly one decision field");
        }
        String decisionField = requiredText(fields.getFirst(), "name", "decision field name");
        RouteDomain domain = closedDomain(decisionField, stringArray(fields.getFirst().get("allowed_values"), "decision allowed_values"));
        validateByFieldRoute(spec, nodeId, route, decisionField, domain, loopsById);
    }

    private static void validateHumanRoute(
            WorkflowSpec spec,
            String nodeId,
            JsonNode node,
            JsonNode route,
            Map<String, JsonNode> loopsById) {
        ObjectNode routeObject = routeObject(route, nodeId);
        String routeField = requiredText(routeObject, "field", "route field");
        JsonNode selected = null;
        for (JsonNode field : arrayValues(node.get("fields"))) {
            if (routeField.equals(text(field, "name"))) {
                selected = field;
                break;
            }
        }
        if (selected == null) {
            throw new ForgeException("structured human node '" + nodeId
                    + "' must route on a declared field, found '" + routeField + "'");
        }
        if (!selected.path("required").asBoolean(false)) {
            throw new ForgeException("structured human node '" + nodeId
                    + "' must route on a required human field, but '" + routeField + "' is optional");
        }
        String kind = textOr(selected, "kind", "string");
        RouteDomain domain = "enum".equals(kind)
                ? closedDomain(routeField, stringArray(selected.get("allowed_values"), "human allowed_values"))
                : openDomain(routeField);
        validateByFieldRoute(spec, nodeId, route, routeField, domain, loopsById);
    }

    private static void validateSubrunRoute(
            WorkflowSpec spec,
            String nodeId,
            JsonNode route,
            Map<String, JsonNode> loopsById) {
        validateByFieldRoute(
                spec,
                nodeId,
                route,
                "subrun_status",
                closedDomain("subrun_status", List.of("complete", "escalate", "failed")),
                loopsById);
    }

    private static void validateByFieldRoute(
            WorkflowSpec spec,
            String nodeId,
            JsonNode route,
            String requiredField,
            RouteDomain domain,
            Map<String, JsonNode> loopsById) {
        ObjectNode routeObject = routeObject(route, nodeId);
        String mode = requiredText(routeObject, "mode", "route mode");
        if (!"by_field".equals(mode)) {
            throw new ForgeException("structured node '" + nodeId + "' must use route mode 'by_field'");
        }
        assertAllowedFields(routeObject, "route", Set.of("mode", "field", "cases", "default"));
        String field = requiredText(routeObject, "field", "route field");
        if (!requiredField.equals(field)) {
            throw new ForgeException("structured node '" + nodeId
                    + "' must route on field '" + requiredField + "', found '" + field + "'");
        }
        Set<String> seen = new TreeSet<>();
        for (JsonNode rawCase : arrayValues(routeObject.get("cases"))) {
            ObjectNode routeCase = requireObject(rawCase, "route case");
            assertAllowedFields(routeCase, "route case", Set.of("equals", "to", "continue_loop"));
            String equals = requiredTrimmedText(routeCase, "equals", "route case value");
            if (!seen.add(equals)) {
                throw new ForgeException("node '" + nodeId + "' has duplicate route case for value '" + equals + "'");
            }
            validateRouteCaseValue(nodeId, equals, domain);
            boolean hasTo = routeCase.hasNonNull("to");
            boolean hasContinueLoop = routeCase.hasNonNull("continue_loop");
            if (hasTo == hasContinueLoop) {
                throw new ForgeException("node '" + nodeId + "' route case '" + equals
                        + "' must declare exactly one action: to or continue_loop");
            }
            if (hasTo) {
                validateTargetExists(spec, nodeId, "route case target", requiredText(routeCase, "to", "route case target"));
            } else {
                String loopId = requiredTrimmedText(routeCase, "continue_loop", "loop id");
                if (!loopsById.containsKey(loopId)) {
                    throw new ForgeException("node '" + nodeId + "' references undeclared loop '" + loopId + "'");
                }
            }
        }
        JsonNode defaultRoute = routeObject.get("default");
        if (defaultRoute != null && !defaultRoute.isNull()) {
            ObjectNode defaultObject = requireObject(defaultRoute, "route default");
            assertAllowedFields(defaultObject, "route default", Set.of("to", "reason"));
            validateTargetExists(spec, nodeId, "route default target", requiredText(defaultObject, "to", "route default target"));
            optionalText(defaultObject.get("reason"), "route default reason");
        } else if (domain.defaultRequired(seen)) {
            throw new ForgeException("node '" + nodeId
                    + "' must declare a default route for field '" + domain.fieldName()
                    + "' because the route table is not total");
        }
    }

    private static List<String> interfaceInputs(WorkflowSpec spec) {
        JsonNode interfaceSpec = spec.interfaceSpec();
        JsonNode inputs = interfaceSpec == null || interfaceSpec.isNull() ? null : interfaceSpec.get("inputs");
        return stringArray(inputs, "interface inputs");
    }

    private static Map<String, JsonNode> interfaceExports(WorkflowSpec spec) {
        JsonNode interfaceSpec = spec.interfaceSpec();
        JsonNode exports = interfaceSpec == null || interfaceSpec.isNull() ? null : interfaceSpec.get("exports");
        if (exports == null || exports.isNull()) {
            return Map.of();
        }
        ObjectNode object = requireObject(exports, "interface exports");
        Map<String, JsonNode> values = new TreeMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }

    private static Set<String> producedArtifacts(WorkflowSpec spec) {
        Set<String> produced = new TreeSet<>();
        for (JsonNode node : spec.nodes()) {
            produced.addAll(stringArray(node.get("outputs"), "node outputs"));
        }
        return Set.copyOf(produced);
    }

    private static @Nullable JsonNode artifactDefinition(WorkflowSpec spec, String name) {
        JsonNode artifacts = spec.artifacts();
        return artifacts == null || artifacts.isNull() ? null : artifacts.get(name);
    }

    private static ObjectNode routeObject(JsonNode route, String nodeId) {
        return requireObject(route, "node '" + nodeId + "' route");
    }

    private static void validateTargetExists(WorkflowSpec spec, String owner, String label, String target) {
        String trimmed = requireTrimmedValue(target, label);
        if (!isTerminalTarget(trimmed) && findNode(spec, trimmed) == null) {
            throw new ForgeException("node '" + owner + "' references unknown " + label + " '" + trimmed + "'");
        }
    }

    private static @Nullable JsonNode findNode(WorkflowSpec spec, String nodeId) {
        for (JsonNode node : spec.nodes()) {
            if (nodeId.equals(text(node, "id"))) {
                return node;
            }
        }
        return null;
    }

    private static boolean isTerminalTarget(String target) {
        return COMPLETE_TARGET.equals(target) || ESCALATE_TARGET.equals(target);
    }

    private static void validateRouteCaseValue(String nodeId, String equals, RouteDomain domain) {
        if (domain.allowedValues() != null && !domain.allowedValues().contains(equals)) {
            throw new ForgeException("node '" + nodeId + "' route case '" + equals
                    + "' is unreachable for field '" + domain.fieldName() + "'");
        }
    }

    private static RouteDomain openDomain(String fieldName) {
        return new RouteDomain(fieldName, null);
    }

    private static RouteDomain closedDomain(String fieldName, List<String> allowedValues) {
        return new RouteDomain(fieldName, Set.copyOf(allowedValues));
    }

    private static void validateNodeId(String nodeId) {
        if (nodeId.isEmpty()) {
            throw new ForgeException("node id is required");
        }
        for (int index = 0; index < nodeId.length(); index++) {
            char ch = nodeId.charAt(index);
            boolean valid = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-'
                    || ch == '_';
            if (!valid) {
                throw new ForgeException("node id '" + nodeId
                        + "' must be a single filesystem-safe identifier using only ASCII letters, digits, '-' or '_'");
            }
        }
    }

    private static String requiredWorkflowRef(JsonNode node) {
        JsonNode value = node.get("workflow_ref");
        if (value == null || value.isNull()) {
            throw new ForgeException("missing required workflow field: workflow_ref");
        }
        if (value.isTextual()) {
            return requireTrimmedValue(value.asText(), "subrun workflow_ref");
        }
        ObjectNode object = requireObject(value, "workflow_ref");
        assertAllowedFields(object, "workflow_ref", Set.of("path", "template_id"));
        if (object.hasNonNull("path")) {
            return requiredTrimmedText(object, "path", "subrun workflow path");
        }
        if (object.hasNonNull("template_id")) {
            return requiredTrimmedText(object, "template_id", "subrun template_id");
        }
        throw new ForgeException("subrun workflow_ref must contain path or template_id");
    }

    private static boolean commandContainsCodexExec(List<String> command) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if ("codex".equals(codexBinaryName(command.get(index))) && "exec".equals(command.get(index + 1))) {
                return true;
            }
        }
        return false;
    }

    private static String codexBinaryName(String argument) {
        Path fileName = Path.of(argument).getFileName();
        String binary = fileName == null ? argument : fileName.toString();
        return binary.endsWith(".exe") ? binary.substring(0, binary.length() - 4) : binary;
    }

    private static boolean isJsonMediaType(String mediaType) {
        String normalized = mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        return "application/json".equals(normalized) || normalized.endsWith("+json");
    }

    private static ObjectNode requireObject(JsonNode node, String label) {
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new ForgeException(label + " must be a JSON object");
    }

    private static ObjectNode requiredObject(JsonNode node, String field, String label) {
        return requireObject(requiredNode(node, field, label), label);
    }

    private static JsonNode requiredNode(JsonNode node, String field, String label) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new ForgeException(label + " is required");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, String label) {
        JsonNode value = requiredNode(node, field, label);
        if (!value.isTextual()) {
            throw new ForgeException(label + " must be a string");
        }
        if (value.asText().isBlank()) {
            throw new ForgeException(label + " must not be empty");
        }
        return value.asText();
    }

    private static String requiredTrimmedText(JsonNode node, String field, String label) {
        return requireTrimmedValue(requiredText(node, field, label), label);
    }

    private static String requireTrimmedValue(String value, String label) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ForgeException(label + " must not be empty");
        }
        if (!trimmed.equals(value)) {
            throw new ForgeException(label + " '" + value + "' must not contain surrounding whitespace");
        }
        return trimmed;
    }

    private static void optionalText(@Nullable JsonNode value, String label) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw new ForgeException(label + " must be a string");
        }
    }

    private static List<JsonNode> arrayValues(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ForgeException("expected JSON array");
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return List.copyOf(values);
    }

    private static List<String> stringArray(@Nullable JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ForgeException(label + " must be a JSON array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new ForgeException(label + " must contain only strings");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(@Nullable JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        ObjectNode object = requireObject(node, label);
        Map<String, String> values = new TreeMap<>();
        object.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new ForgeException(label + " values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(values);
    }

    private static void validateStringMap(@Nullable JsonNode node, String label) {
        stringMap(node, label);
    }

    private static void validateRetryPolicy(@Nullable JsonNode node, String nodeId) {
        if (node == null || node.isNull()) {
            return;
        }
        ObjectNode retry = requireObject(node, "node '" + nodeId + "' retry_policy");
        assertAllowedFields(retry, "node '" + nodeId + "' retry_policy", Set.of("max_attempts"));
        if (retry.has("max_attempts")) {
            requiredUnsignedInt(retry.get("max_attempts"), "node '" + nodeId + "' retry_policy.max_attempts");
        }
    }

    private static void optionalNonNegativeLong(@Nullable JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0L) {
            throw new ForgeException(label + " must be a non-negative integer");
        }
    }

    private static long requiredUnsignedInt(@Nullable JsonNode node, String label) {
        if (node == null || node.isNull()) {
            throw new ForgeException(label + " must be an unsigned 32-bit integer");
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new ForgeException(label + " must be an unsigned 32-bit integer");
        }
        long value = node.asLong();
        if (value < 0L || value > UNSIGNED_INT_MAX) {
            throw new ForgeException(label + " must be an unsigned 32-bit integer");
        }
        return value;
    }

    private static void requireUniqueTrimmed(List<String> values, String label) {
        Set<String> seen = new TreeSet<>();
        for (String value : values) {
            String trimmed = requireTrimmedValue(value, label);
            if (!seen.add(trimmed)) {
                throw new ForgeException(label + " '" + trimmed + "' is duplicated");
            }
        }
    }

    private static void assertAllowedFields(ObjectNode object, String label, Set<String> allowed) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new ForgeException(label + " contains unknown field '" + field + "'");
            }
        }
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    private record RouteDomain(String fieldName, @Nullable Set<String> allowedValues) {
        private boolean defaultRequired(Set<String> seenValues) {
            if (allowedValues == null) {
                return true;
            }
            return !seenValues.containsAll(allowedValues) || seenValues.size() != allowedValues.size();
        }
    }
}

final class WorkflowSpecCanonicalJson {
    private WorkflowSpecCanonicalJson() {
    }

    static String sha256(WorkflowSpec spec) {
        String serialized = Json.toJson(canonicalSpec(spec));
        return Hashing.sha256Hex(serialized.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode canonicalSpec(WorkflowSpec spec) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("version", spec.version());
        root.put("workflow_id", spec.workflowId());
        root.put("entry_node", spec.entryNode());
        setOptionalText(root, "routing_mode", spec.routingMode());
        ToolRequirements requirements = spec.effectiveToolRequirements();
        if (!requirements.isEmpty()) {
            root.set("tool_requirements", requirements.toJson());
        }
        root.set("agents", sortedObject(spec.agents(), WorkflowSpecCanonicalJson::canonicalAgent));
        ArrayNode nodes = Json.mapper().createArrayNode();
        for (JsonNode node : spec.nodes()) {
            nodes.add(canonicalNode(node));
        }
        root.set("nodes", nodes);
        root.set("loops", canonicalLoops(spec.loops()));
        root.set("interface", canonicalInterface(spec.interfaceSpec()));
        root.set("artifacts", sortedObject(spec.artifacts(), WorkflowSpecCanonicalJson::canonicalArtifact));
        root.set("notifications", canonicalNotifications(spec.notifications()));
        return root;
    }

    private static ObjectNode canonicalAgent(JsonNode agent) {
        ObjectNode out = Json.mapper().createObjectNode();
        setOptionalText(out, "display_name", text(agent, "display_name"));
        setOptionalText(out, "runner", text(agent, "runner"));
        out.set("command", arrayOrEmpty(agent.get("command")));
        setOptionalText(out, "cwd", text(agent, "cwd"));
        out.set("env", objectOrEmpty(agent.get("env")));
        out.put("prompt_delivery", textOr(agent, "prompt_delivery", "env_path"));
        return out;
    }

    private static ObjectNode canonicalNode(JsonNode node) {
        String kind = requiredText(node, "kind", "node kind");
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("kind", kind);
        out.put("id", requiredText(node, "id", "node id"));
        out.set("inputs", arrayOrEmpty(node.get("inputs")));
        out.set("outputs", arrayOrEmpty(node.get("outputs")));
        setOptionalLong(out, "timeout_ms", node.get("timeout_ms"));
        ObjectNode retry = Json.mapper().createObjectNode();
        JsonNode retryPolicy = node.get("retry_policy");
        JsonNode maxAttempts = retryPolicy == null || retryPolicy.isNull() ? null : retryPolicy.get("max_attempts");
        retry.put("max_attempts", maxAttempts == null ? 0L : maxAttempts.asLong());
        out.set("retry_policy", retry);
        setOptionalText(out, "message", text(node, "message"));
        switch (kind) {
            case "agent" -> {
                out.put("agent_id", requiredText(node, "agent_id", "agent_id"));
                out.put("prompt_template", requiredText(node, "prompt_template", "prompt_template"));
                setOptionalText(out, "expected_output", text(node, "expected_output"));
                setOptionalText(out, "status_key", text(node, "status_key"));
                setOptionalRoute(out, node.get("route"));
            }
            case "command" -> {
                out.set("command", arrayOrEmpty(node.get("command")));
                setOptionalText(out, "cwd", text(node, "cwd"));
                out.set("env", objectOrEmpty(node.get("env")));
                out.put("capture", textOr(node, "capture", "combined"));
                setOptionalRoute(out, node.get("route"));
            }
            case "judge" -> {
                out.put("agent_id", requiredText(node, "agent_id", "agent_id"));
                out.put("prompt_template", requiredText(node, "prompt_template", "prompt_template"));
                out.put("decision_schema", requiredText(node, "decision_schema", "decision_schema"));
                out.set("decision_fields", canonicalDecisionFields(node.get("decision_fields")));
                setOptionalRoute(out, node.get("route"));
            }
            case "human" -> {
                out.set("fields", canonicalHumanFields(node.get("fields")));
                setOptionalText(out, "instructions", text(node, "instructions"));
                setOptionalRoute(out, node.get("route"));
            }
            case "subrun" -> {
                out.set("workflow_ref", canonicalWorkflowRef(node.get("workflow_ref")));
                out.put("request_artifact", requiredText(node, "request_artifact", "request_artifact"));
                out.put("summary_artifact", requiredText(node, "summary_artifact", "summary_artifact"));
                out.set("import_artifacts", objectOrEmpty(node.get("import_artifacts")));
                setOptionalRoute(out, node.get("route"));
            }
            default -> throw new ForgeException("unsupported workflow node kind: " + kind);
        }
        return out;
    }

    private static ArrayNode canonicalLoops(JsonNode loops) {
        ArrayNode out = Json.mapper().createArrayNode();
        for (JsonNode loop : arrayValues(loops)) {
            ObjectNode value = Json.mapper().createObjectNode();
            value.put("id", requiredText(loop, "id", "loop id"));
            value.put("controller_node", requiredText(loop, "controller_node", "loop controller_node"));
            value.put("entry_node", requiredText(loop, "entry_node", "loop entry_node"));
            value.set("budget", canonicalLoopBudget(loop.get("budget")));
            ObjectNode exhaust = Json.mapper().createObjectNode();
            JsonNode onExhaust = requiredObject(loop, "on_exhaust", "loop on_exhaust");
            exhaust.put("to", requiredText(onExhaust, "to", "loop on_exhaust target"));
            setOptionalText(exhaust, "reason", text(onExhaust, "reason"));
            value.set("on_exhaust", exhaust);
            out.add(value);
        }
        return out;
    }

    private static ObjectNode canonicalLoopBudget(JsonNode budget) {
        ObjectNode out = Json.mapper().createObjectNode();
        String kind = requiredText(budget, "kind", "loop budget kind");
        out.put("kind", kind);
        if ("literal".equals(kind)) {
            out.put("max_iterations", budget.path("max_iterations").asLong());
        } else {
            out.put("artifact", requiredText(budget, "artifact", "loop budget artifact"));
            out.put("field", requiredText(budget, "field", "loop budget field"));
        }
        return out;
    }

    private static ObjectNode canonicalInterface(@Nullable JsonNode interfaceSpec) {
        ObjectNode out = Json.mapper().createObjectNode();
        if (interfaceSpec == null || interfaceSpec.isNull()) {
            out.set("inputs", Json.mapper().createArrayNode());
            out.set("exports", Json.mapper().createObjectNode());
            return out;
        }
        out.set("inputs", arrayOrEmpty(interfaceSpec.get("inputs")));
        out.set("exports", sortedObject(interfaceSpec.get("exports"), WorkflowSpecCanonicalJson::canonicalInterfaceExport));
        return out;
    }

    private static ObjectNode canonicalInterfaceExport(JsonNode export) {
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("required", export.path("required").asBoolean(false));
        out.put("terminal_only", !export.has("terminal_only") || export.path("terminal_only").asBoolean());
        return out;
    }

    private static ObjectNode canonicalArtifact(JsonNode artifact) {
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("path", requiredText(artifact, "path", "artifact path"));
        out.put("media_type", requiredText(artifact, "media_type", "artifact media_type"));
        setOptionalText(out, "schema", text(artifact, "schema"));
        return out;
    }

    private static ObjectNode canonicalNotifications(@Nullable JsonNode notifications) {
        ObjectNode out = Json.mapper().createObjectNode();
        setOptionalHook(out, notifications, "default_hook");
        setOptionalHook(out, notifications, "complete_hook");
        setOptionalHook(out, notifications, "escalate_hook");
        setOptionalHook(out, notifications, "human_review_hook");
        return out;
    }

    private static ObjectNode canonicalHook(JsonNode hook) {
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("path", requiredText(hook, "path", "notification hook path"));
        setOptionalText(out, "cwd", text(hook, "cwd"));
        out.set("env", objectOrEmpty(hook.get("env")));
        return out;
    }

    private static ArrayNode canonicalHumanFields(@Nullable JsonNode fields) {
        ArrayNode out = Json.mapper().createArrayNode();
        for (JsonNode field : arrayValues(fields)) {
            ObjectNode value = Json.mapper().createObjectNode();
            value.put("name", requiredText(field, "name", "human field name"));
            value.put("required", field.path("required").asBoolean(false));
            value.put("kind", textOr(field, "kind", "string"));
            value.set("allowed_values", arrayOrEmpty(field.get("allowed_values")));
            setOptionalText(value, "description", text(field, "description"));
            setOptionalText(value, "example", text(field, "example"));
            out.add(value);
        }
        return out;
    }

    private static ArrayNode canonicalDecisionFields(@Nullable JsonNode fields) {
        ArrayNode out = Json.mapper().createArrayNode();
        for (JsonNode field : arrayValues(fields)) {
            ObjectNode value = Json.mapper().createObjectNode();
            value.put("name", requiredText(field, "name", "decision field name"));
            value.set("allowed_values", arrayOrEmpty(field.get("allowed_values")));
            out.add(value);
        }
        return out;
    }

    private static JsonNode canonicalWorkflowRef(JsonNode workflowRef) {
        if (workflowRef.isTextual()) {
            return Json.mapper().createObjectNode().put("path", workflowRef.asText());
        }
        ObjectNode out = Json.mapper().createObjectNode();
        if (workflowRef.hasNonNull("template_id")) {
            out.put("template_id", workflowRef.get("template_id").asText());
        } else {
            out.put("path", requiredText(workflowRef, "path", "workflow_ref path"));
        }
        return out;
    }

    private static ObjectNode canonicalRoute(JsonNode route) {
        ObjectNode out = Json.mapper().createObjectNode();
        String mode = requiredText(route, "mode", "route mode");
        out.put("mode", mode);
        if ("always".equals(mode)) {
            out.put("to", requiredText(route, "to", "route target"));
            return out;
        }
        out.put("field", requiredText(route, "field", "route field"));
        ArrayNode cases = Json.mapper().createArrayNode();
        for (JsonNode routeCase : arrayValues(route.get("cases"))) {
            ObjectNode value = Json.mapper().createObjectNode();
            value.put("equals", requiredText(routeCase, "equals", "route case value"));
            setOptionalText(value, "to", text(routeCase, "to"));
            setOptionalText(value, "continue_loop", text(routeCase, "continue_loop"));
            cases.add(value);
        }
        out.set("cases", cases);
        JsonNode defaultRoute = route.get("default");
        if (defaultRoute == null || defaultRoute.isNull()) {
            out.set("default", NullNode.getInstance());
        } else {
            ObjectNode defaultObject = Json.mapper().createObjectNode();
            defaultObject.put("to", requiredText(defaultRoute, "to", "route default target"));
            setOptionalText(defaultObject, "reason", text(defaultRoute, "reason"));
            out.set("default", defaultObject);
        }
        return out;
    }

    private static ObjectNode sortedObject(
            @Nullable JsonNode node,
            java.util.function.Function<JsonNode, JsonNode> mapper) {
        ObjectNode out = Json.mapper().createObjectNode();
        if (node == null || node.isNull()) {
            return out;
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        for (String name : names) {
            out.set(name, mapper.apply(node.get(name)));
        }
        return out;
    }

    private static ObjectNode objectOrEmpty(@Nullable JsonNode node) {
        return node == null || node.isNull()
                ? Json.mapper().createObjectNode()
                : node.deepCopy();
    }

    private static ArrayNode arrayOrEmpty(@Nullable JsonNode node) {
        return node == null || node.isNull()
                ? Json.mapper().createArrayNode()
                : node.deepCopy();
    }

    private static List<JsonNode> arrayValues(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ForgeException("expected JSON array");
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return List.copyOf(values);
    }

    private static void setOptionalRoute(ObjectNode out, @Nullable JsonNode route) {
        if (route == null || route.isNull()) {
            out.set("route", NullNode.getInstance());
        } else {
            out.set("route", canonicalRoute(route));
        }
    }

    private static void setOptionalHook(ObjectNode out, @Nullable JsonNode notifications, String field) {
        JsonNode hook = notifications == null || notifications.isNull() ? null : notifications.get(field);
        if (hook == null || hook.isNull()) {
            out.set(field, NullNode.getInstance());
        } else {
            out.set(field, canonicalHook(hook));
        }
    }

    private static void setOptionalText(ObjectNode object, String field, @Nullable String value) {
        if (value == null) {
            object.set(field, NullNode.getInstance());
        } else {
            object.put(field, value);
        }
    }

    private static void setOptionalLong(ObjectNode object, String field, @Nullable JsonNode value) {
        if (value == null || value.isNull()) {
            object.set(field, NullNode.getInstance());
        } else {
            object.put(field, nonNegativeLong(value, field));
        }
    }

    private static long nonNegativeLong(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0L) {
            throw new ForgeException(field + " must be a non-negative integer");
        }
        return value.asLong();
    }

    private static String requiredText(JsonNode node, String field, String label) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new ForgeException(label + " is required");
        }
        return value;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ObjectNode requiredObject(JsonNode node, String field, String label) {
        JsonNode value = node == null ? null : node.get(field);
        if (value instanceof ObjectNode object) {
            return object;
        }
        throw new ForgeException(label + " is required");
    }
}
