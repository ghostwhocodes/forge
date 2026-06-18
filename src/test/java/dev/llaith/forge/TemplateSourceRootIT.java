package dev.llaith.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class TemplateSourceRootIT {
    @TempDir
    private Path tempDir;

    @Test
    void packagedLauncherResolvesBuiltInTemplatesOutsideRepositoryWorkingDirectory() throws Exception {
        Path launcher = Path.of("target/forge").toAbsolutePath().normalize();
        Path jar = Path.of("target/forge-0.1.0.jar").toAbsolutePath().normalize();
        Path packagedTemplate = Path.of("target/forge-assets/templates/implement-change/template.json")
                .toAbsolutePath()
                .normalize();
        Path packagedTemplates = Path.of("target/forge-assets/templates")
                .toAbsolutePath()
                .normalize();
        assertThat(Files.isExecutable(launcher)).isTrue();
        assertThat(jar).isRegularFile();
        assertThat(packagedTemplate).isRegularFile();
        assertThat(packagedTemplates.resolve("implement-change/scripts/stale-package-sentinel.sh")).doesNotExist();
        assertPackagedExecutablesMatchSource(packagedTemplates);
        Path workingDirectory = Files.createDirectory(tempDir.resolve("outside-cwd"));
        Files.createDirectory(workingDirectory.resolve("templates"));
        Path scaffoldRepo = Files.createDirectory(tempDir.resolve("scaffold-repo"));
        Path intakeRepo = Files.createDirectory(tempDir.resolve("intake-repo"));
        Path intakeOut = tempDir.resolve("intake-out");

        CommandResult list = run(workingDirectory, launcher, "template", "list");
        assertThat(list.exitCode()).isZero();
        assertThat(list.stderr()).isEmpty();
        assertThat(list.stdout()).contains("implement-change", "review-only", "qa-gap-guard");

        CommandResult jarList = runCommand(workingDirectory,
                "java", "-jar", jar.toString(), "template", "list");
        assertThat(jarList.exitCode()).isZero();
        assertThat(jarList.stderr()).isEmpty();
        assertThat(jarList.stdout()).contains("implement-change", "review-only", "qa-gap-guard");

        CommandResult scaffold = run(workingDirectory, launcher,
                "scaffold", "repo", "--repo=" + scaffoldRepo, "--template=implement-change");
        assertThat(scaffold.exitCode()).isZero();
        assertThat(scaffold.stderr()).isEmpty();
        assertThat(scaffold.stdout()).contains("\"templates\" : [ \"implement-change\" ]");
        assertThat(scaffoldRepo.resolve(".forge/templates/implement-change/template.json")).isRegularFile();
        assertThat(scaffoldRepo.resolve(".forge/templates/implement-change/hooks/notify"))
                .isRegularFile()
                .isExecutable();
        assertThat(scaffoldRepo.resolve(".forge/templates/implement-change/scripts/write-quality-policy.sh"))
                .isRegularFile()
                .isExecutable();

        CommandResult intake = run(workingDirectory, launcher,
                "intake", "review", "--repo=" + intakeRepo, "--out=" + intakeOut, "--goal=Review this");
        assertThat(intake.exitCode()).isZero();
        assertThat(intake.stderr()).isEmpty();
        assertThat(intake.stdout()).contains("\"template_id\" : \"review-only\"");
        assertThat(intakeOut.resolve("artifacts/request.json")).isRegularFile();
    }

    @Test
    void packagedLauncherRunsTemplateAndMinimalWorkflowSmokeCommands() throws Exception {
        Path launcher = Path.of("target/forge").toAbsolutePath().normalize();
        Path jar = Path.of("target/forge-0.1.0.jar").toAbsolutePath().normalize();
        Path assets = Path.of("target/forge-assets").toAbsolutePath().normalize();
        Path workingDirectory = Files.createDirectory(tempDir.resolve("package-outside-cwd"));

        assertThat(launcher).isRegularFile().isExecutable();
        assertThat(jar).isRegularFile();
        assertThat(assets.resolve("templates/implement-change/template.json")).isRegularFile();

        CommandResult version = run(workingDirectory, launcher, "version");
        assertThat(version.exitCode()).isZero();
        assertThat(version.stdout()).isEqualTo("forge 0.1.0\n");
        assertThat(version.stderr()).isEmpty();

        CommandResult list = run(workingDirectory, launcher, "template", "list");
        assertThat(list.exitCode()).isZero();
        assertThat(list.stderr()).isEmpty();
        assertThat(list.stdout()).contains("implement-change", "review-only", "qa-gap-guard");

        for (String template : List.of(
                "implement-change",
                "review-only",
                "review-and-fix",
                "auto-review-and-fix",
                "architecture-guard",
                "qa-gap-guard")) {
            CommandResult validate = run(workingDirectory, launcher, "template", "validate", "--template=" + template);
            assertThat(validate.exitCode()).isZero();
            assertThat(validate.stderr()).isEmpty();
            assertThat(validate.stdout()).contains("\"status\" : \"valid\"", "\"tool_requirements\"");
        }

        Path repo = Path.of("").toAbsolutePath().normalize();
        Path runs = tempDir.resolve("release-smoke-runs");
        CommandResult init = run(workingDirectory, launcher,
                "run", "init",
                "--spec=" + repo.resolve("docs/examples/minimal/software-loop.json"),
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + repo.resolve("docs/examples/minimal/request-simple.json"));
        assertThat(init.exitCode()).isZero();
        assertThat(init.stderr()).isEmpty();

        CommandResult next = run(workingDirectory, launcher,
                "run", "next", "--runs=" + runs, "--slug=sample");
        assertThat(next.exitCode()).isZero();
        assertThat(next.stderr()).isEmpty();
        assertThat(next.stdout()).contains("\"action\" : \"dispatch\"");
    }

    private static void assertPackagedExecutablesMatchSource(Path packagedTemplates) throws IOException {
        Path sourceTemplates = Path.of("templates").toAbsolutePath().normalize();
        try (var stream = Files.walk(sourceTemplates)) {
            List<Path> executableFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .map(sourceTemplates::relativize)
                    .toList();
            assertThat(executableFiles).isNotEmpty();
            for (Path relative : executableFiles) {
                assertThat(packagedTemplates.resolve(relative))
                        .as("packaged executable %s", relative)
                        .isRegularFile()
                        .isExecutable();
            }
        }
    }

    private static CommandResult run(Path workingDirectory, Path launcher, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        return runCommand(workingDirectory, command.toArray(String[]::new));
    }

    private static CommandResult runCommand(Path workingDirectory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        builder.environment().remove("FORGE_SOURCE_ROOT");
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("timed out running " + String.join(" ", command));
        }
        return new CommandResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
