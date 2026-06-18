package dev.llaith.forge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.cli.CliSupport;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public final class SpecCli {
    private static final String REQUEST_REPO_ROOT_CWD_TOKEN = "$request.repo_root";

    public static final String HELP = """
            forge spec — Spec preparation commands

            USAGE
              forge spec freeze-paths --spec=PATH [--check|--stdout|--write] [--out=PATH] [--freeze=hooks,cwd,commands,env]

            MODES
              --check    Show the proposed path freezes as a change list
              --stdout   Print the normalized spec JSON to stdout
              --write    Write the normalized spec back to --spec or to --out

            FREEZE GROUPS
              hooks      Freeze notification hook paths
              cwd        Freeze cwd values for agents, command nodes, and hooks
              commands   Freeze explicit path-like command arguments that resolve beside the spec
              env        Freeze path-like env values that resolve beside the spec
            """;

    private SpecCli() {
    }

    public static void run(List<String> args) {
        if (args.isEmpty() || CliSupport.isHelpFlag(args.getFirst())) {
            System.out.print(HELP);
            return;
        }

        String command = args.getFirst();
        if (!"freeze-paths".equals(command)) {
            throw new ForgeException("error: unknown spec command: " + command + "\n\n" + HELP);
        }
        List<String> rest = args.subList(1, args.size());
        if (CliSupport.containsHelp(rest)) {
            System.out.print(HELP);
            return;
        }
        freezePaths(rest);
    }

    private static void freezePaths(List<String> args) {
        String spec = null;
        String out = null;
        String mode = null;
        EnumSet<FreezeKind> freeze = EnumSet.allOf(FreezeKind.class);
        for (String arg : args) {
            if (arg.startsWith("--spec=")) {
                spec = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--out=")) {
                out = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--freeze=")) {
                freeze = parseFreezeGroups(CliSupport.optionValue(arg));
            } else if ("--check".equals(arg) || "--stdout".equals(arg) || "--write".equals(arg)) {
                mode = setMode(mode, arg.substring(2));
            } else {
                throw CliSupport.unknownOption(arg);
            }
        }
        if (spec == null) {
            throw CliSupport.missingOption("--spec=PATH");
        }
        String effectiveMode = mode == null ? "check" : mode;
        if (out != null && !"write".equals(effectiveMode)) {
            throw new ForgeException("error: --out=PATH requires --write");
        }

        Path specPath = Path.of(spec);
        ObjectNode workflowSpec = readSpecTree(specPath);
        FreezeResult frozen = freezeSpecWithKinds(workflowSpec, specPath, freeze);
        if ("stdout".equals(effectiveMode)) {
            System.out.print(Json.toPrettyJson(frozen.spec()));
        } else if ("write".equals(effectiveMode)) {
            Path target = out == null ? specPath : Path.of(out);
            Filesystem.writeUtf8(target, Json.toPrettyJson(frozen.spec()));
            System.out.print(Json.toPrettyJson(Map.of(
                    "status", "written",
                    "path", target.toString(),
                    "changed", !frozen.changes().isEmpty(),
                    "freeze", freezeLabels(freeze),
                    "changes", frozen.changes()
            )));
        } else {
            System.out.print(Json.toPrettyJson(Map.of(
                    "status", "checked",
                    "changed", !frozen.changes().isEmpty(),
                    "freeze", freezeLabels(freeze),
                    "changes", frozen.changes()
            )));
        }
    }

    private static String setMode(String current, String next) {
        if (current != null && !current.equals(next)) {
            throw new ForgeException("error: choose only one of --check, --stdout, or --write");
        }
        return next;
    }

    private static EnumSet<FreezeKind> parseFreezeGroups(String raw) {
        EnumSet<FreezeKind> groups = EnumSet.noneOf(FreezeKind.class);
        int start = 0;
        while (start <= raw.length()) {
            int end = raw.indexOf(',', start);
            String group = (end < 0 ? raw.substring(start) : raw.substring(start, end)).trim();
            groups.add(freezeKind(group));
            if (end < 0) {
                if (groups.isEmpty()) {
                    throw new ForgeException("error: --freeze must include at least one group");
                }
                return groups;
            }
            start = end + 1;
        }
        throw new ForgeException("error: --freeze must include at least one group");
    }

    private static FreezeKind freezeKind(String raw) {
        return switch (raw) {
            case "hooks" -> FreezeKind.HOOKS;
            case "cwd" -> FreezeKind.CWD;
            case "commands" -> FreezeKind.COMMANDS;
            case "env" -> FreezeKind.ENV;
            default -> throw new ForgeException("error: unknown freeze group '" + raw + "'");
        };
    }

    private static List<String> freezeLabels(EnumSet<FreezeKind> kinds) {
        List<String> labels = new ArrayList<>();
        for (FreezeKind kind : kinds) {
            labels.add(kind.label());
        }
        return labels;
    }

    private static ObjectNode readSpecTree(Path specPath) {
        return WorkflowSpecs.readResolvedTree(specPath);
    }

    private static FreezeResult freezeSpecWithKinds(
            ObjectNode spec,
            Path specPath,
            EnumSet<FreezeKind> kinds
    ) {
        Path base = freezeBaseDir(specPath);
        List<FreezeChange> changes = new ArrayList<>();

        JsonNode agents = spec.get("agents");
        if (agents instanceof ObjectNode agentMap) {
            for (String agentId : sortedFieldNames(agentMap)) {
                JsonNode agent = agentMap.get(agentId);
                if (agent instanceof ObjectNode agentObject) {
                    if (kinds.contains(FreezeKind.COMMANDS)) {
                        freezeCommand(base, agentObject.get("command"), changes, "agents." + agentId + ".command");
                    }
                    if (kinds.contains(FreezeKind.ENV)) {
                        freezeEnv(base, agentObject.get("env"), changes, "agents." + agentId + ".env");
                    }
                    if (kinds.contains(FreezeKind.CWD)) {
                        freezeCwd(base, agentObject, "cwd", changes, "agents." + agentId + ".cwd");
                    }
                }
            }
        }

        JsonNode nodes = spec.get("nodes");
        if (nodes instanceof ArrayNode nodeArray) {
            for (JsonNode node : nodeArray) {
                if (node instanceof ObjectNode nodeObject && "command".equals(textValue(nodeObject.get("kind")))) {
                    String nodeId = textValue(nodeObject.get("id"));
                    if (kinds.contains(FreezeKind.COMMANDS)) {
                        freezeCommand(base, nodeObject.get("command"), changes, "nodes." + nodeId + ".command");
                    }
                    if (kinds.contains(FreezeKind.ENV)) {
                        freezeEnv(base, nodeObject.get("env"), changes, "nodes." + nodeId + ".env");
                    }
                    if (kinds.contains(FreezeKind.CWD)) {
                        freezeCwd(base, nodeObject, "cwd", changes, "nodes." + nodeId + ".cwd");
                    }
                }
            }
        }

        JsonNode notifications = spec.get("notifications");
        if (notifications instanceof ObjectNode notificationObject) {
            freezeHook(base, notificationObject, "default_hook", kinds, changes);
            freezeHook(base, notificationObject, "complete_hook", kinds, changes);
            freezeHook(base, notificationObject, "escalate_hook", kinds, changes);
            freezeHook(base, notificationObject, "human_review_hook", kinds, changes);
        }

        return new FreezeResult(spec, List.copyOf(changes));
    }

    private static void freezeHook(
            Path base,
            ObjectNode notifications,
            String label,
            EnumSet<FreezeKind> kinds,
            List<FreezeChange> changes
    ) {
        JsonNode hook = notifications.get(label);
        if (hook instanceof ObjectNode hookObject) {
            if (kinds.contains(FreezeKind.HOOKS)) {
                freezeTextField(base, hookObject, "path", changes, "notifications." + label + ".path");
            }
            if (kinds.contains(FreezeKind.ENV)) {
                freezeEnv(base, hookObject.get("env"), changes, "notifications." + label + ".env");
            }
            if (kinds.contains(FreezeKind.CWD)) {
                freezeCwd(base, hookObject, "cwd", changes, "notifications." + label + ".cwd");
            }
        }
    }

    private static void freezeCommand(Path base, JsonNode command, List<FreezeChange> changes, String fieldPrefix) {
        if (command instanceof ArrayNode commandArray) {
            for (int index = 0; index < commandArray.size(); index++) {
                JsonNode argument = commandArray.get(index);
                if (argument != null && argument.isTextual()
                        && shouldFreezeRunLocalReference(base, argument.asText())) {
                    freezeArrayText(base, commandArray, index, changes, fieldPrefix + "[" + index + "]");
                }
            }
        }
    }

    private static void freezeEnv(Path base, JsonNode env, List<FreezeChange> changes, String fieldPrefix) {
        if (env instanceof ObjectNode envObject) {
            for (String key : sortedFieldNames(envObject)) {
                JsonNode value = envObject.get(key);
                if (value != null && value.isTextual() && shouldFreezeRunLocalReference(base, value.asText())) {
                    freezeTextField(base, envObject, key, changes, fieldPrefix + "." + key);
                }
            }
        }
    }

    private static void freezeCwd(
            Path base,
            ObjectNode object,
            String property,
            List<FreezeChange> changes,
            String field
    ) {
        JsonNode cwd = object.get(property);
        if (cwd != null && cwd.isTextual() && !REQUEST_REPO_ROOT_CWD_TOKEN.equals(cwd.asText())) {
            freezeTextField(base, object, property, changes, field);
        }
    }

    private static void freezeArrayText(
            Path base,
            ArrayNode array,
            int index,
            List<FreezeChange> changes,
            String field
    ) {
        String from = array.get(index).asText();
        String to = freezeRelativePath(base, from);
        if (!from.equals(to)) {
            array.set(index, TextNode.valueOf(to));
            changes.add(new FreezeChange(field, from, to));
        }
    }

    private static void freezeTextField(
            Path base,
            ObjectNode object,
            String property,
            List<FreezeChange> changes,
            String field
    ) {
        JsonNode value = object.get(property);
        if (value == null || !value.isTextual()) {
            return;
        }
        String from = value.asText();
        String to = freezeRelativePath(base, from);
        if (!from.equals(to)) {
            object.put(property, to);
            changes.add(new FreezeChange(field, from, to));
        }
    }

    private static boolean shouldFreezeRunLocalReference(Path base, String raw) {
        if (Path.of(raw).isAbsolute()) {
            return true;
        }
        if (!isExplicitPathReference(raw)) {
            return false;
        }
        return Files.exists(base.resolve(raw));
    }

    private static boolean isExplicitPathReference(String raw) {
        return Path.of(raw).isAbsolute() || raw.contains(File.separator);
    }

    private static String freezeRelativePath(Path base, String raw) {
        Path path = Path.of(raw);
        Path frozen = path.isAbsolute() ? path : base.resolve(path);
        return frozen.normalize().toString();
    }

    private static Path freezeBaseDir(Path specPath) {
        Path base = specPath.getParent();
        if (base == null) {
            base = Path.of(".");
        }
        if (base.isAbsolute()) {
            return base.normalize();
        }
        return Path.of("").toAbsolutePath().resolve(base).normalize();
    }

    private static List<String> sortedFieldNames(ObjectNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return names;
    }

    private static String textValue(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText();
    }

    private enum FreezeKind {
        HOOKS("hooks"),
        CWD("cwd"),
        COMMANDS("commands"),
        ENV("env");

        private final String label;

        FreezeKind(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    private record FreezeChange(String field, String from, String to) {
    }

    private record FreezeResult(ObjectNode spec, List<FreezeChange> changes) {
        private FreezeResult {
            changes = List.copyOf(changes);
        }
    }
}
