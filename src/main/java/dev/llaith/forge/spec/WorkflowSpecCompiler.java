package dev.llaith.forge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Hashing;
import dev.llaith.forge.util.Json;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class WorkflowSpecCompiler {
    private static final String COMPLETE_TARGET = "__complete__";
    private static final String ESCALATE_TARGET = "__escalate__";

    private WorkflowSpecCompiler() {
    }

    public static CompiledWorkflow compile(Path sourceSpec) {
        return compile(WorkflowSpecs.load(sourceSpec));
    }

    public static CompiledWorkflow compile(WorkflowSpec spec) {
        WorkflowSpecs.validate(spec);
        ObjectNode workflowIr = lowerToIr(spec);
        String sourceSpecSha256 = requiredText(workflowIr, "source_spec_sha256", "source_spec_sha256");
        return new CompiledWorkflow(spec, workflowIr, deriveInterface(spec), sourceSpecSha256);
    }

    public static CompiledWorkflow compileFrozenRunPackage(
            Path sourceSpec,
            Path logicalRunDir,
            Path actualRunDir,
            TemplateResolver templateResolver) {
        WorkflowSpec spec = WorkflowSpecs.load(sourceSpec);
        Path sourceRoot = sourceSpec.toAbsolutePath().normalize().getParent();
        if (sourceRoot == null) {
            sourceRoot = Path.of("").toAbsolutePath();
        }
        WorkflowSpec frozen = freezeSpecForRun(
                spec,
                sourceRoot,
                logicalRunDir,
                actualRunDir,
                templateResolver);
        return compile(frozen);
    }

    public static Set<String> requiredSubrunImports(
            WorkflowSpec parentSpec,
            String nodeId,
            JsonNode childInterface,
            String requestArtifact,
            Map<String, String> importArtifacts) {
        JsonNode parentRequest = artifactDefinition(parentSpec, requestArtifact);
        if (parentRequest == null) {
            throw new ForgeException("subrun node '" + nodeId
                    + "' references unknown parent request_artifact '" + requestArtifact + "'");
        }
        JsonNode childInputs = requiredObject(childInterface, "inputs", "child interface inputs");
        JsonNode childRequest = childInputs.get(requestArtifact);
        if (childRequest == null) {
            throw new ForgeException("subrun node '" + nodeId
                    + "' request_artifact '" + requestArtifact
                    + "' is not declared by child workflow '"
                    + requiredText(childInterface, "workflow_id", "child workflow_id")
                    + "' interface");
        }
        String parentMediaType = requiredText(parentRequest, "media_type", "parent request media_type");
        String childMediaType = requiredText(childRequest, "media_type", "child request media_type");
        if (!mediaTypesCompatible(parentMediaType, childMediaType)) {
            throw new ForgeException("subrun node '" + nodeId
                    + "' request_artifact '" + requestArtifact
                    + "' media type '" + parentMediaType
                    + "' does not match child workflow interface input media type '" + childMediaType + "'");
        }

        Set<String> unboundInputs = new TreeSet<>();
        childInputs.fieldNames().forEachRemaining(unboundInputs::add);
        unboundInputs.remove(requestArtifact);
        if (!unboundInputs.isEmpty()) {
            throw new ForgeException("subrun node '" + nodeId
                    + "' cannot initialize child workflow because required child interface inputs remain unbound: "
                    + String.join(", ", unboundInputs));
        }

        JsonNode childExports = requiredObject(childInterface, "exports", "child interface exports");
        Set<String> requiredImportsOnComplete = new TreeSet<>();
        for (Map.Entry<String, String> entry : importArtifacts.entrySet()) {
            String childArtifact = entry.getKey();
            String parentArtifact = entry.getValue();
            JsonNode parentDefinition = artifactDefinition(parentSpec, parentArtifact);
            if (parentDefinition == null) {
                throw new ForgeException("subrun node '" + nodeId
                        + "' imports child artifact '" + childArtifact
                        + "' into unknown parent artifact '" + parentArtifact + "'");
            }
            JsonNode childExport = childExports.get(childArtifact);
            if (childExport == null) {
                throw new ForgeException("subrun node '" + nodeId
                        + "' imports child artifact '" + childArtifact
                        + "' that is not declared by child workflow interface");
            }
            String parentExportMediaType = requiredText(parentDefinition, "media_type", "parent export media_type");
            String childExportMediaType = requiredText(childExport, "media_type", "child export media_type");
            if (!mediaTypesCompatible(parentExportMediaType, childExportMediaType)) {
                throw new ForgeException("subrun node '" + nodeId
                        + "' import target artifact '" + parentArtifact
                        + "' media type '" + parentExportMediaType
                        + "' does not match child interface export '" + childArtifact
                        + "' media type '" + childExportMediaType + "'");
            }
            if (childExport.path("required").asBoolean(false)) {
                requiredImportsOnComplete.add(childArtifact);
            }
        }
        return Set.copyOf(requiredImportsOnComplete);
    }

    private static WorkflowSpec freezeSpecForRun(
            WorkflowSpec spec,
            Path sourceRoot,
            Path logicalRunDir,
            Path actualRunDir,
            TemplateResolver templateResolver) {
        WorkflowSpec withFrozenSubruns = freezeSubrunWorkflows(
                spec,
                sourceRoot,
                logicalRunDir,
                actualRunDir,
                templateResolver);
        return freezeRunLocalHookAndCwdPaths(withFrozenSubruns, sourceRoot);
    }

    private static WorkflowSpec freezeSubrunWorkflows(
            WorkflowSpec spec,
            Path sourceRoot,
            Path logicalRoot,
            Path actualRoot,
            TemplateResolver templateResolver) {
        List<JsonNode> nodes = new ArrayList<>();
        boolean changed = false;
        for (JsonNode rawNode : spec.nodes()) {
            JsonNode copied = rawNode.deepCopy();
            if (copied instanceof ObjectNode node && "subrun".equals(text(node, "kind"))) {
                ResolvedWorkflow resolved = resolveWorkflowRef(sourceRoot, node.get("workflow_ref"), templateResolver);
                Path snapshotDir = subrunFrozenWorkflowsDir(actualRoot).resolve(snapshotDirName(resolved.snapshotKey()));
                Path snapshotName = snapshotDir.getFileName();
                if (snapshotName == null) {
                    throw new ForgeException("failed to derive frozen subrun snapshot name for " + snapshotDir);
                }
                Path logicalSnapshotDir = subrunFrozenWorkflowsDir(logicalRoot).resolve(snapshotName.toString());
                WorkflowSpec childSpec = freezeSubrunWorkflows(
                        resolved.spec(),
                        resolved.sourceRoot(),
                        logicalSnapshotDir,
                        snapshotDir,
                        templateResolver);
                childSpec = freezeRunLocalHookAndCwdPaths(childSpec, resolved.sourceRoot());
                requiredSubrunImports(
                        spec,
                        text(node, "id"),
                        deriveInterface(childSpec),
                        text(node, "request_artifact"),
                        stringMap(node.get("import_artifacts"), "subrun import_artifacts"));
                writeFrozenWorkflowSnapshot(snapshotDir, childSpec, resolved.sourcePath());
                node.set("workflow_ref", Json.mapper().createObjectNode()
                        .put("path", relativePath(actualRoot, snapshotDir.resolve("spec.json"))));
                changed = true;
            }
            nodes.add(copied);
        }
        if (!changed) {
            return spec;
        }
        return new WorkflowSpec(
                spec.version(),
                spec.routingMode(),
                spec.workflowId(),
                spec.entryNode(),
                spec.toolRequirements(),
                spec.interfaceSpec(),
                spec.agents(),
                nodes,
                spec.loops(),
                spec.artifacts(),
                spec.notifications());
    }

    private static ResolvedWorkflow resolveWorkflowRef(
            Path sourceRoot,
            JsonNode workflowRef,
            TemplateResolver templateResolver) {
        if (workflowRef == null || workflowRef.isNull()) {
            throw new ForgeException("subrun node is missing workflow_ref");
        }
        if (workflowRef.isTextual() || workflowRef.hasNonNull("path")) {
            String rawPath = workflowRef.isTextual() ? workflowRef.asText() : workflowRef.get("path").asText();
            Path path = Path.of(rawPath);
            Path sourcePath = (path.isAbsolute() ? path : sourceRoot.resolve(path)).normalize();
            WorkflowSpec childSpec = WorkflowSpecs.load(sourcePath);
            Path childRoot = sourcePath.getParent();
            return new ResolvedWorkflow(
                    childSpec,
                    childRoot == null ? sourceRoot : childRoot,
                    sourcePath,
                    sourcePath.toString());
        }
        JsonNode templateId = workflowRef.get("template_id");
        if (templateId != null && templateId.isTextual() && !templateId.asText().isBlank()) {
            return templateResolver.resolve(templateId.asText());
        }
        throw new ForgeException("subrun workflow_ref must contain path or template_id");
    }

    private static void writeFrozenWorkflowSnapshot(Path snapshotDir, WorkflowSpec spec, Path sourcePath) {
        Filesystem.ensureDirectory(snapshotDir);
        CompiledWorkflow compiled = compile(spec);
        WorkflowSpecs.write(snapshotDir.resolve("spec.json"), compiled.spec());
        Json.writePretty(snapshotDir.resolve("workflow-ir.json"), compiled.workflowIr());
        Json.writePretty(snapshotDir.resolve("workflow-interface.json"), compiled.workflowInterface());
        if (Files.isRegularFile(sourcePath)) {
            Path sourceName = sourcePath.getFileName();
            if (sourceName == null) {
                return;
            }
            Path sourceDir = snapshotDir.resolve("source");
            Filesystem.ensureDirectory(sourceDir);
            copy(sourcePath, sourceDir.resolve(sourceName.toString()));
        }
    }

    private static WorkflowSpec freezeRunLocalHookAndCwdPaths(WorkflowSpec spec, Path sourceRoot) {
        JsonNode agents = freezeAgentCwdPaths(spec.agents(), sourceRoot);
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode rawNode : spec.nodes()) {
            JsonNode copied = rawNode.deepCopy();
            if (copied instanceof ObjectNode node && "command".equals(text(node, "kind"))) {
                freezeCwdPath(sourceRoot, node);
            }
            nodes.add(copied);
        }
        JsonNode notifications = freezeNotificationHookPaths(spec.notifications(), sourceRoot);
        return new WorkflowSpec(
                spec.version(),
                spec.routingMode(),
                spec.workflowId(),
                spec.entryNode(),
                spec.toolRequirements(),
                spec.interfaceSpec(),
                agents,
                nodes,
                spec.loops(),
                spec.artifacts(),
                notifications);
    }

    private static @Nullable JsonNode freezeAgentCwdPaths(@Nullable JsonNode agents, Path sourceRoot) {
        if (!(agents instanceof ObjectNode agentMap)) {
            return agents;
        }
        ObjectNode frozen = Json.mapper().createObjectNode();
        var fields = agentMap.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode copied = entry.getValue().deepCopy();
            if (copied instanceof ObjectNode agent) {
                freezeCwdPath(sourceRoot, agent);
            }
            frozen.set(entry.getKey(), copied);
        }
        return frozen;
    }

    private static @Nullable JsonNode freezeNotificationHookPaths(
            @Nullable JsonNode notifications,
            Path sourceRoot) {
        if (!(notifications instanceof ObjectNode notificationMap)) {
            return notifications;
        }
        ObjectNode frozen = notificationMap.deepCopy();
        freezeNotificationHook(sourceRoot, frozen, "default_hook");
        freezeNotificationHook(sourceRoot, frozen, "complete_hook");
        freezeNotificationHook(sourceRoot, frozen, "escalate_hook");
        freezeNotificationHook(sourceRoot, frozen, "human_review_hook");
        return frozen;
    }

    private static void freezeNotificationHook(Path sourceRoot, ObjectNode notifications, String field) {
        JsonNode hook = notifications.get(field);
        if (hook instanceof ObjectNode hookObject) {
            freezePathField(sourceRoot, hookObject, "path");
            freezeCwdPath(sourceRoot, hookObject);
        }
    }

    private static void freezeCwdPath(Path sourceRoot, ObjectNode object) {
        JsonNode cwd = object.get("cwd");
        if (cwd != null && cwd.isTextual() && !"$request.repo_root".equals(cwd.asText())) {
            freezePathField(sourceRoot, object, "cwd");
        }
    }

    private static void freezePathField(Path sourceRoot, ObjectNode object, String field) {
        JsonNode raw = object.get(field);
        if (raw == null || !raw.isTextual()) {
            return;
        }
        object.put(field, freezeSourcePath(sourceRoot, raw.asText()));
    }

    private static String freezeSourcePath(Path sourceRoot, String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : sourceRoot.resolve(path)).normalize().toString();
    }

    private static ObjectNode lowerToIr(WorkflowSpec spec) {
        ObjectMapper mapper = Json.mapper();
        ObjectNode ir = mapper.createObjectNode();
        ir.put("spec_version", spec.version());
        ir.put("workflow_id", spec.workflowId());
        ir.put("entry_node", spec.entryNode());
        ir.put("source_spec_sha256", WorkflowSpecCanonicalJson.sha256(spec));
        ir.put("routing_mode", spec.effectiveRoutingMode());

        ObjectNode nodes = mapper.createObjectNode();
        for (JsonNode node : spec.nodes()) {
            ObjectNode irNode = mapper.createObjectNode();
            String id = requiredText(node, "id", "node id");
            String kind = requiredText(node, "kind", "node kind");
            irNode.put("id", id);
            irNode.put("kind", kind);
            irNode.set("route", lowerRoute(node));
            nodes.set(id, irNode);
        }
        ir.set("nodes", nodes);

        ArrayNode loops = mapper.createArrayNode();
        for (JsonNode loop : arrayValues(spec.loops())) {
            ObjectNode lowered = mapper.createObjectNode();
            lowered.put("id", requiredText(loop, "id", "loop id"));
            lowered.put("controller_node", requiredText(loop, "controller_node", "loop controller_node"));
            lowered.put("entry_node", requiredText(loop, "entry_node", "loop entry_node"));
            lowered.set("budget", lowerLoopBudget(loop.get("budget")));
            ObjectNode exhaust = mapper.createObjectNode();
            JsonNode onExhaust = requiredObject(loop, "on_exhaust", "loop on_exhaust");
            exhaust.set("to", targetNode(requiredText(onExhaust, "to", "loop on_exhaust target")));
            putOptionalText(exhaust, "reason", onExhaust.get("reason"));
            lowered.set("on_exhaust", exhaust);
            loops.add(lowered);
        }
        ir.set("loops", loops);
        return ir;
    }

    private static ObjectNode deriveInterface(WorkflowSpec spec) {
        ObjectMapper mapper = Json.mapper();
        ObjectNode workflowInterface = mapper.createObjectNode();
        workflowInterface.put("workflow_id", spec.workflowId());

        ObjectNode inputs = mapper.createObjectNode();
        for (String input : interfaceInputs(spec)) {
            ObjectNode inputNode = mapper.createObjectNode();
            inputNode.put("media_type", artifactMediaType(spec, input));
            inputs.set(input, inputNode);
        }
        workflowInterface.set("inputs", inputs);

        ObjectNode exports = mapper.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : interfaceExports(spec).entrySet()) {
            String artifact = entry.getKey();
            JsonNode export = entry.getValue();
            ObjectNode exportNode = mapper.createObjectNode();
            exportNode.put("media_type", artifactMediaType(spec, artifact));
            exportNode.put("required", export.path("required").asBoolean(false));
            exportNode.put("terminal_only", !export.has("terminal_only")
                    || export.path("terminal_only").asBoolean());
            exports.set(artifact, exportNode);
        }
        workflowInterface.set("exports", exports);
        return workflowInterface;
    }

    private static ObjectNode lowerRoute(JsonNode node) {
        ObjectMapper mapper = Json.mapper();
        JsonNode route = node.get("route");
        String mode = requiredText(route, "mode", "route mode");
        if ("always".equals(mode)) {
            return mapper.createObjectNode()
                    .put("mode", "always")
                    .set("to", targetNode(requiredText(route, "to", "route target")));
        }
        ObjectNode lowered = mapper.createObjectNode();
        lowered.put("mode", "by_field");
        String field = requiredText(route, "field", "route field");
        lowered.put("field", field);
        lowered.set("domain", lowerRouteDomain(node, field));
        ArrayNode cases = mapper.createArrayNode();
        for (JsonNode rawCase : arrayValues(route.get("cases"))) {
            ObjectNode loweredCase = mapper.createObjectNode();
            loweredCase.put("equals", requiredText(rawCase, "equals", "route case value"));
            loweredCase.set("action", lowerRouteCaseAction(rawCase));
            cases.add(loweredCase);
        }
        lowered.set("cases", cases);
        JsonNode defaultRoute = route.get("default");
        if (defaultRoute != null && !defaultRoute.isNull()) {
            ObjectNode defaultNode = mapper.createObjectNode();
            ObjectNode action = mapper.createObjectNode();
            action.put("kind", "goto");
            action.set("to", targetNode(requiredText(defaultRoute, "to", "route default target")));
            defaultNode.set("action", action);
            putOptionalText(defaultNode, "reason", defaultRoute.get("reason"));
            lowered.set("default", defaultNode);
        }
        return lowered;
    }

    private static ObjectNode lowerRouteCaseAction(JsonNode routeCase) {
        ObjectNode action = Json.mapper().createObjectNode();
        if (routeCase.hasNonNull("continue_loop")) {
            action.put("kind", "continue_loop");
            action.put("loop_id", routeCase.get("continue_loop").asText());
        } else {
            action.put("kind", "goto");
            action.set("to", targetNode(requiredText(routeCase, "to", "route case target")));
        }
        return action;
    }

    private static ObjectNode lowerRouteDomain(JsonNode node, String field) {
        String kind = requiredText(node, "kind", "node kind");
        if ("judge".equals(kind)) {
            for (JsonNode decisionField : arrayValues(node.get("decision_fields"))) {
                if (field.equals(text(decisionField, "name"))) {
                    return closedDomainNode(stringArray(decisionField.get("allowed_values"), "decision allowed_values"));
                }
            }
        }
        if ("human".equals(kind)) {
            for (JsonNode humanField : arrayValues(node.get("fields"))) {
                if (field.equals(text(humanField, "name")) && "enum".equals(textOr(humanField, "kind", "string"))) {
                    return closedDomainNode(stringArray(humanField.get("allowed_values"), "human allowed_values"));
                }
            }
        }
        if ("subrun".equals(kind) && "subrun_status".equals(field)) {
            return closedDomainNode(List.of("complete", "escalate", "failed"));
        }
        return Json.mapper().createObjectNode().put("kind", "open");
    }

    private static ObjectNode closedDomainNode(List<String> allowedValues) {
        ObjectNode domain = Json.mapper().createObjectNode();
        domain.put("kind", "closed");
        ArrayNode allowed = Json.mapper().createArrayNode();
        allowedValues.forEach(allowed::add);
        domain.set("allowed_values", allowed);
        return domain;
    }

    private static ObjectNode lowerLoopBudget(JsonNode budget) {
        ObjectNode budgetObject = requiredObject(budget, "loop budget");
        ObjectNode lowered = Json.mapper().createObjectNode();
        String kind = requiredText(budgetObject, "kind", "loop budget kind");
        lowered.put("kind", kind);
        if ("literal".equals(kind)) {
            lowered.put("max_iterations", budgetObject.get("max_iterations").asLong());
        } else {
            lowered.put("artifact", requiredText(budgetObject, "artifact", "loop budget artifact"));
            lowered.put("field", requiredText(budgetObject, "field", "loop budget field"));
        }
        return lowered;
    }

    private static JsonNode targetNode(String target) {
        if (COMPLETE_TARGET.equals(target)) {
            return TextNode.valueOf("complete");
        }
        if (ESCALATE_TARGET.equals(target)) {
            return TextNode.valueOf("escalate");
        }
        return Json.mapper().createObjectNode().put("node", target);
    }

    private static Path subrunFrozenWorkflowsDir(Path runDir) {
        return runDir.resolve("subruns").resolve("frozen-workflows");
    }

    private static String snapshotDirName(String key) {
        String hash = Hashing.sha256Hex(key.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path namePath = Path.of(key);
        Path fileName = namePath.getFileName();
        String label = fileName == null ? "workflow" : sanitizeSlugFragment(fileName.toString());
        return label + "-" + hash;
    }

    private static String relativePath(Path root, Path path) {
        Path relative = root.normalize().relativize(path.normalize());
        return relative.toString().replace('\\', '/');
    }

    private static String sanitizeSlugFragment(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                out.append(Character.toLowerCase(ch));
            } else {
                out.append('-');
            }
        }
        String slug = out.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "workflow" : slug;
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
        ObjectNode object = requiredObject(exports, "interface exports");
        Map<String, JsonNode> values = new TreeMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }

    private static @Nullable JsonNode artifactDefinition(WorkflowSpec spec, String name) {
        JsonNode artifacts = spec.artifacts();
        return artifacts == null || artifacts.isNull() ? null : artifacts.get(name);
    }

    private static String artifactMediaType(WorkflowSpec spec, String artifact) {
        JsonNode definition = artifactDefinition(spec, artifact);
        if (definition == null) {
            throw new ForgeException("unknown artifact '" + artifact + "'");
        }
        return requiredText(definition, "media_type", "artifact media_type");
    }

    private static boolean mediaTypesCompatible(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static ObjectNode requiredObject(JsonNode node, String label) {
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new ForgeException(label + " must be a JSON object");
    }

    private static ObjectNode requiredObject(JsonNode node, String field, String label) {
        return requiredObject(requiredNode(node, field, label), label);
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
        ObjectNode object = requiredObject(node, label);
        Map<String, String> values = new TreeMap<>();
        object.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new ForgeException(label + " values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(values);
    }

    private static void putOptionalText(ObjectNode object, String field, @Nullable JsonNode value) {
        if (value != null && !value.isNull()) {
            object.put(field, value.asText());
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

    private static void copy(Path source, Path target) {
        Path parent = target.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new ForgeException("failed to copy workflow source " + source + " to " + target, error);
        }
    }

    public interface TemplateResolver {
        ResolvedWorkflow resolve(String templateId);
    }

    public record ResolvedWorkflow(
            WorkflowSpec spec,
            Path sourceRoot,
            Path sourcePath,
            String snapshotKey
    ) {
    }

    public record CompiledWorkflow(
            WorkflowSpec spec,
            ObjectNode workflowIr,
            ObjectNode workflowInterface,
            String sourceSpecSha256
    ) {
        public CompiledWorkflow {
            workflowIr = workflowIr.deepCopy();
            workflowInterface = workflowInterface.deepCopy();
        }

        @Override
        public ObjectNode workflowIr() {
            return workflowIr.deepCopy();
        }

        @Override
        public ObjectNode workflowInterface() {
            return workflowInterface.deepCopy();
        }
    }
}
