package dev.llaith.forge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ToolRequirementsTest {
    @TempDir
    private Path tempDir;

    @Test
    void parsesAndSerializesToolRequirements() throws Exception {
        ToolRequirements requirements = parse("""
                {
                  "all_of": ["bash", "codex"],
                  "any_of": [["jq", "python3"]]
                }
                """);

        assertThat(requirements.allOf()).containsExactly("bash", "codex");
        assertThat(requirements.anyOf()).containsExactly(java.util.List.of("jq", "python3"));
        assertThat(requirements.toJson().at("/all_of/1").asText()).isEqualTo("codex");
        assertThat(ToolRequirements.fromJson(null).isEmpty()).isTrue();
    }

    @Test
    void rejectsMalformedToolRequirementShapes() {
        assertInvalid("[]", "tool_requirements must be a JSON object");
        assertInvalid("{\"unknown\": []}", "unknown field 'unknown'");
        assertInvalid("{\"all_of\": {}}", "all_of must be a JSON array");
        assertInvalid("{\"all_of\": [1]}", "all_of must contain only command names");
        assertInvalid("{\"all_of\": [\"\"]}", "command names must not be empty");
        assertInvalid("{\"all_of\": [\" bash\"]}", "must not contain surrounding whitespace");
        assertInvalid("{\"all_of\": [\"bin/bash\"]}", "must use only ASCII letters");
        assertInvalid("{\"any_of\": {}}", "any_of must be a JSON array");
        assertInvalid("{\"any_of\": [[]]}", "any_of[0] must not be empty");
        assertInvalid("{\"any_of\": [[\"bash\", \"bash\"]]}", "command name 'bash' is duplicated");
    }

    @Test
    void checksCommandAvailabilityAndRendersPayload() throws Exception {
        Path bin = Files.createDirectory(tempDir.resolve("bin"));
        Path bash = executable(bin.resolve("bash"));
        Path python = executable(bin.resolve("python3"));
        assertThat(bash).isRegularFile();
        assertThat(python).isRegularFile();
        ToolRequirements requirements = new ToolRequirements(
                java.util.List.of("bash"),
                java.util.List.of(java.util.List.of("jq", "python3")));

        ToolRequirementChecker.Report available = ToolRequirementChecker.check(
                requirements,
                Map.of("PATH", bin.toString()));

        assertThat(available.satisfied()).isTrue();
        assertThat(available.missingDescriptions()).isEmpty();
        assertThat(available.toPayload())
                .containsEntry("satisfied", true)
                .containsKey("all_of")
                .containsKey("any_of");

        ToolRequirementChecker.Report missing = ToolRequirementChecker.check(
                requirements,
                Map.of("PATH", tempDir.resolve("empty").toString()));

        assertThat(missing.satisfied()).isFalse();
        assertThat(missing.missingDescriptions())
                .containsExactly(
                        "required command 'bash' was not found on PATH",
                        "at least one of [jq, python3] must be available on PATH");
        assertThat(missing.toPayload()).containsKey("missing");
    }

    @Test
    void emptyPathNeverSatisfiesCommands() {
        ToolRequirementChecker.Report report = ToolRequirementChecker.check(
                new ToolRequirements(java.util.List.of("bash"), java.util.List.of()),
                Map.of("PATH", ""));

        assertThat(report.satisfied()).isFalse();
    }

    private static ToolRequirements parse(String json) throws Exception {
        JsonNode node = Json.mapper().readTree(json);
        return ToolRequirements.fromJson(node);
    }

    private static void assertInvalid(String json, String message) {
        assertThatThrownBy(() -> parse(json))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining(message);
    }

    private static Path executable(Path path) throws Exception {
        Files.writeString(path, "#!/usr/bin/env bash\n");
        assertThat(path.toFile().setExecutable(true)).isTrue();
        return path;
    }
}
