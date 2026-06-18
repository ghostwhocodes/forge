package dev.llaith.forge.spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ToolRequirementChecker {
    private ToolRequirementChecker() {
    }

    public static Report check(ToolRequirements requirements) {
        return check(requirements, System.getenv());
    }

    public static Report check(ToolRequirements requirements, Map<String, String> environment) {
        List<CommandAvailability> allOf = requirements.allOf().stream()
                .map(command -> new CommandAvailability(command, isAvailable(command, environment)))
                .toList();
        List<AlternativeGroup> anyOf = new ArrayList<>();
        for (List<String> group : requirements.anyOf()) {
            List<CommandAvailability> commands = group.stream()
                    .map(command -> new CommandAvailability(command, isAvailable(command, environment)))
                    .toList();
            anyOf.add(new AlternativeGroup(commands, commands.stream().anyMatch(CommandAvailability::available)));
        }
        boolean satisfied = allOf.stream().allMatch(CommandAvailability::available)
                && anyOf.stream().allMatch(AlternativeGroup::satisfied);
        return new Report(allOf, List.copyOf(anyOf), satisfied);
    }

    private static boolean isAvailable(String command, Map<String, String> environment) {
        String path = environment.getOrDefault("PATH", "");
        if (path.isBlank()) {
            return false;
        }
        for (String rawDir : splitLiteral(path, java.io.File.pathSeparator)) {
            if (rawDir.isBlank()) {
                continue;
            }
            Path dir = Path.of(rawDir);
            for (String candidate : commandCandidates(command, environment)) {
                Path executable = dir.resolve(candidate);
                if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> commandCandidates(String command, Map<String, String> environment) {
        if (!isWindows()) {
            return List.of(command);
        }
        String lower = command.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add(command);
        String pathExt = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        for (String extension : splitLiteral(pathExt, ";")) {
            if (extension.isBlank()) {
                continue;
            }
            String normalized = extension.startsWith(".") ? extension : "." + extension;
            if (!lower.endsWith(normalized.toLowerCase(Locale.ROOT))) {
                candidates.add(command + normalized);
            }
        }
        return List.copyOf(candidates);
    }

    private static List<String> splitLiteral(String value, String separator) {
        List<String> values = new ArrayList<>();
        int start = 0;
        while (start <= value.length()) {
            int next = value.indexOf(separator, start);
            if (next < 0) {
                values.add(value.substring(start));
                return List.copyOf(values);
            }
            values.add(value.substring(start, next));
            start = next + separator.length();
        }
        return List.copyOf(values);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record Report(
            List<CommandAvailability> allOf,
            List<AlternativeGroup> anyOf,
            boolean satisfied
    ) {
        public Report {
            allOf = List.copyOf(allOf);
            anyOf = List.copyOf(anyOf);
        }

        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("satisfied", satisfied);
            payload.put("all_of", allOf.stream().map(CommandAvailability::toPayload).toList());
            payload.put("any_of", anyOf.stream().map(AlternativeGroup::toPayload).toList());
            List<String> missing = missingDescriptions();
            if (!missing.isEmpty()) {
                payload.put("missing", missing);
            }
            return payload;
        }

        public List<String> missingDescriptions() {
            List<String> missing = new ArrayList<>();
            for (CommandAvailability command : allOf) {
                if (!command.available()) {
                    missing.add("required command '" + command.command() + "' was not found on PATH");
                }
            }
            for (AlternativeGroup group : anyOf) {
                if (!group.satisfied()) {
                    missing.add("at least one of ["
                            + String.join(", ", group.commands().stream().map(CommandAvailability::command).toList())
                            + "] must be available on PATH");
                }
            }
            return List.copyOf(missing);
        }
    }

    public record AlternativeGroup(List<CommandAvailability> commands, boolean satisfied) {
        public AlternativeGroup {
            commands = List.copyOf(commands);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("satisfied", satisfied);
            payload.put("commands", commands.stream().map(CommandAvailability::toPayload).toList());
            return payload;
        }
    }

    public record CommandAvailability(String command, boolean available) {
        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", command);
            payload.put("available", available);
            return payload;
        }
    }
}
