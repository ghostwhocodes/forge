package dev.llaith.forge;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecCompiler;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.RunPaths;
import dev.llaith.forge.template.Templates;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class ParitySuiteTest {
    @TempDir
    private Path tempDir;

    @Test
    void javaVersionMatchesCapturedRustBaseline() throws Exception {
        CapturedInvocation version = capture(() -> Main.exitCode(new String[]{"version"}));

        assertThat(version.exitCode()).isZero();
        assertThat(version.stdout()).isEqualTo(Files.readString(
                Path.of("src/test/resources/parity/rust-version.stdout")));
    }

    @Test
    void javaSpecLoweringMatchesCapturedRustBaseline() {
        WorkflowSpec spec = WorkflowSpecs.load(Path.of("src/test/resources/parity/runtime-command-workflow.json"));
        JsonNode expectedIr = Json.read(
                Path.of("src/test/resources/parity/rust-runtime-workflow-ir.json"),
                JsonNode.class);
        JsonNode expectedInterface = Json.read(
                Path.of("src/test/resources/parity/rust-runtime-workflow-interface.json"),
                JsonNode.class);

        WorkflowSpecCompiler.CompiledWorkflow compiled = WorkflowSpecCompiler.compile(spec);
        assertThat(compiled.workflowIr()).isEqualTo(expectedIr);
        assertThat(compiled.workflowInterface()).isEqualTo(expectedInterface);
    }

    @Test
    void javaReplayReadsCapturedRustRuntimeEvents() {
        Path rustEvents = Path.of("src/test/resources/parity/rust-runtime-events.ndjson");

        var events = EventLog.readEvents(rustEvents);
        assertThat(events).extracting(event -> event.type()).containsExactly(
                "run_initialized",
                "node_entered",
                "operation_prepared",
                "operation_started",
                "operation_succeeded",
                "operation_completed",
                "transition_taken",
                "run_completed");
        var state = WorkflowReducer.deriveState(events);
        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.completedMessage()).isEqualTo("run 'golden' completed");
        assertThat(state.operationExecutions().get("plan-1").get("stdout_bytes").asInt()).isEqualTo(10);
        assertThat(state.pendingOperation()).isNull();
    }

    @Test
    void javaRuntimeCommandPathMatchesGoldenEventAndArtifactShape() throws Exception {
        Path spec = Path.of("src/test/resources/parity/runtime-command-workflow.json");
        Path runs = tempDir.resolve("runs");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=golden"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=golden"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=golden"
        })).stdout()).contains("\"success\" : true");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=golden", "--dispatch-id=plan-1"
        })).exitCode()).isZero();

        Path runDir = runs.resolve("golden");
        assertThat(Files.exists(runDir.resolve("spec.json"))).isTrue();
        assertThat(Files.exists(runDir.resolve("workflow-ir.json"))).isTrue();
        assertThat(Files.exists(runDir.resolve("workflow-interface.json"))).isTrue();
        assertThat(Files.readString(runDir.resolve("run-state-version"))).isEqualTo("1\n");
        assertThat(Files.readString(RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stdout")))
                .isEqualTo("parity-out");
        assertThat(Files.readString(RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stderr")))
                .isEqualTo("parity-err");

        var events = EventLog.readEvents(RunPaths.eventsPath(runDir));
        assertThat(events).extracting(event -> event.type()).containsExactly(
                "run_initialized",
                "node_entered",
                "operation_prepared",
                "operation_started",
                "operation_heartbeat",
                "operation_succeeded",
                "operation_completed",
                "transition_taken",
                "run_completed");
        JsonNode prelaunchExecution = events.get(3).field("execution");
        assertThat(prelaunchExecution).isNotNull();
        assertThat(prelaunchExecution.get("active").asBoolean()).isTrue();
        assertThat(prelaunchExecution.get("launched").asBoolean()).isFalse();
        assertThat(prelaunchExecution.hasNonNull("pid")).isFalse();
        JsonNode launchExecution = events.get(4).field("execution");
        assertThat(launchExecution).isNotNull();
        assertThat(launchExecution.get("active").asBoolean()).isTrue();
        assertThat(launchExecution.get("launched").asBoolean()).isTrue();
        assertThat(launchExecution.get("pid").asLong()).isPositive();
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus())
                .isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void scaffoldAllBuiltInsProducesExpectedTemplateInventory() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        CapturedInvocation scaffold = capture(() -> Main.exitCode(new String[]{
                "scaffold", "repo", "--repo=" + repo, "--all"
        }));

        assertThat(scaffold.exitCode()).isZero();
        for (String template : Templates.builtInTemplateIds()) {
            assertThat(Files.exists(repo.resolve(".forge/templates/" + template + "/template.json"))).isTrue();
            assertThat(Files.exists(repo.resolve(".forge/templates/" + template + "/workflow.json"))).isTrue();
        }
        assertThat(Files.exists(repo.resolve(".forge/README.md"))).isTrue();
    }

    @Test
    void intakeToRuntimeGoldenRequestShapeStaysStructured() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("subject"));
        Path out = tempDir.resolve("intake");

        CapturedInvocation intake = capture(() -> Main.exitCode(new String[]{
                "intake", "simple",
                "--repo=" + repo,
                "--goal=Ship parity",
                "--out=" + out,
                "--scope=src",
                "--acceptance=tests pass"
        }));

        assertThat(intake.exitCode()).isZero();
        JsonNode request = dev.llaith.forge.util.Json.read(out.resolve("artifacts/request.json"), JsonNode.class);
        assertThat(request.get("request_kind").asText()).isEqualTo("change_request");
        assertThat(request.get("repo_root").asText()).isEqualTo(repo.toRealPath().toString());
        assertThat(request.get("scope_paths").get(0).asText()).isEqualTo("src");
        assertThat(request.get("acceptance_criteria").get(0).asText()).isEqualTo("tests pass");
    }

    private static CapturedInvocation capture(ExitSupplier invocation) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream outCapture = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream errCapture = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(outCapture);
            System.setErr(errCapture);
            int exitCode = invocation.get();
            return new CapturedInvocation(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static DerivedRunState loadDerivedState(Path eventsPath) {
        return WorkflowReducer.deriveState(EventLog.readEvents(eventsPath));
    }

    private record CapturedInvocation(int exitCode, String stdout, String stderr) {
    }

    @FunctionalInterface
    private interface ExitSupplier {
        int get();
    }
}
