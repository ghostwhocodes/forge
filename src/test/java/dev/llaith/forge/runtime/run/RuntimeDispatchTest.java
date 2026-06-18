package dev.llaith.forge.runtime.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.Main;
import dev.llaith.forge.runtime.dispatch.DispatchProgressCursor;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.storage.AdvisoryLock;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.ProjectionBacklogEntry;
import dev.llaith.forge.storage.RunPaths;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactRole;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RuntimeDispatchTest {
    private static final ObjectMapper MAPPER = Json.mapper();

    @TempDir
    private Path tempDir;

    @Test
    void runInitNextExecAndCompleteUseDurableCommandPath() throws Exception {
        Path spec = writeCommandSpec("printf out; printf err >&2", "__complete__");
        Path runs = tempDir.resolve("runs");

        CapturedInvocation init = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(init.exitCode()).isZero();
        assertThat(init.stdout())
                .contains("\"status\" : \"initialized\"")
                .contains("\"entry_node\" : \"plan\"")
                .doesNotContain("\"workflow_id\"");

        CapturedInvocation status = capture(() -> Main.exitCode(new String[]{
                "run", "status",
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(status.exitCode()).isZero();
        assertThat(status.stdout()).contains("Status: running", "Action: dispatch", "Dispatch: plan-1");

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next",
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(next.exitCode()).isZero();
        JsonNode nextPayload = MAPPER.readTree(next.stdout());
        assertThat(nextPayload.get("action").asText()).isEqualTo("dispatch");
        assertThat(nextPayload.get("dispatch_id").asText()).isEqualTo("plan-1");
        assertThat(nextPayload.get("dispatch_kind").asText()).isEqualTo("node");
        assertThat(nextPayload.get("runner").asText()).isEqualTo("command");
        assertThat(nextPayload.get("command").get(0).asText()).isEqualTo("sh");
        assertThat(nextPayload.has("status")).isFalse();
        assertThat(nextPayload.has("run_dir")).isFalse();
        Path runDir = runs.resolve("sample");
        JsonNode preparedOperation = EventLog.readEvents(RunPaths.eventsPath(runDir)).get(2).field("operation");
        assertThat(preparedOperation).isNotNull();
        assertThat(preparedOperation.get("operation_kind").asText()).isEqualTo("node_dispatch");
        assertThat(preparedOperation.has("kind")).isFalse();
        assertThat(preparedOperation.has("command")).isFalse();
        assertThat(preparedOperation.get("dispatch").get("command").get(0).asText()).isEqualTo("sh");

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress",
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(progress.exitCode()).isZero();
        assertThat(progress.stdout()).contains("\"run_status\" : \"waiting_for_agent\"");
        assertThat(progress.stdout()).contains("\"pending_operation\" : \"plan-1\"");

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch",
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(exec.exitCode()).isZero();
        assertThat(exec.stdout()).contains("\"status\" : \"executed\"", "\"success\" : true");
        assertThat(Files.readString(RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stdout"))).isEqualTo("out");
        assertThat(Files.readString(RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stderr"))).isEqualTo("err");
        JsonNode execution = loadDerivedState(RunPaths.eventsPath(runDir)).operationExecutions().get("plan-1");
        assertThat(execution.get("kind").asText()).isEqualTo("process");
        assertThat(execution.get("operation_kind").asText()).isEqualTo("node_dispatch");
        assertThat(execution.get("started_at").asText()).isNotBlank();
        assertThat(execution.get("progress_path").asText()).isEqualTo("dispatch/output/plan-1.progress.ndjson");
        assertThat(execution.get("stdout_path").asText()).isEqualTo("dispatch/output/plan-1.stdout");
        assertThat(execution.get("stderr_path").asText()).isEqualTo("dispatch/output/plan-1.stderr");
        assertThat(execution.get("result_path").asText()).isEqualTo("dispatch/output/plan-1.result.json");
        assertThat(execution.get("active").asBoolean()).isFalse();
        assertThat(execution.get("launched").asBoolean()).isTrue();
        assertThat(execution.get("pid").asLong()).isPositive();

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--decision=status=accepted"
        }));
        assertThat(complete.exitCode()).isZero();
        assertThat(complete.stdout())
                .contains("\"status\" : \"dispatch_completed\"")
                .contains("\"run_status\" : \"completed\"")
                .contains("\"current_node\" : \"plan\"");
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void execDispatchReturnsRecordedResultWithoutRerunningCommand() throws Exception {
        Path counter = tempDir.resolve("dispatch-count.txt");
        Path spec = writeCommandSpec("""
                count=0
                test -f '%s' && count=$(cat '%s')
                count=$((count + 1))
                printf '%%s' "$count" > '%s'
                printf run
                """.formatted(counter, counter, counter), "__complete__");
        Path runs = tempDir.resolve("runs-idempotent-exec");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation first = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        CapturedInvocation second = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(first.exitCode()).isZero();
        assertThat(second.exitCode()).isZero();
        assertThat(first.stdout()).contains("\"status\" : \"executed\"", "\"success\" : true");
        assertThat(second.stdout()).contains("\"status\" : \"already_executed\"", "\"success\" : true");
        assertThat(Files.readString(counter)).isEqualTo("1");
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(event -> event.type())
                .toList();
        assertThat(eventTypes.stream().filter("operation_started"::equals).count()).isEqualTo(1);
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isEqualTo(1);
    }

    @Test
    void execDispatchRejectsMalformedRecordedResultSidecarAfterTerminalEvent() throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        Path runs = tempDir.resolve("runs-malformed-recorded-result-sidecar");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        ObjectNode malformedResult = (ObjectNode) MAPPER.readTree(resultPath.toFile());
        malformedResult.put("active", true);
        Json.writePretty(resultPath, malformedResult);

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr()).contains("recorded dispatch result", "active must be false");
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isEqualTo(1);
    }

    @Test
    void execDispatchRejectsAbsoluteRecordedResultPath() throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        Path runs = tempDir.resolve("runs-absolute-recorded-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        replaceExecutionField(runDir, "plan-1", "result_path", tempDir.resolve("outside-result.json").toString());

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr()).contains("result_path", "run-relative", "absolute");
    }

    @Test
    void execDispatchRejectsSymlinkedRecordedResultSidecar() throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        Path runs = tempDir.resolve("runs-symlinked-recorded-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        Path externalResult = tempDir.resolve("outside-result.json");
        Files.move(resultPath, externalResult);
        Files.createSymbolicLink(resultPath, externalResult);

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr()).contains("result_path", "symlink");
    }

    @Test
    void execDispatchRejectsEscapingRecordedOutputPathForTeeReplay() throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        Path runs = tempDir.resolve("runs-escaping-recorded-output");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Files.writeString(runs.resolve("escaped-stdout.txt"), "outside replay");
        replaceExecutionField(runDir, "plan-1", "stdout_path", "../escaped-stdout.txt");

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample", "--tee"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr())
                .contains("stdout_path", "inside the run directory")
                .doesNotContain("outside replay");
    }

    @Test
    void execDispatchRejectsSymlinkedRecordedOutputPathForTeeReplay() throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        Path runs = tempDir.resolve("runs-symlinked-recorded-output");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path stdoutPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stdout");
        Path externalStdout = tempDir.resolve("outside-stdout.txt");
        Files.writeString(externalStdout, "outside replay");
        Files.delete(stdoutPath);
        Files.createSymbolicLink(stdoutPath, externalStdout);

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample", "--tee"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr())
                .contains("stdout_path", "symlink")
                .doesNotContain("outside replay");
    }

    @Test
    void execDispatchRejectsInvalidStaleExecutionResultPathsBeforeRecoveryWrites() throws Exception {
        Path absoluteRuns = tempDir.resolve("runs-absolute-stale-result");
        Path absoluteRunDir = preparePendingCommandRun(absoluteRuns);
        appendStaleExecution(absoluteRunDir, "plan-1");
        Path externalResult = tempDir.resolve("outside-stale.result.json");
        replaceExecutionField(absoluteRunDir, "plan-1", "result_path", externalResult.toString());

        CapturedInvocation absoluteReplay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + absoluteRuns, "--slug=sample"
        }));

        assertThat(absoluteReplay.exitCode()).isEqualTo(1);
        assertThat(absoluteReplay.stdout()).isEmpty();
        assertThat(absoluteReplay.stderr()).contains("result_path", "run-relative", "absolute");
        assertThat(Files.exists(externalResult)).isFalse();
        assertThat(terminalExecutionEventCount(absoluteRunDir, "plan-1")).isZero();

        Path escapingRuns = tempDir.resolve("runs-escaping-stale-result");
        Path escapingRunDir = preparePendingCommandRun(escapingRuns);
        appendStaleExecution(escapingRunDir, "plan-1");
        Path escapedResult = escapingRuns.resolve("escaped-stale.result.json");
        replaceExecutionField(escapingRunDir, "plan-1", "result_path", "../escaped-stale.result.json");

        CapturedInvocation escapingReplay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + escapingRuns, "--slug=sample"
        }));

        assertThat(escapingReplay.exitCode()).isEqualTo(1);
        assertThat(escapingReplay.stdout()).isEmpty();
        assertThat(escapingReplay.stderr()).contains("result_path", "inside the run directory");
        assertThat(Files.exists(escapedResult)).isFalse();
        assertThat(terminalExecutionEventCount(escapingRunDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchRejectsSymlinkedStaleExecutionResultPathBeforeRecoveryWrites() throws Exception {
        Path runs = tempDir.resolve("runs-symlinked-stale-result");
        Path runDir = preparePendingCommandRun(runs);
        appendStaleExecution(runDir, "plan-1");
        Path externalResult = tempDir.resolve("outside-stale.result.json");
        Files.writeString(externalResult, "outside");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        Files.createDirectories(resultPath.getParent());
        Files.createSymbolicLink(resultPath, externalResult);

        CapturedInvocation replay = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(replay.exitCode()).isEqualTo(1);
        assertThat(replay.stdout()).isEmpty();
        assertThat(replay.stderr()).contains("result_path", "symlink");
        assertThat(Files.readString(externalResult)).isEqualTo("outside");
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchReportsLiveExecutionWithoutStartingDuplicateProcess() throws Exception {
        Path ready = tempDir.resolve("already-running-ready");
        Path spec = writeCommandSpec("touch " + shellQuote(ready) + "; sleep 2; printf done", "__complete__");
        Path runs = tempDir.resolve("runs-already-running");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        RuntimeEngine engine = new RuntimeEngine();
        RuntimeEngine.Locator locator = new RuntimeEngine.Locator(runs, RunSlug.parse("sample"));
        CompletableFuture<Map<String, Object>> running = CompletableFuture.supplyAsync(
                () -> engine.executePendingDispatch(locator, false));
        waitFor(() -> Files.exists(ready), running);

        CapturedInvocation duplicate = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(duplicate.exitCode()).isZero();
        assertThat(duplicate.stdout())
                .contains("\"status\" : \"already_running\"")
                .contains("\"active\" : true")
                .contains("\"pid\" :");
        JsonNode duplicatePayload = MAPPER.readTree(duplicate.stdout());
        assertThat(duplicatePayload.get("pid").asLong()).isPositive();
        assertThat(running.get(5, TimeUnit.SECONDS).get("success")).isEqualTo(true);
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runs.resolve("sample"))).stream()
                .map(EventEnvelope::type)
                .toList();
        assertThat(eventTypes.stream().filter("operation_started"::equals).count()).isEqualTo(1);
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isEqualTo(1);
    }

    @Test
    void autoFailsWhenDispatchIsAlreadyRunning() throws Exception {
        Path ready = tempDir.resolve("auto-already-running-ready");
        Path spec = writeCommandSpec("touch " + shellQuote(ready) + "; sleep 2; printf done", "__complete__");
        Path runs = tempDir.resolve("runs-auto-already-running");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        RuntimeEngine engine = new RuntimeEngine();
        RuntimeEngine.Locator locator = new RuntimeEngine.Locator(runs, RunSlug.parse("sample"));
        CompletableFuture<Map<String, Object>> running = CompletableFuture.supplyAsync(
                () -> engine.executePendingDispatch(locator, false));
        waitFor(() -> Files.exists(ready), running);

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).isEmpty();
        assertThat(auto.stderr())
                .contains("error: dispatch 'plan-1' is already running")
                .contains("forge run show-progress");
        assertThat(running.get(5, TimeUnit.SECONDS).get("success")).isEqualTo(true);
    }

    @Test
    void execDispatchRecoversStaleActiveExecutionWithoutRerunningCommand() throws Exception {
        Path counter = tempDir.resolve("stale-dispatch-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-execution");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("kind", "process");
        execution.put("operation_id", "plan-1");
        execution.put("operation_kind", "node_dispatch");
        execution.put("started_at", "2026-05-23T13:30:00Z");
        execution.put("latest_activity_at", "2026-05-23T13:30:00Z");
        execution.put("progress_path", dispatchOutputRecordPath("plan-1", ".progress.ndjson"));
        execution.put("stdout_path", dispatchOutputRecordPath("plan-1", ".stdout"));
        execution.put("stderr_path", dispatchOutputRecordPath("plan-1", ".stderr"));
        execution.put("result_path", dispatchOutputRecordPath("plan-1", ".result.json"));
        execution.put("stdout_bytes", 0);
        execution.put("stderr_bytes", 0);
        execution.put("progress_bytes", 0);
        execution.put("progress_entries", 0);
        execution.put("active", true);
        execution.put("pid", Long.MAX_VALUE);
        execution.put("launched", true);
        EventLog.appendEvent(RunPaths.eventsPath(runDir), EventEnvelope.of(
                4,
                "2026-05-23T13:30:00Z",
                "operation_started",
                Map.of("operation_id", "plan-1", "execution", execution)));

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"stale_execution_failed\"")
                .contains("\"success\" : false");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("active").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("success").asBoolean()).isFalse();
        assertThat(EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(EventEnvelope::type)
                .filter("operation_failed"::equals)
                .count()).isEqualTo(1);
    }

    @Test
    void execDispatchFailsStalePrelaunchExecutionWithoutRerunningCommand() throws Exception {
        Path counter = tempDir.resolve("stale-prelaunch-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-prelaunch");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendPrelaunchExecution(runDir, "plan-1");

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"stale_execution_failed\"")
                .contains("\"success\" : false");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("active").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("launched").asBoolean()).isFalse();
        assertThat(EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(EventEnvelope::type)
                .filter("operation_started"::equals)
                .count()).isEqualTo(1);
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isEqualTo(1);
    }

    @Test
    void execDispatchPromotesDurableSuccessfulResultForStaleActiveExecution() throws Exception {
        Path counter = tempDir.resolve("stale-success-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-success-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendStaleExecution(runDir, "plan-1");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        Json.writePretty(resultPath, completedExecutionResult("plan-1", true, 0, null));

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"already_executed\"")
                .contains("\"success\" : true")
                .doesNotContain("stale_execution_failed");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode resultFile = MAPPER.readTree(resultPath.toFile());
        assertThat(resultFile.get("success").asBoolean()).isTrue();
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("active").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("success").asBoolean()).isTrue();
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(EventEnvelope::type)
                .toList();
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isEqualTo(1);
        assertThat(eventTypes.stream().filter("operation_failed"::equals).count()).isZero();
    }

    @Test
    void execDispatchPromotesDurableResultWithRelativeRunsPathAndTeeReplay() throws Exception {
        Path counter = tempDir.resolve("stale-relative-count.txt");
        Path spec = writeCommandSpec("""
                count=0
                test -f '%s' && count=$(cat '%s')
                count=$((count + 1))
                printf '%%s' "$count" > '%s'
                printf 'recorded stdout'
                printf 'recorded stderr' >&2
                """.formatted(counter, counter, counter), "__complete__");
        Path runs = Path.of("target", "runtime-dispatch-test", tempDir.getFileName().toString(),
                "runs-stale-relative-result");
        assertThat(runs.isAbsolute()).isFalse();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        CapturedInvocation first = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(first.exitCode()).isZero();
        assertThat(first.stdout()).contains("\"status\" : \"executed\"", "\"success\" : true");
        assertThat(Files.readString(counter)).isEqualTo("1");
        removeTerminalExecutionEvent(runDir, "plan-1");

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample", "--tee"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"already_executed\"")
                .contains("\"success\" : true")
                .doesNotContain("stale_execution_failed");
        assertThat(recovered.stderr()).contains("recorded stdout", "recorded stderr");
        assertThat(Files.readString(counter)).isEqualTo("1");
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("result_path").asText())
                .isEqualTo("dispatch/output/plan-1.result.json");
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(EventEnvelope::type)
                .toList();
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isEqualTo(1);
        assertThat(eventTypes.stream().filter("operation_failed"::equals).count()).isZero();
    }

    @Test
    void execDispatchUsesAbsoluteRunOwnedEnvPathsWithRelativeRunsAndRequestCwd() throws Exception {
        Path repo = tempDir.resolve("repo-cwd-env");
        Files.createDirectories(repo);
        Path spec = writeRequestCwdEnvSpec();
        Path request = tempDir.resolve("request-cwd-env.json");
        Files.writeString(request, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = Path.of("target", "runtime-dispatch-test", tempDir.getFileName().toString(),
                "runs-relative-cwd-env");
        assertThat(runs.isAbsolute()).isFalse();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path absoluteRunDir = runDir.toAbsolutePath().normalize();
        var operation = loadDerivedState(RunPaths.eventsPath(runDir)).pendingOperation();
        assertThat(operation).isNotNull();
        JsonNode payload = operation.payload();
        assertThat(payload.get("cwd").asText()).isEqualTo(repo.toAbsolutePath().toString());
        assertThat(Path.of(payload.get("env").get("FORGE_RUN_DIR").asText())).isAbsolute();
        assertThat(payload.get("env").get("FORGE_RUN_DIR").asText()).isEqualTo(absoluteRunDir.toString());
        assertThat(payload.get("env").get("FORGE_INPUT_REQUEST").asText())
                .isEqualTo(absoluteRunDir.resolve("artifacts/__canonical/request.json").toString());
        assertThat(payload.get("env").get("FORGE_OUTPUT_REPORT").asText())
                .isEqualTo(absoluteRunDir.resolve("artifacts/__canonical/report.md").toString());
        assertThat(payload.get("env").get("FORGE_PROGRESS_FILE").asText())
                .isEqualTo(absoluteRunDir.resolve("dispatch/output/plan-1.progress.ndjson").toString());
        assertThat(payload.get("input_paths").get(0).asText())
                .isEqualTo(absoluteRunDir.resolve("artifacts/__canonical/request.json").toString());
        assertThat(payload.get("output_paths").get(0).asText())
                .isEqualTo(absoluteRunDir.resolve("artifacts/__canonical/report.md").toString());

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isZero();
        Path report = absoluteRunDir.resolve("artifacts/__canonical/report.md");
        assertThat(Files.readString(report))
                .contains("cwd=" + repo.toAbsolutePath())
                .contains("run=" + absoluteRunDir)
                .contains("input=" + absoluteRunDir.resolve("artifacts/__canonical/request.json"))
                .contains("output=" + report)
                .contains("progress=" + absoluteRunDir.resolve("dispatch/output/plan-1.progress.ndjson"));
        assertThat(Files.readString(absoluteRunDir.resolve("dispatch/output/plan-1.progress.ndjson")))
                .contains("\"phase\":\"cwd-env\"");
        assertThat(Files.exists(repo.resolve(runs).resolve("sample"))).isFalse();
    }

    @Test
    void execDispatchRejectsRelativePreparedRunOwnedPathsBeforeLaunch() throws Exception {
        Path repo = tempDir.resolve("repo-stale-prepared");
        Files.createDirectories(repo);
        Path spec = writeRequestCwdLaunchMarkerSpec();
        Path request = tempDir.resolve("request-stale-prepared.json");
        Files.writeString(request, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = Path.of("target", "runtime-dispatch-test", tempDir.getFileName().toString(),
                "runs-stale-prepared-payload");
        assertThat(runs.isAbsolute()).isFalse();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> {
            ObjectNode env = (ObjectNode) dispatch.get("env");
            env.put("FORGE_RUN_DIR", runDir.toString());
            env.put("FORGE_PROGRESS_FILE", runDir.resolve("dispatch/output/plan-1.progress.ndjson").toString());
            env.put("FORGE_INPUT_REQUEST", runDir.resolve("artifacts/__canonical/request.json").toString());
            env.put("FORGE_OUTPUT_REPORT", runDir.resolve("artifacts/__canonical/report.md").toString());
            dispatch.set("input_paths", MAPPER.createArrayNode()
                    .add(runDir.resolve("artifacts/__canonical/request.json").toString()));
            dispatch.set("output_paths", MAPPER.createArrayNode()
                    .add(runDir.resolve("artifacts/__canonical/report.md").toString()));
        });

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isEqualTo(1);
        assertThat(exec.stdout()).isEmpty();
        assertThat(exec.stderr()).contains("prepared dispatch", "FORGE_RUN_DIR", "absolute");
        assertThat(Files.exists(repo.resolve("launched.txt"))).isFalse();
        assertThat(Files.exists(repo.resolve(runs).resolve("sample").resolve("artifacts/__canonical/report.md"))).isFalse();
        assertThat(Files.exists(repo.resolve(runs).resolve("sample").resolve("dispatch/output/plan-1.progress.ndjson"))).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchRejectsSymlinkedPreparedRunOwnedPathsBeforeLaunch() throws Exception {
        Path repo = tempDir.resolve("repo-symlinked-prepared");
        Files.createDirectories(repo);
        Path spec = writeRequestCwdLaunchMarkerSpec();
        Path request = tempDir.resolve("request-symlinked-prepared.json");
        Files.writeString(request, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = tempDir.resolve("runs-symlinked-prepared-payload");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path external = tempDir.resolve("outside-prepared-output");
        Files.createDirectories(external);
        Files.createSymbolicLink(runDir.resolve("escape"), external);
        Path escapedReport = runDir.resolve("escape/report.md");
        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> {
            ObjectNode env = (ObjectNode) dispatch.get("env");
            env.put("FORGE_OUTPUT_REPORT", escapedReport.toString());
            dispatch.set("output_paths", MAPPER.createArrayNode().add(escapedReport.toString()));
        });

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isEqualTo(1);
        assertThat(exec.stdout()).isEmpty();
        assertThat(exec.stderr()).contains("prepared dispatch", "FORGE_OUTPUT_REPORT", "symlink");
        assertThat(Files.exists(repo.resolve("launched.txt"))).isFalse();
        assertThat(Files.exists(external.resolve("report.md"))).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchRejectsPreparedRunOwnedPathsThatNormalizeAcrossSymlinks() throws Exception {
        Path repo = tempDir.resolve("repo-normalized-symlinked-prepared");
        Files.createDirectories(repo);
        Path spec = writeRequestCwdLaunchMarkerSpec();
        Path request = tempDir.resolve("request-normalized-symlinked-prepared.json");
        Files.writeString(request, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = tempDir.resolve("runs-normalized-symlinked-prepared-payload");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path external = tempDir.resolve("outside-normalized-prepared-output");
        Path symlinkTarget = external.resolve("child");
        Files.createDirectories(symlinkTarget);
        Files.createSymbolicLink(runDir.resolve("escape"), symlinkTarget);
        Path escapedReport = runDir.resolve("escape/../report.md");
        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> {
            ObjectNode env = (ObjectNode) dispatch.get("env");
            env.put("FORGE_OUTPUT_REPORT", escapedReport.toString());
            dispatch.set("output_paths", MAPPER.createArrayNode().add(escapedReport.toString()));
        });

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isEqualTo(1);
        assertThat(exec.stdout()).isEmpty();
        assertThat(exec.stderr()).contains("prepared dispatch", "FORGE_OUTPUT_REPORT", "normalized");
        assertThat(Files.exists(repo.resolve("launched.txt"))).isFalse();
        assertThat(Files.exists(external.resolve("report.md"))).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchRejectsMalformedPreparedTimeoutBeforeLaunch() throws Exception {
        Path marker = tempDir.resolve("timeout-launched.txt");
        Path spec = writeTimedCommandSpec("touch " + shellQuote(marker) + "; printf ok", 1000);
        Path runs = tempDir.resolve("runs-malformed-prepared-timeout");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");

        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> dispatch.put("timeout_ms", "1000"));
        CapturedInvocation textual = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(textual.exitCode()).isEqualTo(1);
        assertThat(textual.stdout()).isEmpty();
        assertThat(textual.stderr()).contains("timeout_ms", "non-negative integer");
        assertThat(Files.exists(marker)).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();

        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> dispatch.put("timeout_ms", 0.5d));
        CapturedInvocation decimal = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(decimal.exitCode()).isEqualTo(1);
        assertThat(decimal.stdout()).isEmpty();
        assertThat(decimal.stderr()).contains("timeout_ms", "non-negative integer");
        assertThat(Files.exists(marker)).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();

        mutatePreparedDispatchPayload(runDir, "plan-1", dispatch -> dispatch.put("timeout_ms", -1));
        CapturedInvocation negative = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(negative.exitCode()).isEqualTo(1);
        assertThat(negative.stdout()).isEmpty();
        assertThat(negative.stderr()).contains("timeout_ms", "non-negative integer");
        assertThat(Files.exists(marker)).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isZero();
    }

    @Test
    void execDispatchFailsStaleActiveExecutionWhenDurableResultPayloadIsInvalid() throws Exception {
        Path counter = tempDir.resolve("stale-invalid-result-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-invalid-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendStaleExecution(runDir, "plan-1");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");

        ObjectNode invalidExitCode = MAPPER.valueToTree(completedExecutionResult("plan-1", true, 0, null));
        invalidExitCode.put("exit_code", 0.5d);
        Json.writePretty(resultPath, invalidExitCode);

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"stale_execution_failed\"")
                .contains("\"success\" : false");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode recoveredResult = MAPPER.readTree(resultPath.toFile());
        assertThat(recoveredResult.get("status").asText()).isEqualTo("stale_execution_failed");
        assertThat(recoveredResult.get("success").asBoolean()).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isEqualTo(1);
    }

    @Test
    void execDispatchFailsStaleActiveExecutionWhenResultSidecarIsPartialJson() throws Exception {
        Path counter = tempDir.resolve("stale-partial-result-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-partial-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendStaleExecution(runDir, "plan-1");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, "{\"kind\":\"process\"", StandardCharsets.UTF_8);

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"stale_execution_failed\"")
                .contains("\"success\" : false");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode recoveredResult = MAPPER.readTree(resultPath.toFile());
        assertThat(recoveredResult.get("status").asText()).isEqualTo("stale_execution_failed");
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("active").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("success").asBoolean()).isFalse();
        assertThat(terminalExecutionEventCount(runDir, "plan-1")).isEqualTo(1);
    }

    @Test
    void execDispatchPromotesDurableFailedResultForStaleActiveExecution() throws Exception {
        Path counter = tempDir.resolve("stale-failure-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-stale-failure-result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendStaleExecution(runDir, "plan-1");
        Path resultPath = RunPaths.dispatchOutputDir(runDir).resolve("plan-1.result.json");
        Json.writePretty(resultPath, completedExecutionResult("plan-1", false, 7, "durable failure"));

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout())
                .contains("\"status\" : \"already_executed\"")
                .contains("\"success\" : false")
                .contains("durable failure")
                .doesNotContain("stale_execution_failed");
        assertThat(Files.exists(counter)).isFalse();
        JsonNode resultFile = MAPPER.readTree(resultPath.toFile());
        assertThat(resultFile.get("exit_code").asInt()).isEqualTo(7);
        assertThat(resultFile.get("failure_reason").asText()).isEqualTo("durable failure");
        JsonNode recoveredExecution = loadDerivedState(RunPaths.eventsPath(runDir))
                .operationExecutions()
                .get("plan-1");
        assertThat(recoveredExecution.get("active").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("success").asBoolean()).isFalse();
        assertThat(recoveredExecution.get("exit_code").asInt()).isEqualTo(7);
        assertThat(recoveredExecution.get("failure_reason").asText()).isEqualTo("durable failure");
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(EventEnvelope::type)
                .toList();
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isZero();
        assertThat(eventTypes.stream().filter("operation_failed"::equals).count()).isEqualTo(1);
    }

    @Test
    void autoUsesRecordedStaleExecutionFailureReason() throws Exception {
        Path counter = tempDir.resolve("stale-auto-count.txt");
        Path spec = writeCommandSpec("printf run > " + shellQuote(counter), "__complete__");
        Path runs = tempDir.resolve("runs-auto-stale-execution");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendStaleExecution(runDir, "plan-1");

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("dispatch execution became inactive before completion");
        assertThat(Files.exists(counter)).isFalse();
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).escalationReason())
                .contains("dispatch execution became inactive before completion");
    }

    @Test
    void dispatchProgressFileUpdatesPersistHeartbeatAndProgressCounts() throws Exception {
        Path spec = writeCommandSpec("printf '{\"phase\":\"started\"}\\n' > \"$FORGE_PROGRESS_FILE\"; printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-progress-heartbeat");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(exec.exitCode()).isZero();
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(event -> event.type())
                .toList();
        assertThat(eventTypes).containsSubsequence(
                "operation_started",
                "operation_heartbeat",
                "operation_succeeded");
        JsonNode execution = loadDerivedState(RunPaths.eventsPath(runDir)).operationExecutions().get("plan-1");
        assertThat(execution.get("progress_entries").asLong()).isEqualTo(1);
        assertThat(execution.get("progress_bytes").asLong()).isGreaterThan(0);
        assertThat(execution.get("last_heartbeat_at").asText()).isNotBlank();
        assertThat(execution.get("latest_progress_at").asText()).isNotBlank();

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(progress.exitCode()).isZero();
        assertThat(progress.stdout())
                .contains("\"pending_execution\" : {")
                .contains("\"progress_entries\" : 1");
    }

    @Test
    void dispatchProgressIsDurableBeforeProcessExits() throws Exception {
        Path ready = tempDir.resolve("progress-ready");
        Path spec = writeCommandSpec("printf '{\"phase\":\"started\"}\\n' > \"$FORGE_PROGRESS_FILE\"; "
                + "touch " + shellQuote(ready) + "; sleep 2; printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-live-progress-heartbeat");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        RuntimeEngine engine = new RuntimeEngine();
        RuntimeEngine.Locator locator = new RuntimeEngine.Locator(runs, RunSlug.parse("sample"));
        CompletableFuture<Map<String, Object>> running = CompletableFuture.supplyAsync(
                () -> engine.executePendingDispatch(locator, false));
        Path eventsPath = RunPaths.eventsPath(runs.resolve("sample"));

        waitFor(() -> Files.exists(ready), running);
        waitFor(() -> {
            JsonNode execution = loadDerivedState(eventsPath).operationExecutions().get("plan-1");
            return execution != null
                    && execution.path("active").asBoolean(false)
                    && execution.path("progress_entries").asLong() == 1;
        }, running);

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(progress.exitCode()).isZero();
        assertThat(progress.stdout())
                .contains("\"pending_execution\" : {")
                .contains("\"active\" : true")
                .contains("\"progress_entries\" : 1");
        assertThat(running.get(5, TimeUnit.SECONDS).get("success")).isEqualTo(true);
    }

    @Test
    void progressFileCursorReadsOnlyAppendedBytes() {
        RecordingProgressSource source = new RecordingProgressSource();
        DispatchProgressCursor cursor = new DispatchProgressCursor(source);
        source.replace("{\"phase\":\"one\"}\n\n{\"phase\":\"two\"}");

        DispatchProgressCursor.ProgressStats first = cursor.refresh();
        int firstLength = source.length();
        DispatchProgressCursor.ProgressStats unchanged = cursor.refresh();
        source.append("\n{\"phase\":\"three\"}\n");
        DispatchProgressCursor.ProgressStats second = cursor.refresh();
        source.replace("{\"phase\":\"reset\"}\n");
        DispatchProgressCursor.ProgressStats reset = cursor.refresh();

        assertThat(first.bytes()).isEqualTo(firstLength);
        assertThat(first.entries()).isEqualTo(2);
        assertThat(unchanged).isEqualTo(first);
        assertThat(second.entries()).isEqualTo(3);
        assertThat(reset.entries()).isEqualTo(1);
        assertThat(source.readOffsets()).containsExactly(0L, (long) firstLength, 0L);
    }

    @Test
    void failDispatchRequiresRecordedExecutionAndEscalatesNonRetryableFailure() throws Exception {
        Path spec = writeCommandSpec("exit 7", "__complete__");
        Path runs = tempDir.resolve("runs-fail");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation missingExecution = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=nope",
                "--retryable=false"
        }));
        assertThat(missingExecution.exitCode()).isEqualTo(1);
        assertThat(missingExecution.stderr()).contains("no recorded execution result");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation failed = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=nope",
                "--retryable=false"
        }));

        assertThat(failed.exitCode()).isZero();
        assertThat(failed.stdout())
                .contains("\"status\" : \"dispatch_failed_escalated\"")
                .contains("\"node_id\" : \"plan\"")
                .contains("\"attempt\" : 1")
                .contains("\"max_attempts\" : 1");
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(state.escalationReason()).contains("failed without retry", "nope");
    }

    @Test
    void dispatchLaunchFailureRecordsFailedExecutionBeforeFailureCompletion() throws Exception {
        Path missingCommand = tempDir.resolve("missing-forge-command");
        Path spec = writeDirectCommandSpec(List.of(missingCommand.toString()), "__complete__");
        Path runs = tempDir.resolve("runs-launch-failure");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isZero();
        assertThat(exec.stdout()).contains("\"success\" : false", "failed to launch process");
        Path runDir = runs.resolve("sample");
        JsonNode execution = loadDerivedState(RunPaths.eventsPath(runDir)).operationExecutions().get("plan-1");
        assertThat(execution.get("active").asBoolean()).isFalse();
        assertThat(execution.get("launched").asBoolean()).isFalse();
        assertThat(execution.get("success").asBoolean()).isFalse();
        assertThat(execution.get("failure_reason").asText()).contains("failed to launch process");

        CapturedInvocation failed = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=launch failed",
                "--retryable=false"
        }));
        assertThat(failed.exitCode()).isZero();
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus()).isEqualTo(RunStatus.ESCALATED);
    }

    @Test
    void autoUsesLaunchFailureReasonWhenDispatchCannotStart() throws Exception {
        Path missingCommand = tempDir.resolve("missing-forge-auto-command");
        Path spec = writeDirectCommandSpec(List.of(missingCommand.toString()), "__complete__");
        Path runs = tempDir.resolve("runs-auto-launch-failure");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("failed to launch process");
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).escalationReason())
                .contains("failed to launch process");
    }

    @Test
    void failDispatchRetriesSameNodeWhenAttemptsRemain() throws Exception {
        Path spec = writeRetryCommandSpec("exit 7", 2);
        Path runs = tempDir.resolve("runs-fail-retry");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation failed = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=transient",
                "--retryable=true"
        }));

        assertThat(failed.exitCode()).isZero();
        assertThat(failed.stdout())
                .contains("\"status\" : \"dispatch_failed_retrying\"")
                .contains("\"attempt\" : 1")
                .contains("\"max_attempts\" : 2");
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(state.currentNode()).isEqualTo("plan");
        assertThat(state.nodeVisitCounts()).containsEntry("plan", 2L);
        assertThat(state.pendingOperation()).isNull();

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(next.exitCode()).isZero();
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).pendingOperation())
                .extracting(operation -> operation.operationId())
                .isEqualTo("plan-2");
    }

    @Test
    void retryableFailureEscalatesAfterAttemptsAreExhausted() throws Exception {
        Path spec = writeRetryCommandSpec("exit 7", 1);
        Path runs = tempDir.resolve("runs-fail-retry-exhausted");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation failed = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=still-broken",
                "--retryable=true"
        }));

        assertThat(failed.exitCode()).isZero();
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(state.escalationReason()).contains("failed after 1 attempt(s)", "still-broken");
    }

    @Test
    void initCopiesSeedArtifactsRejectsDuplicatesAndAllowsForce() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path request = tempDir.resolve("request.json");
        Files.writeString(request, "{\"goal\":\"test\"}\n");
        Path runs = tempDir.resolve("runs-artifact");

        CapturedInvocation init = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        }));
        assertThat(init.exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        var state = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(state.artifactIndex()).containsKey("request");
        assertThat(state.artifactMetadata().get("request").role()).isEqualTo(ArtifactRole.REQUEST);
        assertThat(Files.exists(runDir.resolve("artifacts/__canonical/request.json"))).isTrue();
        JsonNode workflowIr = MAPPER.readTree(RunPaths.workflowIrPath(runDir).toFile());
        assertThat(workflowIr.get("spec_version").asInt()).isEqualTo(2);
        assertThat(workflowIr.get("source_spec_sha256").asText()).hasSize(64);
        assertThat(workflowIr.at("/nodes/plan/route/mode").asText()).isEqualTo("always");
        JsonNode workflowInterface = MAPPER.readTree(RunPaths.workflowInterfacePath(runDir).toFile());
        assertThat(workflowInterface.get("workflow_id").asText()).isEqualTo("runtime_test");
        assertThat(workflowInterface.get("inputs").isObject()).isTrue();

        CapturedInvocation duplicate = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample"
        }));
        assertThat(duplicate.exitCode()).isEqualTo(1);
        assertThat(duplicate.stderr()).contains("run already exists");

        CapturedInvocation forced = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--force"
        }));
        assertThat(forced.exitCode()).isZero();
    }

    @Test
    void initStagesOutputBeforePublishingRunDirectory() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-staged-init");
        Path missing = tempDir.resolve("missing-request.json");

        CapturedInvocation failed = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + missing
        }));

        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.stderr()).contains("failed to copy artifact");
        assertThat(Files.exists(runs.resolve("sample"))).isFalse();
        assertThat(Files.exists(RunPaths.stagingDir(runs, RunSlug.parse("sample")))).isFalse();

        Path request = tempDir.resolve("request-after-failure.json");
        Files.writeString(request, "{\"goal\":\"retry\"}\n");
        CapturedInvocation retried = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        }));
        assertThat(retried.exitCode()).isZero();
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).artifactIndex())
                .containsKey("request");
    }

    @Test
    void initMarksSingleInterfaceInputAsRequestArtifact() throws Exception {
        Path spec = writeSingleInterfaceRequestCwdSpec();
        Path repo = tempDir.resolve("repo-root-interface");
        Files.createDirectories(repo);
        Path packet = tempDir.resolve("task-packet.json");
        Files.writeString(packet, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = tempDir.resolve("runs-interface-request");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=task_packet=" + packet
        })).exitCode()).isZero();

        Path runDir = runs.resolve("sample");
        var initialized = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(initialized.artifactMetadata().get("task_packet").role()).isEqualTo(ArtifactRole.REQUEST);

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(next.exitCode()).isZero();
        var operation = loadDerivedState(RunPaths.eventsPath(runDir)).pendingOperation();
        assertThat(operation).isNotNull();
        assertThat(operation.payload().get("cwd").asText()).isEqualTo(repo.toAbsolutePath().toString());
        assertThat(operation.payload().get("env").get("FORGE_INPUT_TASK_PACKET").asText())
                .isEqualTo(runDir.resolve("artifacts/__canonical/task_packet.json").toString());
    }

    @Test
    void completeDispatchCanRouteToNextNode() {
        Path spec = writeTwoNodeSpec();
        Path runs = tempDir.resolve("runs-route");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=plan-1"
        }));

        assertThat(complete.exitCode()).isZero();
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.currentNode()).isEqualTo("finalize");
        assertThat(state.runStatus()).isEqualTo(RunStatus.RUNNING);
        CapturedInvocation status = capture(() -> Main.exitCode(new String[]{
                "run", "status", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(status.stdout()).contains("Dispatch: finalize-1");
    }

    @Test
    void execDispatchRecordsNonZeroResultsAndTeeOutput() throws Exception {
        Path spec = writeCommandSpec("printf bad; printf err >&2; exit 7", "__complete__");
        Path runs = tempDir.resolve("runs-nonzero");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample", "--tee"
        }));

        assertThat(exec.exitCode()).isZero();
        assertThat(exec.stdout()).contains("\"success\" : false", "\"exit_code\" : 7");
        assertThat(exec.stderr()).contains("bad", "err");
        Path result = RunPaths.dispatchOutputDir(runs.resolve("sample")).resolve("plan-1.result.json");
        assertThat(Files.readString(result)).contains("\"success\" : false");
    }

    @Test
    void dispatchCompletionAndFailureRequireMatchingProcessResult() throws Exception {
        Path failingSpec = writeCommandSpec("exit 7", "__complete__");
        Path failingRuns = tempDir.resolve("runs-complete-failed-exec");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + failingSpec, "--runs=" + failingRuns, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + failingRuns, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + failingRuns, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation completeFailed = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch",
                "--runs=" + failingRuns,
                "--slug=sample",
                "--dispatch-id=plan-1"
        }));
        assertThat(completeFailed.exitCode()).isEqualTo(1);
        assertThat(completeFailed.stderr()).contains("does not have a successful execution result");

        Path successfulSpec = writeCommandSpec("printf ok", "__complete__");
        Path successfulRuns = tempDir.resolve("runs-fail-successful-exec");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + successfulSpec, "--runs=" + successfulRuns, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + successfulRuns, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + successfulRuns, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation failSuccessful = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + successfulRuns,
                "--slug=sample",
                "--dispatch-id=plan-1",
                "--reason=bad-manual-state",
                "--retryable=false"
        }));
        assertThat(failSuccessful.exitCode()).isEqualTo(1);
        assertThat(failSuccessful.stderr()).contains("does not have a failed execution result");
    }

    @Test
    void execDispatchUsesExecutionLeaseHeartbeatAndTimeoutResult() throws Exception {
        Path spec = writeTimedCommandSpec("sleep 2", 50);
        Path runs = tempDir.resolve("runs-timeout");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(exec.exitCode()).isZero();
        assertThat(exec.stdout()).contains("\"success\" : false", "\"timed_out\" : true");
        assertThat(Files.readString(RunPaths.dispatchExecutionLeasePath(runDir, "plan-1")))
                .contains("forge run exec-dispatch plan-1");
        assertThat(Files.readString(RunPaths.eventsPath(runDir)))
                .contains("\"type\":\"operation_started\"", "\"operation_id\":\"plan-1\"");
    }

    @Test
    void terminalNotificationHooksRunAsDispatchesBeforeCompletion() throws Exception {
        Path hookOutput = tempDir.resolve("complete-hook-output.txt");
        Path hook = tempDir.resolve("complete-hook.sh");
        Files.writeString(hook, """
                #!/bin/sh
                printf '%%s|%%s|%%s|%%s\\n' "$FORGE_LEVEL" "$FORGE_NOTIFICATION_ID" "$FORGE_MESSAGE" "$1" > '%s'
                """.formatted(hookOutput));
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Path spec = writeNotificationCommandSpec("printf ok", "__complete__", "complete_hook", hook);
        Path runs = tempDir.resolve("runs-complete-notification");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        CapturedInvocation completedNode = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        }));
        assertThat(completedNode.exitCode()).isZero();
        assertThat(completedNode.stdout())
                .contains("\"status\" : \"dispatch_completed\"")
                .contains("\"run_status\" : \"waiting_for_agent\"");

        Path runDir = runs.resolve("sample");
        assertThat(EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(event -> event.type())
                .toList())
                .containsSubsequence("transition_taken", "run_completed", "operation_prepared");
        var pendingNotification = loadDerivedState(RunPaths.eventsPath(runDir)).pendingOperation();
        assertThat(pendingNotification).isNotNull();
        assertThat(pendingNotification.kind()).isEqualTo("notification");
        assertThat(pendingNotification.operationId()).isEqualTo("notify-complete");

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(progress.stdout())
                .contains("\"pending_dispatch_id\" : \"notify-complete\"")
                .contains("\"pending_dispatch_kind\" : \"notification\"");

        CapturedInvocation missingNotificationExecution = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=notify-complete"
        }));
        assertThat(missingNotificationExecution.exitCode()).isEqualTo(1);
        assertThat(missingNotificationExecution.stderr()).contains("no recorded execution result");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        CapturedInvocation delivered = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=notify-complete"
        }));
        assertThat(delivered.exitCode()).isZero();
        assertThat(delivered.stdout())
                .contains("\"status\" : \"notification_delivered\"")
                .contains("\"run_status\" : \"completed\"");

        var state = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.deliveredNotifications()).containsEntry("notify-complete", "complete");
        assertThat(Files.readString(hookOutput))
                .contains("COMPLETE|notify-complete|run 'sample' completed|run 'sample' completed");
    }

    @Test
    void initFreezesRelativeNotificationHookPathsAndCwd() throws Exception {
        Path specRoot = tempDir.resolve("relative-notification");
        Path hookOutput = tempDir.resolve("relative-complete-hook-output.txt");
        Path hook = specRoot.resolve("hooks/notify.sh");
        Files.createDirectories(hook.getParent());
        Files.createDirectories(specRoot.resolve("workspace"));
        Files.writeString(hook, """
                #!/bin/sh
                printf '%%s|%%s|%%s\\n' "$PWD" "$0" "$1" > '%s'
                """.formatted(hookOutput));
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Path spec = writeRelativeNotificationCommandSpec(specRoot);
        Path runs = tempDir.resolve("runs-relative-notification");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        JsonNode frozenSpec = MAPPER.readTree(RunPaths.specPath(runDir).toFile());
        assertThat(frozenSpec.at("/notifications/complete_hook/path").asText()).isEqualTo(hook.toString());
        assertThat(frozenSpec.at("/notifications/complete_hook/cwd").asText())
                .isEqualTo(specRoot.resolve("workspace").toString());

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"status\" : \"completed\"");
        assertThat(Files.readString(hookOutput))
                .contains(specRoot.resolve("workspace").toString())
                .contains(hook.toString())
                .contains("run 'sample' completed");
    }

    @Test
    void humanReviewNotificationFailureIsAdvisoryAndThenBlocksForHuman() throws Exception {
        Path hook = tempDir.resolve("human-hook.sh");
        Files.writeString(hook, "#!/bin/sh\nexit 9\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Path spec = writeHumanNotificationSpec(hook);
        Path plan = tempDir.resolve("plan.md");
        Files.writeString(plan, "review me\n");
        Path runs = tempDir.resolve("runs-human-notification");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=plan=" + plan
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        var state = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"status\" : \"human_review\"", "\"node_id\" : \"review\"");
        assertThat(state.runStatus()).isEqualTo(RunStatus.WAITING_FOR_HUMAN);
        assertThat(state.failedNotifications()).containsEntry("notify-human-review-review-1", "human_review");
    }

    @Test
    void terminalNotificationFailureMarksRunFailed() throws Exception {
        Path hook = tempDir.resolve("failing-complete-hook.sh");
        Files.writeString(hook, "#!/bin/sh\nprintf boom >&2\nexit 9\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Path spec = writeNotificationCommandSpec("printf ok", "__complete__", "complete_hook", hook);
        Path runs = tempDir.resolve("runs-complete-notification-failure");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();
        CapturedInvocation missingNotificationFailure = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=notify-complete",
                "--reason=hook failed",
                "--retryable=false"
        }));
        assertThat(missingNotificationFailure.exitCode()).isEqualTo(1);
        assertThat(missingNotificationFailure.stderr()).contains("no recorded execution result");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).stdout()).contains("\"success\" : false");
        CapturedInvocation notificationFailed = capture(() -> Main.exitCode(new String[]{
                "run", "fail-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=notify-complete",
                "--reason=hook failed",
                "--retryable=false"
        }));
        assertThat(notificationFailed.exitCode()).isZero();
        assertThat(notificationFailed.stdout())
                .contains("\"status\" : \"notification_failed\"")
                .contains("\"notification_level\" : \"complete\"");

        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(state.failedNotifications()).containsEntry("notify-complete", "complete");
    }

    @Test
    void defaultNotificationHookCanGateEscalationCompletion() throws Exception {
        Path hookOutput = tempDir.resolve("escalate-hook-output.txt");
        Path hook = tempDir.resolve("default-hook.sh");
        Files.writeString(hook, """
                #!/bin/sh
                printf '%%s|%%s\\n' "$FORGE_LEVEL" "$FORGE_NOTIFICATION_ID" > '%s'
                """.formatted(hookOutput));
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Path spec = writeNotificationCommandSpec("printf ok", "__escalate__", "default_hook", hook);
        Path runs = tempDir.resolve("runs-default-escalate-hook");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=notify-escalate"
        })).exitCode()).isZero();

        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(state.deliveredNotifications()).containsEntry("notify-escalate", "escalate");
        assertThat(Files.readString(hookOutput)).contains("ESCALATE|notify-escalate");
    }

    @Test
    void dispatchExecutionLeaseContentionReportsAlreadyRunning() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-dispatch-lease-contention");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        try (AdvisoryLock ignored = AdvisoryLock.tryAcquire(
                RunPaths.dispatchExecutionLeasePath(runDir, "plan-1"),
                "test lease").orElseThrow()) {
            CapturedInvocation blocked = capture(() -> Main.exitCode(new String[]{
                    "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
            }));
            assertThat(blocked.exitCode()).isZero();
            assertThat(blocked.stdout())
                    .contains("\"status\" : \"already_running\"")
                    .contains("\"active\" : true");
        }
    }

    @Test
    void dispatchExecutionLeaseContentionWaitsForPublishedPid() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-dispatch-lease-pid-publication");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        appendPrelaunchExecution(runDir, "plan-1");
        long pid = ProcessHandle.current().pid();
        RuntimeEngine engine = new RuntimeEngine();
        RuntimeEngine.Locator locator = new RuntimeEngine.Locator(runs, RunSlug.parse("sample"));
        try (AdvisoryLock ignored = AdvisoryLock.tryAcquire(
                RunPaths.dispatchExecutionLeasePath(runDir, "plan-1"),
                "test lease").orElseThrow()) {
            CountDownLatch duplicateStarted = new CountDownLatch(1);
            CompletableFuture<Map<String, Object>> blocked = CompletableFuture.supplyAsync(() -> {
                duplicateStarted.countDown();
                return engine.executePendingDispatch(locator, false);
            });

            assertThat(duplicateStarted.await(1, TimeUnit.SECONDS)).isTrue();
            sleepQuietly(50);
            assertThat(blocked.isDone()).isFalse();
            appendActiveExecutionHeartbeat(runDir, "plan-1", pid);

            Map<String, Object> payload = blocked.get(1, TimeUnit.SECONDS);
            assertThat(payload).containsEntry("status", "already_running");
            assertThat(payload).containsEntry("active", true);
            assertThat(payload).containsEntry("pid", pid);
        }
    }

    @Test
    void autoFailsWhenDispatchExecutionLeaseIsHeld() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-auto-dispatch-lease-contention");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        try (AdvisoryLock ignored = AdvisoryLock.tryAcquire(
                RunPaths.dispatchExecutionLeasePath(runDir, "plan-1"),
                "test lease").orElseThrow()) {
            CapturedInvocation blocked = capture(() -> Main.exitCode(new String[]{
                    "run", "auto", "--runs=" + runs, "--slug=sample"
            }));
            assertThat(blocked.exitCode()).isEqualTo(1);
            assertThat(blocked.stdout()).isEmpty();
            assertThat(blocked.stderr()).contains("dispatch 'plan-1' is already running");
        }
    }

    @Test
    void structuredContinueLoopRecordsLoopEventsAndExhaustsBudget() throws Exception {
        Path spec = writeStructuredLoopSpec();
        Path runs = tempDir.resolve("runs-structured-loop");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();

        CapturedInvocation firstLoop = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        }));
        assertThat(firstLoop.exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        var afterFirst = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(afterFirst.currentNode()).isEqualTo("plan");
        assertThat(afterFirst.nodeVisitCounts()).containsEntry("plan", 2L);
        assertThat(afterFirst.routeFields()).containsEntry("review.status", "rework");
        assertThat(afterFirst.loopStates()).containsKey("review_loop-1");
        assertThat(afterFirst.loopStates().get("review_loop-1").resolvedBudget()).isEqualTo(1L);
        assertThat(afterFirst.loopStates().get("review_loop-1").iterationCount()).isEqualTo(1L);

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-2"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        })).exitCode()).isZero();

        var exhausted = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(exhausted.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(exhausted.escalationReason()).contains("review loop exhausted");
    }

    @Test
    void structuredContinueLoopCanCompleteWhenBudgetIsExhausted() throws Exception {
        Path spec = writeStructuredCompleteExhaustLoopSpec();
        Path runs = tempDir.resolve("runs-structured-loop-complete-exhaust");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();

        CapturedInvocation firstLoop = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        }));
        assertThat(firstLoop.exitCode()).isZero();

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-2"
        })).exitCode()).isZero();

        CapturedInvocation completed = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        }));
        assertThat(completed.exitCode()).isZero();
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.completedMessage()).contains("review loop completed");
    }

    @Test
    void structuredContinueLoopResolvesArtifactBudgetAndRoutesOnExhaustion() throws Exception {
        Path spec = writeArtifactBudgetLoopSpec();
        Path runs = tempDir.resolve("runs-artifact-budget-loop");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();

        CapturedInvocation exhausted = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        }));

        assertThat(exhausted.exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        var afterExhaustion = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(afterExhaustion.currentNode()).isEqualTo("finalize");
        assertThat(afterExhaustion.runStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(afterExhaustion.routeFields()).containsEntry("review.status", "rework");
        assertThat(afterExhaustion.loopStates()).containsKey("review_loop-1");
        assertThat(afterExhaustion.loopStates().get("review_loop-1").resolvedBudget()).isEqualTo(0L);
        assertThat(afterExhaustion.edgeVisitCounts()).containsEntry("review->finalize", 1L);

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=finalize-1"
        })).exitCode()).isZero();

        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus())
                .isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void structuredContinueLoopAcceptsArtifactBudgetAboveJavaIntRange() throws Exception {
        Path spec = writeArtifactBudgetLoopSpec("2147483648");
        Path runs = tempDir.resolve("runs-large-artifact-budget-loop");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();

        CapturedInvocation continued = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample", "--field=status=rework"
        }));

        assertThat(continued.exitCode()).isZero();
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.currentNode()).isEqualTo("plan");
        assertThat(state.loopStates().get("review_loop-1").resolvedBudget()).isEqualTo(2_147_483_648L);
        assertThat(state.loopStates().get("review_loop-1").iterationCount()).isEqualTo(1);
    }

    @Test
    void dispatchCompletionRejectsMismatchedPendingDispatch() {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-mismatch");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation mismatch = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch",
                "--runs=" + runs,
                "--slug=sample",
                "--dispatch-id=other"
        }));

        assertThat(mismatch.exitCode()).isEqualTo(1);
        assertThat(mismatch.stderr()).contains("pending dispatch is 'plan-1' not 'other'");
    }

    @Test
    void judgeDispatchExtractsOutputDecisionAndRoutes() throws Exception {
        Path spec = writeJudgeSpec("accept");
        Path runs = tempDir.resolve("runs-judge");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(next.exitCode()).isZero();
        assertThat(next.stdout()).contains("\"action\" : \"dispatch\"");

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(exec.exitCode()).isZero();
        assertThat(exec.stdout()).contains("\"success\" : true");

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=judge-1"
        }));
        assertThat(complete.exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        var state = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.decisions()).containsEntry("decision", "accept");
        assertThat(state.artifactIndex()).containsKey("judge_result");
        assertThat(Files.readString(runDir.resolve("artifacts/__canonical/judge_result.json")))
                .contains("\"decision\":\"accept\"");
    }

    @Test
    void judgeDispatchCompletionRejectsInvalidOutputDecision() {
        Path spec = writeJudgeSpec("hold");
        Path runs = tempDir.resolve("runs-judge-invalid");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=judge-1"
        }));

        assertThat(complete.exitCode()).isEqualTo(1);
        assertThat(complete.stderr()).contains("produced invalid value 'hold'");
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_AGENT);
    }

    @Test
    void commandDispatchReceivesArtifactDecisionAndProgressEnvironment() throws Exception {
        Path spec = writeCommandEnvSpec();
        Path request = tempDir.resolve("request-env.json");
        Files.writeString(request, "{\"goal\":\"env\"}\n");
        Path runs = tempDir.resolve("runs-command-env");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample",
                "--field=status=approved"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isZero();
        Path report = runs.resolve("sample").resolve("artifacts/__canonical/report.md");
        String envReport = Files.readString(report);
        assertThat(envReport)
                .contains("input=" + runs.resolve("sample").resolve("artifacts/__canonical/request.json"))
                .contains("output=" + report)
                .contains("progress=" + RunPaths.dispatchOutputDir(runs.resolve("sample"))
                        .resolve("build-1.progress.ndjson"))
                .contains("decision=approved")
                .contains("node=build");

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=build-1"
        }));
        assertThat(complete.exitCode()).isZero();
        assertThat(EventLog.readEvents(RunPaths.eventsPath(runs.resolve("sample"))).stream()
                .map(event -> event.type())
                .toList())
                .containsSubsequence(
                        "operation_succeeded",
                        "operation_completed",
                        "artifact_written",
                        "transition_taken",
                        "run_completed",
                        "artifact_metadata_recorded");
    }

    @Test
    void dispatchCompletionRejectsMissingDeclaredOutputArtifact() throws Exception {
        Path spec = writeOutputCommandSpec("printf no-output");
        Path runs = tempDir.resolve("runs-missing-output");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation complete = capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(complete.exitCode()).isEqualTo(1);
        assertThat(complete.stderr()).contains("output artifact 'report'", "was not produced");
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus()).isEqualTo(RunStatus.WAITING_FOR_AGENT);
        assertThat(EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(event -> event.type())
                .toList())
                .doesNotContain("operation_completed");
    }

    @Test
    void autoRoutesCompletionRejectionThroughRetryPolicy() throws Exception {
        Path spec = writeOutputCommandSpec("printf no-output", 2);
        Path runs = tempDir.resolve("runs-auto-missing-output");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        Path runDir = runs.resolve("sample");
        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("error:");
        var state = loadDerivedState(RunPaths.eventsPath(runDir));
        assertThat(state.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(state.nodeVisitCounts()).containsEntry("plan", 2L);
        assertThat(state.escalationReason())
                .contains("failed after 2 attempt(s)")
                .contains("dispatch completion rejected")
                .contains("output artifact 'report'");
        List<String> eventTypes = EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .map(event -> event.type())
                .toList();
        assertThat(eventTypes.stream().filter("operation_succeeded"::equals).count()).isEqualTo(2);
        assertThat(eventTypes.stream().filter("node_failed"::equals).count()).isEqualTo(2);
    }

    @Test
    void autoReturnsErrorForFailedTerminalRun() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-auto-failed-terminal");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        long seq = loadDerivedState(RunPaths.eventsPath(runDir)).lastEventSeq() + 1L;
        EventLog.appendEvent(RunPaths.eventsPath(runDir), EventEnvelope.of(
                seq,
                "2026-05-23T14:58:00Z",
                "node_failed",
                Map.of("node_id", "plan", "reason", "manual failure", "retryable", false)));

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"failed\"", "\"reason\" : \"manual failure\"");
        assertThat(auto.stderr()).contains("error: manual failure");
    }

    @Test
    void agentDispatchMaterializesPromptStdinAndCodexRequestConfig() throws Exception {
        Path spec = writeAgentPromptSpec("stdin_file");
        Path request = tempDir.resolve("request-agent.json");
        Files.writeString(request, """
                {
                  "repo_root": "%s",
                  "workflow_config": {
                    "codex": {
                      "model": "gpt-5.5",
                      "reasoning_effort": "high"
                    }
                  }
                }
                """.formatted(tempDir.toAbsolutePath()));
        Path runs = tempDir.resolve("runs-agent-prompt");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(next.exitCode()).isZero();

        Path runDir = runs.resolve("sample");
        var operation = loadDerivedState(RunPaths.eventsPath(runDir)).pendingOperation();
        assertThat(operation).isNotNull();
        assertThat(commandValues(operation.payload().get("command")))
                .containsExactly("sh", "-c",
                        "cat > \"$FORGE_OUTPUT_REPORT\"; printf '%s\\n' \"$0 $@\" > \"$FORGE_OUTPUT_ARGV\"; printf '%s\\n' \"$FORGE_PROMPT_FILE\" > \"$FORGE_OUTPUT_PROMPT_PATH\"",
                        "codex", "exec", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"high\"");
        String promptPath = operation.payload().get("stdin_path").asText();
        assertThat(promptPath).endsWith("dispatch/input/write-1-prompt.md");
        assertThat(operation.payload().get("input_paths").get(0).asText()).isEqualTo(promptPath);
        assertThat(operation.payload().get("env").get("FORGE_INPUT_REQUEST").asText())
                .isEqualTo(runDir.resolve("artifacts/__canonical/request.json").toString());
        assertThat(Files.readString(Path.of(promptPath)))
                .contains("Write the final report.")
                .contains("# Inputs")
                .contains("--- REQUEST ---")
                .contains("workflow_config")
                .contains("# Output Paths");

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isZero();
        assertThat(Files.readString(runDir.resolve("artifacts/__canonical/report.md")))
                .contains("Write the final report.", "# Output Paths");
        assertThat(Files.readString(runDir.resolve("artifacts/__canonical/argv.txt")))
                .contains("codex", "exec", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"high\"");
        assertThat(Files.readString(runDir.resolve("artifacts/__canonical/prompt_path.txt")).trim())
                .isEqualTo(promptPath);
    }

    @Test
    void agentDispatchSupportsArgvPromptDeliveryAndRequestRepoRootCwd() throws Exception {
        Path repo = tempDir.resolve("repo-root");
        Files.createDirectories(repo);
        Path spec = writeArgvPromptCwdSpec();
        Path request = tempDir.resolve("request-cwd.json");
        Files.writeString(request, "{\"repo_root\":\"" + repo.toAbsolutePath() + "\"}\n");
        Path runs = tempDir.resolve("runs-agent-argv");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        Path runDir = runs.resolve("sample");
        var operation = loadDerivedState(RunPaths.eventsPath(runDir)).pendingOperation();
        assertThat(operation).isNotNull();
        assertThat(operation.payload().get("cwd").asText()).isEqualTo(repo.toAbsolutePath().toString());
        assertThat(operation.payload().has("stdin_path")).isFalse();
        assertThat(operation.payload().get("command").get(operation.payload().get("command").size() - 1).asText())
                .endsWith("dispatch/input/write-1-prompt.md");

        CapturedInvocation exec = capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(exec.exitCode()).isZero();
        assertThat(Files.readString(runDir.resolve("artifacts/__canonical/cwd.txt")).trim())
                .isEqualTo(repo.toAbsolutePath().toString());
    }

    @Test
    void stateChangingRunCommandsRefreshMutationLockMetadata() throws Exception {
        Path spec = writeCommandSpec("printf locked", "__complete__");
        Path runs = tempDir.resolve("runs-mutation-lock");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path mutationLock = RunPaths.mutationLockPath(runDir);
        assertThat(Files.readString(mutationLock)).contains("\"purpose\" : \"forge run mutation\"");

        Files.delete(mutationLock);
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(Files.readString(mutationLock)).contains("\"purpose\" : \"forge run mutation\"");

        Files.delete(mutationLock);
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "exec-dispatch", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(Files.readString(mutationLock)).contains("\"purpose\" : \"forge run mutation\"");

        Files.delete(mutationLock);
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "complete-dispatch", "--runs=" + runs, "--slug=sample", "--dispatch-id=plan-1"
        })).exitCode()).isZero();
        assertThat(Files.readString(mutationLock)).contains("\"purpose\" : \"forge run mutation\"");
    }

    @Test
    void initAndAutoReportLiveLockContentionWithoutWaiting() throws Exception {
        Path spec = writeCommandSpec("printf locked", "__complete__");
        Path runs = tempDir.resolve("runs-lock-contention");

        try (AdvisoryLock ignored = AdvisoryLock.acquire(
                RunPaths.stagingLockPath(runs, RunSlug.parse("sample")),
                "held init")) {
            CapturedInvocation blockedInit = capture(() -> Main.exitCode(new String[]{
                    "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
            }));
            assertThat(blockedInit.exitCode()).isEqualTo(1);
            assertThat(blockedInit.stderr()).contains("another init is already in progress for slug 'sample'");
        }

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.autoLockPath(runDir), "held auto")) {
            CapturedInvocation blockedAuto = capture(() -> Main.exitCode(new String[]{
                    "run", "auto", "--runs=" + runs, "--slug=sample"
            }));
            assertThat(blockedAuto.exitCode()).isEqualTo(1);
            assertThat(blockedAuto.stderr()).contains("auto runner already active for " + runDir);
        }
    }

    @Test
    void initRejectsMissingToolRequirementsBeforePublishingRunDirectory() throws Exception {
        Path spec = tempDir.resolve("missing-tool-workflow.json");
        Files.writeString(spec, """
                {
                  "version": 2,
                  "tool_requirements": {
                    "all_of": ["missing-forge-test-tool"]
                  },
                  "workflow_id": "missing_tool_workflow",
                  "entry_node": "plan",
                  "artifacts": {
                    "request": {
                      "path": "artifacts/request.json",
                      "media_type": "application/json"
                    }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "inputs": ["request"],
                      "outputs": [],
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        Path request = tempDir.resolve("request.json");
        Files.writeString(request, "{}\n");
        Path runs = tempDir.resolve("runs-missing-tool");

        CapturedInvocation init = capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        }));

        assertThat(init.exitCode()).isEqualTo(1);
        assertThat(init.stderr())
                .contains("workflow tool requirements are not satisfied")
                .contains("missing-forge-test-tool");
        assertThat(runs.resolve("sample")).doesNotExist();
        assertThat(runs.resolve(".staging/sample")).doesNotExist();
    }

    @Test
    void autoRejectsCorruptPrereleaseLockFile() throws Exception {
        Path spec = writeCommandSpec("printf locked", "__complete__");
        Path runs = tempDir.resolve("runs-corrupt-prerelease-auto-lock");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Files.writeString(RunPaths.autoLockPath(runDir), "corrupt prerelease sentinel\n");

        CapturedInvocation corrupt = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(corrupt.exitCode()).isEqualTo(1);
        assertThat(corrupt.stderr()).contains("unsupported or corrupt prerelease auto.lock");
    }

    @Test
    void autoMissingRunDoesNotCreateLockDirectory() {
        Path runs = tempDir.resolve("runs-missing-auto");

        CapturedInvocation missing = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.stderr()).contains("failed to read");
        assertThat(Files.exists(runs.resolve("sample"))).isFalse();
    }

    @Test
    void autoRecoverAndWatchUseRuntimeCommandPaths() throws Exception {
        Path spec = writeCommandSpec("printf auto-out; printf auto-err >&2", "__complete__");
        Path runs = tempDir.resolve("runs-auto");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation recover = capture(() -> Main.exitCode(new String[]{
                "run", "recover", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(recover.exitCode()).isZero();
        assertThat(recover.stdout()).contains("\"status\" : \"no_projection_backlog\"");

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"status\" : \"completed\"");

        Path runDir = runs.resolve("sample");
        assertThat(Files.readString(RunPaths.dispatchOutputDir(runDir).resolve("plan-1.stdout")))
                .isEqualTo("auto-out");
        assertThat(loadDerivedState(RunPaths.eventsPath(runDir)).runStatus())
                .isEqualTo(RunStatus.COMPLETED);

        CapturedInvocation watch = capture(() -> Main.exitCode(new String[]{
                "run", "watch", "--runs=" + runs, "--slug=sample", "--jsonl", "--until-terminal",
                "--interval-ms=1"
        }));
        assertThat(watch.exitCode()).isZero();
        assertThat(watch.stdout()).contains("\"terminal_state\":\"completed\"");
    }

    @Test
    void defaultWatchKeepsRunningUntilInterrupted() throws Exception {
        Path spec = writeCommandSpec("printf watch-out", "__complete__");
        Path runs = tempDir.resolve("runs-default-watch");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation defaultWatch = captureUntilOutput(
                () -> Main.exitCode(new String[]{
                        "run", "watch", "--runs=" + runs, "--slug=sample", "--jsonl", "--interval-ms=1"
                }),
                output -> countOccurrences(output, "\"terminal_state\":\"completed\"") >= 2);

        assertThat(defaultWatch.exitCode()).isEqualTo(1);
        assertThat(defaultWatch.stdout()).contains("\"terminal_state\":\"completed\"");
        assertThat(countOccurrences(defaultWatch.stdout(), "\"terminal_state\":\"completed\"")).isGreaterThanOrEqualTo(2);

        CapturedInvocation untilTerminal = capture(() -> Main.exitCode(new String[]{
                "run", "watch", "--runs=" + runs, "--slug=sample", "--jsonl", "--until-terminal", "--interval-ms=1"
        }));
        assertThat(untilTerminal.exitCode()).isZero();
        assertThat(countOccurrences(untilTerminal.stdout(), "\"terminal_state\":\"completed\"")).isEqualTo(1);
    }

    @Test
    void autoCompletesLongWorkflowPastFormerInternalStepLimit() {
        Path spec = writeLongCommandChainSpec(105);
        Path runs = tempDir.resolve("runs-long-auto");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"status\" : \"completed\"");
        var state = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(state.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.nodeVisitCounts()).containsEntry("step_104", 1L);
    }

    @Test
    void recoverRepublishesProjectionBacklogAndRejectsInvalidRecords() throws Exception {
        Path spec = writeCommandSpec("printf ok", "__complete__");
        Path runs = tempDir.resolve("runs-recover-backlog");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path runDir = runs.resolve("sample");
        Path canonical = runDir.resolve("artifacts/__canonical/report.md");
        Path projection = runDir.resolve("artifacts/report.md");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "# Report\n", StandardCharsets.UTF_8);
        Path record = writeProjectionBacklogRecord(runDir, new ProjectionBacklogEntry(
                1,
                "report",
                canonical.toString(),
                projection.toString()));

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(progress.exitCode()).isZero();
        assertThat(progress.stdout()).contains("\"artifact_name\" : \"report\"");

        CapturedInvocation recovered = capture(() -> Main.exitCode(new String[]{
                "run", "recover", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(recovered.exitCode()).isZero();
        assertThat(recovered.stdout()).contains("\"status\" : \"projection_backlog_recovered\"");
        assertThat(Files.readString(projection)).isEqualTo("# Report\n");
        assertThat(record).doesNotExist();

        CapturedInvocation empty = capture(() -> Main.exitCode(new String[]{
                "run", "recover", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(empty.exitCode()).isZero();
        assertThat(empty.stdout()).contains("\"status\" : \"no_projection_backlog\"");

        Path futureRecord = writeProjectionBacklogRecord(runDir, new ProjectionBacklogEntry(
                99,
                "future",
                canonical.toString(),
                runDir.resolve("artifacts/future.md").toString()));
        CapturedInvocation future = capture(() -> Main.exitCode(new String[]{
                "run", "recover", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(future.exitCode()).isEqualTo(1);
        assertThat(future.stderr()).contains("references uncommitted event seq 99");
        Files.delete(futureRecord);

        Path missingRecord = writeProjectionBacklogRecord(runDir, new ProjectionBacklogEntry(
                1,
                "missing",
                runDir.resolve("artifacts/__canonical/missing.md").toString(),
                runDir.resolve("artifacts/missing.md").toString()));
        CapturedInvocation missing = capture(() -> Main.exitCode(new String[]{
                "run", "recover", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.stderr()).contains("points at missing canonical artifact");
        assertThat(missingRecord).exists();
    }

    @Test
    void showHumanAndResolveHumanAdvanceHumanReviewRoute() {
        Path spec = writeHumanThenCommandSpec();
        Path plan = tempDir.resolve("plan.md");
        writeText(plan, "# Plan\n");
        Path runs = tempDir.resolve("runs-human");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample",
                "--artifact=plan=" + plan
        })).exitCode()).isZero();

        CapturedInvocation projectedHuman = capture(() -> Main.exitCode(new String[]{
                "run", "show-human", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(projectedHuman.exitCode()).isZero();
        assertThat(projectedHuman.stdout()).contains("\"available\" : true");

        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();

        CapturedInvocation human = capture(() -> Main.exitCode(new String[]{
                "run", "show-human", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(human.exitCode()).isZero();
        assertThat(human.stdout()).contains("\"status\" : \"human_review\"");
        assertThat(human.stdout()).contains("\"node_id\" : \"review\"");
        assertThat(human.stdout()).contains("\"field_schema\"");

        CapturedInvocation dryRun = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample",
                "--field=status=approved", "--dry-run"
        }));
        assertThat(dryRun.exitCode()).isZero();
        assertThat(dryRun.stdout()).contains("\"status\" : \"human_resolution_valid\"");
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).currentNode())
                .isEqualTo("review");

        CapturedInvocation resolved = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + runs, "--slug=sample",
                "--field=status=approved"
        }));
        assertThat(resolved.exitCode()).isZero();
        assertThat(resolved.stdout()).contains("\"status\" : \"human_resolved\"");
        List<String> resolveEventTypes = EventLog.readEvents(RunPaths.eventsPath(runs.resolve("sample"))).stream()
                .map(event -> event.type())
                .toList();
        int humanInputIndex = resolveEventTypes.indexOf("human_input_recorded");
        assertThat(humanInputIndex).isGreaterThanOrEqualTo(0);
        assertThat(resolveEventTypes.subList(humanInputIndex, resolveEventTypes.size()))
                .doesNotContain("decision_recorded");
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).decisions())
                .containsEntry("status", "approved");
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).currentNode())
                .isEqualTo("implement");

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample", "--watch=summary"
        }));
        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"action\" : {");
        assertThat(auto.stdout()).doesNotContain("\"last_event_seq\"", "\"projected_action\"", "\"pending_operation\"");
        assertThat(loadDerivedState(RunPaths.eventsPath(runs.resolve("sample"))).runStatus())
                .isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void autoStopsAtPendingHumanAndFailsNonzeroDispatches() {
        Path humanSpec = writeHumanThenCommandSpec();
        Path humanRuns = tempDir.resolve("runs-auto-human");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + humanSpec, "--runs=" + humanRuns, "--slug=sample"
        })).exitCode()).isZero();
        CapturedInvocation humanAuto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + humanRuns, "--slug=sample"
        }));
        assertThat(humanAuto.exitCode()).isZero();
        assertThat(humanAuto.stdout()).contains("\"status\" : \"human_review\"", "\"node_id\" : \"review\"");
        assertThat(loadDerivedState(RunPaths.eventsPath(humanRuns.resolve("sample"))).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_HUMAN);

        CapturedInvocation badField = capture(() -> Main.exitCode(new String[]{
                "run", "resolve-human", "--runs=" + humanRuns, "--slug=sample",
                "--field=status=bad"
        }));
        assertThat(badField.exitCode()).isEqualTo(1);
        assertThat(badField.stderr()).contains("invalid value for human field 'status'");

        Path failingSpec = writeCommandSpec("printf fail; exit 7", "__complete__");
        Path failingRuns = tempDir.resolve("runs-auto-fail");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + failingSpec, "--runs=" + failingRuns, "--slug=sample"
        })).exitCode()).isZero();
        CapturedInvocation failingAuto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + failingRuns, "--slug=sample"
        }));
        assertThat(failingAuto.exitCode()).isEqualTo(1);
        assertThat(failingAuto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(failingAuto.stderr()).contains("error:");
        assertThat(loadDerivedState(RunPaths.eventsPath(failingRuns.resolve("sample"))).runStatus())
                .isEqualTo(RunStatus.ESCALATED);
    }

    @Test
    void autoDrivesSubrunChildAndImportsArtifacts() throws Exception {
        Path childSpec = writeSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("subrun-request.json");
        Files.writeString(request, "{\"goal\":\"delegate\"}\n");
        Path runs = tempDir.resolve("runs-subrun");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();

        CapturedInvocation progress = capture(() -> Main.exitCode(new String[]{
                "run", "show-progress", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(progress.exitCode()).isZero();
        assertThat(progress.stdout()).contains("\"projected_action\" : \"subrun\"");

        CapturedInvocation next = capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(next.exitCode()).isZero();
        Path parentRunDir = runs.resolve("sample");
        var prepared = loadDerivedState(RunPaths.eventsPath(parentRunDir));
        assertThat(prepared.runStatus()).isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(prepared.pendingOperation()).isNotNull();
        JsonNode payload = prepared.pendingOperation().payload();
        assertThat(payload.get("child_slug").asText()).isEqualTo("sample-child-1");
        assertThat(payload.has("workflow_ref")).isFalse();
        assertThat(payload.get("frozen_child_spec_path").asText()).contains("subruns/frozen-workflows");
        assertThat(payload.get("request_artifact").asText()).isEqualTo("request");
        assertThat(payload.get("request_artifact_path").asText()).contains("subruns/children/sample-child-1/prepared");
        assertThat(payload.get("summary_artifact").asText()).isEqualTo("summary");
        assertThat(payload.get("import_artifacts").get("child_report").asText()).isEqualTo("imported_report");
        assertThat(payload.has("required_import_artifacts")).isFalse();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).contains("\"status\" : \"completed\"");
        var finalState = loadDerivedState(RunPaths.eventsPath(parentRunDir));
        assertThat(finalState.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finalState.pendingOperation()).isNull();
        assertThat(finalState.decisions()).containsEntry("subrun_status", "complete");
        assertThat(finalState.artifactIndex()).containsKeys("summary", "imported_report");
        assertThat(finalState.artifactMetadata().get("imported_report").binding())
                .isEqualTo(ArtifactBinding.importedChild(
                        parentRunDir.resolve("subruns/children/sample-child-1").toString(),
                        "child_report"));
        assertThat(Files.readString(parentRunDir.resolve("artifacts/__canonical/summary.md")))
                .contains("Child Run Summary", "sample-child-1", "complete");
        assertThat(Files.readString(parentRunDir.resolve("subruns/children/sample-child-1/artifacts/__canonical/child_report.md")))
                .isEqualTo("child report\n");

        CapturedInvocation secondAuto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));
        assertThat(secondAuto.exitCode()).isZero();
        assertThat(secondAuto.stdout()).contains("\"status\" : \"completed\"");
    }

    @Test
    void autoRejectsPreparedSubrunIdentityMismatch() throws Exception {
        Path childSpec = writeSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("subrun-mismatch-request.json");
        Files.writeString(request, "{\"goal\":\"delegate\"}\n");
        Path runs = tempDir.resolve("runs-subrun-identity-mismatch");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next",
                "--runs=" + runs,
                "--slug=sample"
        })).exitCode()).isZero();
        Path parentRunDir = runs.resolve("sample");

        mutatePreparedSubrunPayload(parentRunDir, "sample-child-1", subrun -> {
            subrun.put("child_slug", "mismatched-child");
        });

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto",
                "--runs=" + runs,
                "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).isEmpty();
        assertThat(auto.stderr()).contains("prepared subrun operation_id", "child_slug", "does not match");
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).decisions())
                .doesNotContainKey("subrun_status");
        assertThat(EventLog.readEvents(RunPaths.eventsPath(parentRunDir)).stream()
                .map(EventEnvelope::type)
                .filter("operation_started"::equals)
                .count()).isZero();
    }

    @Test
    void autoRejectsPreparedSubrunPathOutsideRunBoundary() throws Exception {
        Path childSpec = writeSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("subrun-paths-request.json");
        Files.writeString(request, "{\"goal\":\"delegate\"}\n");
        Path runs = tempDir.resolve("runs-subrun-path-boundary");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next",
                "--runs=" + runs,
                "--slug=sample"
        })).exitCode()).isZero();
        Path parentRunDir = runs.resolve("sample");

        mutatePreparedSubrunPayload(parentRunDir, "sample-child-1", subrun -> {
            subrun.put("frozen_child_spec_path", tempDir.resolve("outside-subrun-workflow.json").toString());
        });

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto",
                "--runs=" + runs,
                "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stderr()).contains("prepared subrun field 'frozen_child_spec_path'");
        assertThat(auto.stderr()).contains("run-relative");
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).decisions())
                .doesNotContainKey("subrun_status");
    }

    @Test
    void autoRejectsPreparedSubrunPathThatContainsSymlinkComponent() throws Exception {
        Path childSpec = writeSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("subrun-symlinked-request.json");
        Files.writeString(request, "{\"goal\":\"delegate\"}\n");
        Path runs = tempDir.resolve("runs-subrun-path-symlink");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next",
                "--runs=" + runs,
                "--slug=sample"
        })).exitCode()).isZero();
        Path parentRunDir = runs.resolve("sample");
        Files.createDirectories(parentRunDir.resolve("subruns/frozen-workflows"));
        Path escapedWorkflow = tempDir.resolve("outside-subrun-workflow");
        Files.createDirectories(escapedWorkflow);
        Files.createSymbolicLink(parentRunDir.resolve("subruns/frozen-workflows/safe-escape"), escapedWorkflow);

        mutatePreparedSubrunPayload(parentRunDir, "sample-child-1", subrun -> {
            subrun.put("frozen_child_spec_path", "subruns/frozen-workflows/safe-escape/spec.json");
            subrun.put("frozen_child_ir_path", "subruns/frozen-workflows/safe-escape/workflow-ir.json");
            subrun.put("frozen_child_interface_path", "subruns/frozen-workflows/safe-escape/workflow-interface.json");
        });

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto",
                "--runs=" + runs,
                "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stderr()).contains("must not include symlink components");
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).decisions())
                .doesNotContainKey("subrun_status");
    }

    @Test
    void autoBlocksWhileSubrunChildWaitsForHumanReview() throws Exception {
        Path childSpec = writeBlockingSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("blocked-subrun-request.json");
        Files.writeString(request, "{\"goal\":\"pause\"}\n");
        Path runs = tempDir.resolve("runs-subrun-blocked");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isZero();
        assertThat(auto.stdout()).isEmpty();
        Path parentRunDir = runs.resolve("sample");
        var parentState = loadDerivedState(RunPaths.eventsPath(parentRunDir));
        assertThat(parentState.runStatus()).isEqualTo(RunStatus.WAITING_FOR_SUBRUN);
        assertThat(parentState.pendingOperation()).isNotNull();
        Path childRunDir = parentRunDir.resolve("subruns/children/sample-child-1");
        assertThat(loadDerivedState(RunPaths.eventsPath(childRunDir)).runStatus())
                .isEqualTo(RunStatus.WAITING_FOR_HUMAN);
        assertThat(EventLog.readEvents(RunPaths.eventsPath(parentRunDir)).stream()
                .filter(event -> "operation_started".equals(event.type()))
                .count()).isEqualTo(1);

        CapturedInvocation secondAuto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(secondAuto.exitCode()).isZero();
        assertThat(secondAuto.stdout()).isEmpty();
        assertThat(EventLog.readEvents(RunPaths.eventsPath(parentRunDir)).stream()
                .filter(event -> "operation_started".equals(event.type()))
                .count()).isEqualTo(1);
    }

    @Test
    void failedSubrunChildRoutesParentThroughFailedStatus() throws Exception {
        Path childSpec = writeSubrunChildSpec("exit 7");
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("failed-subrun-request.json");
        Files.writeString(request, "{\"goal\":\"fail\"}\n");
        Path runs = tempDir.resolve("runs-subrun-failed");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("error:");
        var parentState = loadDerivedState(RunPaths.eventsPath(runs.resolve("sample")));
        assertThat(parentState.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(parentState.decisions()).containsEntry("subrun_status", "escalate");
        assertThat(parentState.pendingOperation()).isNull();
    }

    @Test
    void completedSubrunMissingRequiredImportFailsParent() throws Exception {
        Path childSpec = writeSubrunChildSpecThatSkipsRequiredExport();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("missing-import-subrun-request.json");
        Files.writeString(request, "{\"goal\":\"missing-import\"}\n");
        Path runs = tempDir.resolve("runs-subrun-missing-import");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        Path parentRunDir = runs.resolve("sample");
        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("error:");
        var parentState = loadDerivedState(RunPaths.eventsPath(parentRunDir));
        assertThat(parentState.runStatus()).isEqualTo(RunStatus.ESCALATED);
        assertThat(parentState.decisions()).containsEntry("subrun_status", "failed");
        assertThat(parentState.artifactIndex()).doesNotContainKey("imported_report");
        assertThat(Files.readString(RunPaths.eventsPath(parentRunDir)))
                .contains("required import artifact", "child_report", "subrun_status", "failed");
    }

    @Test
    void partialSubrunChildStateIsReconciledAsFailed() throws Exception {
        Path childSpec = writeSubrunChildSpec();
        Path spec = writeSubrunSpec(childSpec);
        Path request = tempDir.resolve("partial-subrun-request.json");
        Files.writeString(request, "{\"goal\":\"partial\"}\n");
        Path runs = tempDir.resolve("runs-subrun-partial");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init",
                "--spec=" + spec,
                "--runs=" + runs,
                "--slug=sample",
                "--artifact=request=" + request
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        Path parentRunDir = runs.resolve("sample");
        Path childRunDir = parentRunDir.resolve("subruns/children/sample-child-1");
        Files.writeString(RunPaths.specPath(childRunDir), "{}\n");

        CapturedInvocation auto = capture(() -> Main.exitCode(new String[]{
                "run", "auto", "--runs=" + runs, "--slug=sample"
        }));

        assertThat(auto.exitCode()).isEqualTo(1);
        assertThat(auto.stdout()).contains("\"status\" : \"escalated\"");
        assertThat(auto.stderr()).contains("error:");
        String events = Files.readString(RunPaths.eventsPath(parentRunDir));
        assertThat(events).contains("partial initialized state", "subrun_status", "failed");
        assertThat(loadDerivedState(RunPaths.eventsPath(parentRunDir)).runStatus())
                .isEqualTo(RunStatus.ESCALATED);
    }

    private Path writeCommandSpec(String shell, String routeTarget) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add(shell));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", routeTarget));
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-" + Integer.toHexString(shell.hashCode()) + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeSingleInterfaceRequestCwdSpec() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("task_packet", MAPPER.createObjectNode()
                .put("path", "artifacts/task-packet.json")
                .put("media_type", "application/json"));
        artifacts.set("cwd", MAPPER.createObjectNode()
                .put("path", "artifacts/cwd.txt")
                .put("media_type", "text/plain"));

        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "inspect")
                .put("message", "Inspect request cwd")
                .put("cwd", "$request.repo_root");
        node.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add("pwd > \"$FORGE_OUTPUT_CWD\""));
        node.set("inputs", MAPPER.createArrayNode().add("task_packet"));
        node.set("outputs", MAPPER.createArrayNode().add("cwd"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var interfaceSpec = MAPPER.createObjectNode();
        interfaceSpec.set("inputs", MAPPER.createArrayNode().add("task_packet"));
        interfaceSpec.set("exports", MAPPER.createObjectNode());

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_interface_request_test",
                "inspect",
                interfaceSpec,
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-interface-request.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeRequestCwdEnvSpec() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("report", MAPPER.createObjectNode()
                .put("path", "artifacts/report.md")
                .put("media_type", "text/markdown"));

        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan")
                .put("cwd", "$request.repo_root");
        node.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add("""
                        printf '{"phase":"cwd-env"}\\n' > "$FORGE_PROGRESS_FILE"
                        printf 'cwd=%s\\nrun=%s\\ninput=%s\\noutput=%s\\nprogress=%s\\n' "$(pwd)" "$FORGE_RUN_DIR" "$FORGE_INPUT_REQUEST" "$FORGE_OUTPUT_REPORT" "$FORGE_PROGRESS_FILE" > "$FORGE_OUTPUT_REPORT"
                        """));
        node.set("inputs", MAPPER.createArrayNode().add("request"));
        node.set("outputs", MAPPER.createArrayNode().add("report"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_request_cwd_env_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-request-cwd-env.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeRequestCwdLaunchMarkerSpec() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("report", MAPPER.createObjectNode()
                .put("path", "artifacts/report.md")
                .put("media_type", "text/markdown"));

        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan")
                .put("cwd", "$request.repo_root");
        node.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add("""
                        printf launched > launched.txt
                        mkdir -p "$(dirname "$FORGE_OUTPUT_REPORT")" "$(dirname "$FORGE_PROGRESS_FILE")"
                        printf '{"phase":"stale-prepared"}\\n' > "$FORGE_PROGRESS_FILE"
                        printf 'output=%s\\n' "$FORGE_OUTPUT_REPORT" > "$FORGE_OUTPUT_REPORT"
                        """));
        node.set("inputs", MAPPER.createArrayNode().add("request"));
        node.set("outputs", MAPPER.createArrayNode().add("report"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_request_cwd_launch_marker_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-request-cwd-launch-marker.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeLongCommandChainSpec(int count) {
        List<JsonNode> nodes = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            String id = "step_" + index;
            String target = index + 1 == count ? "__complete__" : "step_" + (index + 1);
            var node = MAPPER.createObjectNode()
                    .put("kind", "command")
                    .put("id", id)
                    .put("message", "Step " + index);
            node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("true"));
            node.set("inputs", MAPPER.createArrayNode());
            node.set("outputs", MAPPER.createArrayNode());
            node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", target));
            nodes.add(node);
        }
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "long_auto_test",
                "step_0",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                nodes,
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-long-auto.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeDirectCommandSpec(List<String> command, String routeTarget) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        var commandArray = MAPPER.createArrayNode();
        command.forEach(commandArray::add);
        node.set("command", commandArray);
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", routeTarget));
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_direct_command_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-direct-" + Integer.toHexString(command.toString().hashCode()) + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeOutputCommandSpec(String shell) {
        return writeOutputCommandSpec(shell, 1);
    }

    private Path writeOutputCommandSpec(String shell, int maxAttempts) {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("report", MAPPER.createObjectNode()
                .put("path", "artifacts/report.md")
                .put("media_type", "text/markdown"));
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add(shell));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode().add("report"));
        node.set("retry_policy", MAPPER.createObjectNode().put("max_attempts", maxAttempts));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_output_command_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-output-"
                + Integer.toHexString((shell + maxAttempts).hashCode())
                + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeRetryCommandSpec(String shell, int maxAttempts) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add(shell));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("retry_policy", MAPPER.createObjectNode().put("max_attempts", maxAttempts));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_retry_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-retry.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeTimedCommandSpec(String shell, long timeoutMs) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan")
                .put("timeout_ms", timeoutMs);
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add(shell));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_timeout_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-timeout.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeNotificationCommandSpec(String shell, String routeTarget, String hookName, Path hookPath) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add(shell));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", routeTarget));

        var notifications = MAPPER.createObjectNode();
        notifications.set(hookName, MAPPER.createObjectNode().put("path", hookPath.toString()));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_notification_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                notifications);
        Path path = tempDir.resolve("workflow-notification-" + hookName + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeRelativeNotificationCommandSpec(Path specRoot) {
        var node = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf ok"));
        node.set("inputs", MAPPER.createArrayNode());
        node.set("outputs", MAPPER.createArrayNode());
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var hook = MAPPER.createObjectNode()
                .put("path", "hooks/notify.sh")
                .put("cwd", "workspace");
        var notifications = MAPPER.createObjectNode();
        notifications.set("complete_hook", hook);

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_relative_notification_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                notifications);
        Path path = specRoot.resolve("workflow.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeHumanNotificationSpec(Path hookPath) {
        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Approve");
        review.set("inputs", MAPPER.createArrayNode().add("plan"));
        review.set("outputs", MAPPER.createArrayNode());
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "__complete__")));
        review.set("route", route);

        var artifacts = MAPPER.createObjectNode();
        artifacts.set("plan", MAPPER.createObjectNode()
                .put("path", "artifacts/plan.md")
                .put("media_type", "text/markdown"));
        var notifications = MAPPER.createObjectNode();
        notifications.set("human_review_hook", MAPPER.createObjectNode().put("path", hookPath.toString()));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_human_notification_test",
                "review",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) review),
                MAPPER.createArrayNode(),
                artifacts,
                notifications);
        Path path = tempDir.resolve("workflow-human-notification.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeStructuredLoopSpec() {
        var plan = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        plan.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf plan"));
        plan.set("inputs", MAPPER.createArrayNode());
        plan.set("outputs", MAPPER.createArrayNode());
        plan.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "review"));

        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Review");
        review.set("inputs", MAPPER.createArrayNode());
        review.set("outputs", MAPPER.createArrayNode());
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved").add("rework"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "__complete__"))
                .add(MAPPER.createObjectNode().put("equals", "rework").put("continue_loop", "review_loop")));
        review.set("route", route);

        var loop = MAPPER.createObjectNode()
                .put("id", "review_loop")
                .put("controller_node", "review")
                .put("entry_node", "plan");
        loop.set("budget", MAPPER.createObjectNode()
                .put("kind", "literal")
                .put("max_iterations", 1));
        loop.set("on_exhaust", MAPPER.createObjectNode()
                .put("to", "__escalate__")
                .put("reason", "review loop exhausted"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_structured_loop_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) plan, review),
                MAPPER.createArrayNode().add(loop),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-structured-loop.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeStructuredCompleteExhaustLoopSpec() {
        var plan = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        plan.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf plan"));
        plan.set("inputs", MAPPER.createArrayNode());
        plan.set("outputs", MAPPER.createArrayNode());
        plan.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "review"));

        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Review");
        review.set("inputs", MAPPER.createArrayNode());
        review.set("outputs", MAPPER.createArrayNode());
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved").add("rework"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "__complete__"))
                .add(MAPPER.createObjectNode().put("equals", "rework").put("continue_loop", "review_loop")));
        review.set("route", route);

        var loop = MAPPER.createObjectNode()
                .put("id", "review_loop")
                .put("controller_node", "review")
                .put("entry_node", "plan");
        loop.set("budget", MAPPER.createObjectNode()
                .put("kind", "literal")
                .put("max_iterations", 1));
        loop.set("on_exhaust", MAPPER.createObjectNode()
                .put("to", "__complete__")
                .put("reason", "review loop completed"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_structured_loop_complete_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) plan, review),
                MAPPER.createArrayNode().add(loop),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-structured-loop-complete.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeArtifactBudgetLoopSpec() {
        return writeArtifactBudgetLoopSpec("0");
    }

    private Path writeArtifactBudgetLoopSpec(String maxBudget) {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("budget", MAPPER.createObjectNode()
                .put("path", "artifacts/budget.json")
                .put("media_type", "application/json"));

        var plan = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        plan.set("command", MAPPER.createArrayNode().add("sh").add("-c")
                .add("printf '{\"max\":" + maxBudget + "}\\n' > \"$FORGE_OUTPUT_BUDGET\""));
        plan.set("inputs", MAPPER.createArrayNode());
        plan.set("outputs", MAPPER.createArrayNode().add("budget"));
        plan.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "review"));

        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Review");
        review.set("inputs", MAPPER.createArrayNode().add("budget"));
        review.set("outputs", MAPPER.createArrayNode());
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved").add("rework"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "__complete__"))
                .add(MAPPER.createObjectNode().put("equals", "rework").put("continue_loop", "review_loop")));
        review.set("route", route);

        var finalize = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "finalize")
                .put("message", "Finalize");
        finalize.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf done"));
        finalize.set("inputs", MAPPER.createArrayNode().add("budget"));
        finalize.set("outputs", MAPPER.createArrayNode());
        finalize.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var loop = MAPPER.createObjectNode()
                .put("id", "review_loop")
                .put("controller_node", "review")
                .put("entry_node", "plan");
        loop.set("budget", MAPPER.createObjectNode()
                .put("kind", "artifact_field")
                .put("artifact", "budget")
                .put("field", "max"));
        loop.set("on_exhaust", MAPPER.createObjectNode()
                .put("to", "finalize")
                .put("reason", "budget exhausted"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_artifact_budget_loop_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) plan, review, finalize),
                MAPPER.createArrayNode().add(loop),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-artifact-budget-loop-" + maxBudget + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeCommandEnvSpec() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("child_report", MAPPER.createObjectNode()
                .put("path", "artifacts/child_report.md")
                .put("media_type", "text/markdown"));
        artifacts.set("report", MAPPER.createObjectNode()
                .put("path", "artifacts/report.md")
                .put("media_type", "text/markdown"));

        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Approve");
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var reviewRoute = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        reviewRoute.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "build")));
        review.set("route", reviewRoute);

        var build = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "build")
                .put("message", "Build");
        build.set("inputs", MAPPER.createArrayNode().add("request"));
        build.set("outputs", MAPPER.createArrayNode().add("report"));
        build.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add("printf 'input=%s\\noutput=%s\\nprogress=%s\\ndecision=%s\\nnode=%s\\n' \"$FORGE_INPUT_REQUEST\" \"$FORGE_OUTPUT_REPORT\" \"$FORGE_PROGRESS_FILE\" \"$FORGE_DECISION_STATUS\" \"$FORGE_CURRENT_NODE\" > \"$FORGE_OUTPUT_REPORT\""));
        build.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_command_env_test",
                "review",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) review, build),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-command-env.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeAgentPromptSpec(String promptDelivery) {
        var agents = MAPPER.createObjectNode();
        agents.set("writer", MAPPER.createObjectNode()
                .put("runner", "codex")
                .put("prompt_delivery", promptDelivery)
                .set("command", MAPPER.createArrayNode()
                        .add("sh")
                        .add("-c")
                        .add("cat > \"$FORGE_OUTPUT_REPORT\"; printf '%s\\n' \"$0 $@\" > \"$FORGE_OUTPUT_ARGV\"; printf '%s\\n' \"$FORGE_PROMPT_FILE\" > \"$FORGE_OUTPUT_PROMPT_PATH\"")
                        .add("codex")
                        .add("exec")));

        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("report", MAPPER.createObjectNode()
                .put("path", "artifacts/report.md")
                .put("media_type", "text/markdown"));
        artifacts.set("argv", MAPPER.createObjectNode()
                .put("path", "artifacts/argv.txt")
                .put("media_type", "text/plain"));
        artifacts.set("prompt_path", MAPPER.createObjectNode()
                .put("path", "artifacts/prompt-path.txt")
                .put("media_type", "text/plain"));

        var node = MAPPER.createObjectNode()
                .put("kind", "agent")
                .put("id", "write")
                .put("agent_id", "writer")
                .put("prompt_template", "Write the final report.")
                .put("message", "Write");
        node.set("inputs", MAPPER.createArrayNode().add("request"));
        node.set("outputs", MAPPER.createArrayNode().add("report").add("argv").add("prompt_path"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_agent_prompt_test",
                "write",
                MAPPER.createObjectNode(),
                agents,
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-agent-" + promptDelivery + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeArgvPromptCwdSpec() {
        var agents = MAPPER.createObjectNode();
        agents.set("writer", MAPPER.createObjectNode()
                .put("runner", "agent")
                .put("prompt_delivery", "argv_path")
                .put("cwd", "$request.repo_root")
                .set("command", MAPPER.createArrayNode()
                        .add("sh")
                        .add("-c")
                        .add("pwd > \"$FORGE_OUTPUT_CWD\"")
                        .add("runner")));

        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("cwd", MAPPER.createObjectNode()
                .put("path", "artifacts/cwd.txt")
                .put("media_type", "text/plain"));

        var node = MAPPER.createObjectNode()
                .put("kind", "agent")
                .put("id", "write")
                .put("agent_id", "writer")
                .put("prompt_template", "Use argv prompt.")
                .put("message", "Write");
        node.set("inputs", MAPPER.createArrayNode().add("request"));
        node.set("outputs", MAPPER.createArrayNode().add("cwd"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_agent_argv_test",
                "write",
                MAPPER.createObjectNode(),
                agents,
                List.of((JsonNode) node),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-agent-argv.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeHumanThenCommandSpec() {
        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "review plan");
        review.set("inputs", MAPPER.createArrayNode().add("plan"));
        review.set("outputs", MAPPER.createArrayNode());
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum")
                .put("description", "Choose whether to continue.")
                .put("example", "approved");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved").add("rework"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var cases = MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "implement"));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", cases);
        route.set("default", MAPPER.createObjectNode().put("to", "__escalate__"));
        review.set("route", route);

        var implement = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "implement")
                .put("message", "Implement");
        implement.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf implemented"));
        implement.set("inputs", MAPPER.createArrayNode());
        implement.set("outputs", MAPPER.createArrayNode());
        implement.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var artifacts = MAPPER.createObjectNode();
        artifacts.set("plan", MAPPER.createObjectNode()
                .put("path", "artifacts/plan.md")
                .put("media_type", "text/markdown"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_human_test",
                "review",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) review, implement),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-human.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeSubrunSpec(Path childWorkflow) {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("summary", MAPPER.createObjectNode()
                .put("path", "artifacts/summary.md")
                .put("media_type", "text/markdown"));
        artifacts.set("imported_report", MAPPER.createObjectNode()
                .put("path", "artifacts/imported-report.md")
                .put("media_type", "text/markdown"));

        var child = MAPPER.createObjectNode()
                .put("kind", "subrun")
                .put("id", "child")
                .put("message", "Run child")
                .put("request_artifact", "request")
                .put("summary_artifact", "summary");
        child.set("workflow_ref", MAPPER.createObjectNode().put("path", childWorkflow.toString()));
        child.set("inputs", MAPPER.createArrayNode().add("request"));
        child.set("outputs", MAPPER.createArrayNode().add("summary").add("imported_report"));
        child.set("import_artifacts", MAPPER.createObjectNode().put("child_report", "imported_report"));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "subrun_status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "complete").put("to", "__complete__"))
                .add(MAPPER.createObjectNode().put("equals", "failed").put("to", "__escalate__"))
                .add(MAPPER.createObjectNode().put("equals", "escalate").put("to", "__escalate__")));
        child.set("route", route);
        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_subrun_test",
                "child",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) child),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-subrun.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeSubrunChildSpec() {
        return writeSubrunChildSpec("printf 'child report\\n' > \"$FORGE_OUTPUT_CHILD_REPORT\"");
    }

    private Path writeSubrunChildSpec(String shell) {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("child_report", MAPPER.createObjectNode()
                .put("path", "artifacts/child-report.md")
                .put("media_type", "text/markdown"));

        var deliver = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "deliver")
                .put("message", "Deliver child report");
        deliver.set("inputs", MAPPER.createArrayNode().add("request"));
        deliver.set("outputs", MAPPER.createArrayNode().add("child_report"));
        deliver.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add(shell));
        deliver.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var exports = MAPPER.createObjectNode();
        exports.set("child_report", MAPPER.createObjectNode()
                .put("required", true)
                .put("terminal_only", true));
        var interfaceSpec = MAPPER.createObjectNode();
        interfaceSpec.set("inputs", MAPPER.createArrayNode().add("request"));
        interfaceSpec.set("exports", exports);

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_subrun_child_test",
                "deliver",
                interfaceSpec,
                MAPPER.createObjectNode(),
                List.of((JsonNode) deliver),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("child-workflow.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeSubrunChildSpecThatSkipsRequiredExport() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("child_report", MAPPER.createObjectNode()
                .put("path", "artifacts/child-report.md")
                .put("media_type", "text/markdown"));

        var skip = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "skip")
                .put("message", "Complete without report");
        skip.set("inputs", MAPPER.createArrayNode().add("request"));
        skip.set("outputs", MAPPER.createArrayNode());
        skip.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("true"));
        skip.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var produce = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "produce")
                .put("message", "Unreached producer");
        produce.set("inputs", MAPPER.createArrayNode().add("request"));
        produce.set("outputs", MAPPER.createArrayNode().add("child_report"));
        produce.set("command", MAPPER.createArrayNode()
                .add("sh")
                .add("-c")
                .add("printf 'child report\\n' > \"$FORGE_OUTPUT_CHILD_REPORT\""));
        produce.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        var exports = MAPPER.createObjectNode();
        exports.set("child_report", MAPPER.createObjectNode()
                .put("required", true)
                .put("terminal_only", true));
        var interfaceSpec = MAPPER.createObjectNode();
        interfaceSpec.set("inputs", MAPPER.createArrayNode().add("request"));
        interfaceSpec.set("exports", exports);

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_subrun_missing_export_child_test",
                "skip",
                interfaceSpec,
                MAPPER.createObjectNode(),
                List.of((JsonNode) skip, produce),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("missing-export-child-workflow.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeBlockingSubrunChildSpec() {
        var artifacts = MAPPER.createObjectNode();
        artifacts.set("request", MAPPER.createObjectNode()
                .put("path", "artifacts/request.json")
                .put("media_type", "application/json"));
        artifacts.set("child_report", MAPPER.createObjectNode()
                .put("path", "artifacts/child_report.md")
                .put("media_type", "text/markdown"));

        var review = MAPPER.createObjectNode()
                .put("kind", "human")
                .put("id", "review")
                .put("message", "Review child request");
        review.set("inputs", MAPPER.createArrayNode().add("request"));
        review.set("outputs", MAPPER.createArrayNode().add("child_report"));
        var field = MAPPER.createObjectNode()
                .put("name", "status")
                .put("required", true)
                .put("kind", "enum");
        field.set("allowed_values", MAPPER.createArrayNode().add("approved"));
        review.set("fields", MAPPER.createArrayNode().add(field));
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "status");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "approved").put("to", "__complete__")));
        review.set("route", route);

        var interfaceSpec = MAPPER.createObjectNode();
        interfaceSpec.set("inputs", MAPPER.createArrayNode().add("request"));
        var exports = MAPPER.createObjectNode();
        exports.set("child_report", MAPPER.createObjectNode()
                .put("required", true)
                .put("terminal_only", true));
        interfaceSpec.set("exports", exports);

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_subrun_blocked_child_test",
                "review",
                interfaceSpec,
                MAPPER.createObjectNode(),
                List.of((JsonNode) review),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("blocked-child-workflow.json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private Path writeJudgeSpec(String decisionValue) {
        String script = "printf '{\"decision\":\"" + decisionValue + "\"}' > \"$FORGE_OUTPUT_JUDGE_RESULT\"";
        var agents = MAPPER.createObjectNode();
        agents.set("judge", MAPPER.createObjectNode()
                .put("runner", "agent")
                .set("command", MAPPER.createArrayNode().add("sh").add("-c").add(script)));

        var artifacts = MAPPER.createObjectNode();
        artifacts.set("judge_result", MAPPER.createObjectNode()
                .put("path", "artifacts/judge.json")
                .put("media_type", "application/json"));

        var judge = MAPPER.createObjectNode()
                .put("kind", "judge")
                .put("id", "judge")
                .put("agent_id", "judge")
                .put("message", "Judge")
                .put("prompt_template", "Read the output artifact and decide.")
                .put("decision_schema", "judge_result_v1");
        judge.set("inputs", MAPPER.createArrayNode());
        judge.set("outputs", MAPPER.createArrayNode().add("judge_result"));
        var fields = MAPPER.createArrayNode();
        var decision = fields.addObject().put("name", "decision");
        decision.set("allowed_values", MAPPER.createArrayNode().add("accept").add("replan").add("escalate"));
        judge.set("decision_fields", fields);
        var route = MAPPER.createObjectNode()
                .put("mode", "by_field")
                .put("field", "decision");
        route.set("cases", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("equals", "accept").put("to", "__complete__"))
                .add(MAPPER.createObjectNode().put("equals", "escalate").put("to", "__escalate__")));
        route.set("default", MAPPER.createObjectNode().put("to", "judge"));
        judge.set("route", route);

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_judge_test",
                "judge",
                MAPPER.createObjectNode(),
                agents,
                List.of((JsonNode) judge),
                MAPPER.createArrayNode(),
                artifacts,
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-judge-" + decisionValue + ".json");
        WorkflowSpecs.write(path, spec);
        return path;
    }

    private static void writeText(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static Path writeProjectionBacklogRecord(Path runDir, ProjectionBacklogEntry entry) {
        Path backlogDir = RunPaths.projectionBacklogDir(runDir);
        try {
            Files.createDirectories(backlogDir);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
        Path path = backlogDir.resolve(entry.firstEventSeq() + "-"
                + (entry.artifactName() == null ? "artifact" : entry.artifactName())
                + "-"
                + System.nanoTime()
                + ".json");
        Json.writePretty(path, entry);
        return path;
    }

    private static List<String> commandValues(JsonNode command) {
        return MAPPER.convertValue(
                command,
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static void waitFor(BooleanSupplier condition, CompletableFuture<?> running) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (running.isDone()) {
                running.get(1, TimeUnit.SECONDS);
            }
            Thread.sleep(20);
        }
        if (running.isDone()) {
            running.get(1, TimeUnit.SECONDS);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private Path preparePendingCommandRun(Path runs) throws Exception {
        Path spec = writeCommandSpec("printf run", "__complete__");
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "init", "--spec=" + spec, "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        assertThat(capture(() -> Main.exitCode(new String[]{
                "run", "next", "--runs=" + runs, "--slug=sample"
        })).exitCode()).isZero();
        return runs.resolve("sample");
    }

    private static void appendStaleExecution(Path runDir, String operationId) {
        appendActiveExecution(runDir, operationId, true, Long.MAX_VALUE);
    }

    private static void appendPrelaunchExecution(Path runDir, String operationId) {
        appendActiveExecution(runDir, operationId, false, null);
    }

    private static void appendActiveExecutionHeartbeat(Path runDir, String operationId, long pid) {
        appendActiveExecutionEvent(runDir, operationId, "operation_heartbeat", true, pid);
    }

    private static void appendActiveExecution(Path runDir, String operationId, boolean launched, Long pid) {
        appendActiveExecutionEvent(runDir, operationId, "operation_started", launched, pid);
    }

    private static void appendActiveExecutionEvent(
            Path runDir,
            String operationId,
            String eventType,
            boolean launched,
            Long pid) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("kind", "process");
        execution.put("operation_id", operationId);
        execution.put("operation_kind", "node_dispatch");
        execution.put("started_at", "2026-05-23T13:30:00Z");
        execution.put("latest_activity_at", "2026-05-23T13:30:00Z");
        execution.put("progress_path", dispatchOutputRecordPath(operationId, ".progress.ndjson"));
        execution.put("stdout_path", dispatchOutputRecordPath(operationId, ".stdout"));
        execution.put("stderr_path", dispatchOutputRecordPath(operationId, ".stderr"));
        execution.put("result_path", dispatchOutputRecordPath(operationId, ".result.json"));
        execution.put("stdout_bytes", 0);
        execution.put("stderr_bytes", 0);
        execution.put("progress_bytes", 0);
        execution.put("progress_entries", 0);
        execution.put("active", true);
        execution.put("pid", pid);
        execution.put("launched", launched);
        Long lastSeq = loadDerivedState(RunPaths.eventsPath(runDir)).lastEventSeq();
        EventLog.appendEvent(RunPaths.eventsPath(runDir), EventEnvelope.of(
                (lastSeq == null ? 0L : lastSeq) + 1L,
                "2026-05-23T13:30:00Z",
                eventType,
                Map.of("operation_id", operationId, "execution", execution)));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void replaceExecutionField(
            Path runDir,
            String operationId,
            String field,
            String value) throws IOException {
        Path eventsPath = RunPaths.eventsPath(runDir);
        StringBuilder rewrittenLines = new StringBuilder();
        for (EventEnvelope event : EventLog.readEvents(eventsPath)) {
            EventEnvelope rewritten = new EventEnvelope(event.seq(), event.timestamp(), event.type());
            for (Map.Entry<String, JsonNode> entry : event.fields().entrySet()) {
                JsonNode eventField = entry.getValue();
                if ("execution".equals(entry.getKey())
                        && operationId.equals(event.textField("operation_id"))
                        && eventField.isObject()) {
                    ObjectNode execution = ((ObjectNode) eventField).deepCopy();
                    execution.put(field, value);
                    rewritten.put(entry.getKey(), execution);
                } else {
                    rewritten.put(entry.getKey(), eventField);
                }
            }
            rewrittenLines.append(Json.toJson(rewritten)).append('\n');
        }
        Files.writeString(eventsPath, rewrittenLines.toString(), StandardCharsets.UTF_8);
    }

    private static void removeTerminalExecutionEvent(Path runDir, String operationId) throws IOException {
        Path eventsPath = RunPaths.eventsPath(runDir);
        StringBuilder retainedLines = new StringBuilder();
        for (EventEnvelope event : EventLog.readEvents(eventsPath)) {
            if (isTerminalExecutionEvent(event, operationId)) {
                continue;
            }
            retainedLines.append(Json.toJson(event)).append('\n');
        }
        Files.writeString(eventsPath, retainedLines.toString(), StandardCharsets.UTF_8);
    }

    private static void mutatePreparedDispatchPayload(
            Path runDir,
            String operationId,
            Consumer<ObjectNode> mutate) throws IOException {
        Path eventsPath = RunPaths.eventsPath(runDir);
        StringBuilder rewrittenLines = new StringBuilder();
        for (EventEnvelope event : EventLog.readEvents(eventsPath)) {
            EventEnvelope rewritten = new EventEnvelope(event.seq(), event.timestamp(), event.type());
            for (Map.Entry<String, JsonNode> entry : event.fields().entrySet()) {
                JsonNode eventField = entry.getValue();
                if ("operation".equals(entry.getKey())
                        && "operation_prepared".equals(event.type())
                        && operationId.equals(eventField.path("operation_id").asText())
                        && eventField.path("dispatch").isObject()) {
                    ObjectNode operation = ((ObjectNode) eventField).deepCopy();
                    mutate.accept((ObjectNode) operation.get("dispatch"));
                    rewritten.put(entry.getKey(), operation);
                } else {
                    rewritten.put(entry.getKey(), eventField);
                }
            }
            rewrittenLines.append(Json.toJson(rewritten)).append('\n');
        }
        Files.writeString(eventsPath, rewrittenLines.toString(), StandardCharsets.UTF_8);
    }

    private static void mutatePreparedSubrunPayload(
            Path runDir,
            String operationId,
            Consumer<ObjectNode> mutate) throws IOException {
        Path eventsPath = RunPaths.eventsPath(runDir);
        StringBuilder rewrittenLines = new StringBuilder();
        for (EventEnvelope event : EventLog.readEvents(eventsPath)) {
            EventEnvelope rewritten = new EventEnvelope(event.seq(), event.timestamp(), event.type());
            for (Map.Entry<String, JsonNode> entry : event.fields().entrySet()) {
                JsonNode eventField = entry.getValue();
                if ("operation".equals(entry.getKey())
                        && "operation_prepared".equals(event.type())
                        && operationId.equals(eventField.path("operation_id").asText())
                        && eventField.path("subrun").isObject()) {
                    ObjectNode operation = ((ObjectNode) eventField).deepCopy();
                    mutate.accept((ObjectNode) operation.get("subrun"));
                    rewritten.put(entry.getKey(), operation);
                } else {
                    rewritten.put(entry.getKey(), eventField);
                }
            }
            rewrittenLines.append(Json.toJson(rewritten)).append('\n');
        }
        Files.writeString(eventsPath, rewrittenLines.toString(), StandardCharsets.UTF_8);
    }

    private static boolean isTerminalExecutionEvent(EventEnvelope event, String operationId) {
        return ("operation_succeeded".equals(event.type()) || "operation_failed".equals(event.type()))
                && operationId.equals(event.textField("operation_id"));
    }

    private static long terminalExecutionEventCount(Path runDir, String operationId) {
        return EventLog.readEvents(RunPaths.eventsPath(runDir)).stream()
                .filter(event -> isTerminalExecutionEvent(event, operationId))
                .count();
    }

    private static Map<String, Object> completedExecutionResult(
            String operationId,
            boolean success,
            int exitCode,
            String failureReason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "process");
        result.put("operation_id", operationId);
        result.put("operation_kind", "node_dispatch");
        result.put("started_at", "2026-05-23T13:30:00Z");
        result.put("latest_activity_at", "2026-05-23T13:30:01Z");
        result.put("latest_output_at", "2026-05-23T13:30:01Z");
        result.put("progress_path", dispatchOutputRecordPath(operationId, ".progress.ndjson"));
        result.put("stdout_path", dispatchOutputRecordPath(operationId, ".stdout"));
        result.put("stderr_path", dispatchOutputRecordPath(operationId, ".stderr"));
        result.put("result_path", dispatchOutputRecordPath(operationId, ".result.json"));
        result.put("stdout_bytes", 0);
        result.put("stderr_bytes", 0);
        result.put("progress_bytes", 0);
        result.put("progress_entries", 0);
        result.put("active", false);
        result.put("pid", Long.MAX_VALUE);
        result.put("launched", true);
        result.put("success", success);
        result.put("exit_code", exitCode);
        result.put("failure_reason", failureReason);
        result.put("completed_at", "2026-05-23T13:30:01Z");
        result.put("status", "executed");
        result.put("dispatch_id", operationId);
        result.put("timed_out", false);
        result.put("message", success ? "dispatch completed successfully" : "dispatch failed");
        return result;
    }

    private static String dispatchOutputRecordPath(String operationId, String suffix) {
        return Path.of("dispatch", "output", operationId + suffix).toString().replace('\\', '/');
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class RecordingProgressSource implements DispatchProgressCursor.ProgressFileSource {
        private byte[] content = new byte[0];
        private final List<Long> readOffsets = new ArrayList<>();

        private void replace(String value) {
            content = value.getBytes(StandardCharsets.UTF_8);
        }

        private void append(String value) {
            byte[] appended = value.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[content.length + appended.length];
            System.arraycopy(content, 0, combined, 0, content.length);
            System.arraycopy(appended, 0, combined, content.length, appended.length);
            content = combined;
        }

        private int length() {
            return content.length;
        }

        private List<Long> readOffsets() {
            return readOffsets;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public long size() {
            return content.length;
        }

        @Override
        public long readFrom(long offset, IntConsumer consumer) {
            readOffsets.add(offset);
            long read = 0;
            for (int index = Math.toIntExact(offset); index < content.length; index++) {
                consumer.accept(content[index] & 0xff);
                read++;
            }
            return read;
        }

        @Override
        public String description() {
            return "recording-progress";
        }
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private Path writeTwoNodeSpec() {
        var plan = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "plan")
                .put("message", "Plan");
        plan.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf plan"));
        plan.set("inputs", MAPPER.createArrayNode());
        plan.set("outputs", MAPPER.createArrayNode());
        plan.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "finalize"));

        var finalize = MAPPER.createObjectNode()
                .put("kind", "command")
                .put("id", "finalize")
                .put("message", "Finalize");
        finalize.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("printf done"));
        finalize.set("inputs", MAPPER.createArrayNode());
        finalize.set("outputs", MAPPER.createArrayNode());
        finalize.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));

        WorkflowSpec spec = new WorkflowSpec(
                2,
                "structured",
                "runtime_route_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of((JsonNode) plan, finalize),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        Path path = tempDir.resolve("workflow-route.json");
        WorkflowSpecs.write(path, spec);
        return path;
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

    private static CapturedInvocation captureUntilOutput(
            ExitSupplier invocation,
            java.util.function.Predicate<String> outputReady) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                exitCode.set(invocation.get());
            } catch (Throwable error) {
                failure[0] = error;
            }
        }, "forge-runtime-watch-test");
        try (PrintStream outCapture = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream errCapture = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(outCapture);
            System.setErr(errCapture);
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && !outputReady.test(stdout.toString(StandardCharsets.UTF_8))) {
                if (!worker.isAlive()) {
                    break;
                }
                Thread.sleep(20);
            }
            String observed = stdout.toString(StandardCharsets.UTF_8);
            assertThat(outputReady.test(observed)).isTrue();
            assertThat(worker.isAlive()).isTrue();
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));
            assertThat(worker.isAlive()).isFalse();
            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }
            return new CapturedInvocation(
                    exitCode.get(),
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8)
            );
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
            }
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
