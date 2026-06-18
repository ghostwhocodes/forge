package dev.llaith.forge.runtime.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.runtime.dispatch.DispatchProgressCursor.ProgressStats;
import dev.llaith.forge.storage.AdvisoryLock;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.FilesystemRunStore;
import dev.llaith.forge.storage.RunPaths;
import dev.llaith.forge.storage.StoredRunContext;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.util.ProcessResult;
import dev.llaith.forge.util.Processes;
import dev.llaith.forge.util.TimeSupport;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.PendingOperation;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class DispatchLifecycle {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration EXECUTION_PID_PUBLICATION_WAIT = Duration.ofSeconds(2);
    private static final Duration EXECUTION_PID_PUBLICATION_POLL = Duration.ofMillis(10);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FilesystemRunStore store;
    private final Clock clock;

    public DispatchLifecycle(Clock clock) {
        this.store = new FilesystemRunStore();
        this.clock = clock;
    }

    public Map<String, Object> executePendingDispatch(Path runDir, boolean tee) {
        StoredRunContext context = store.loadRunContext(runDir);
        PendingOperation selected = requirePendingDispatch(context.state());
        Map<String, Object> recorded = recordedExecutionResult(runDir, context.state(), selected, tee);
        if (recorded != null) {
            return recorded;
        }
        Optional<AdvisoryLock> acquired = AdvisoryLock.tryAcquire(
                RunPaths.dispatchExecutionLeasePath(runDir, selected.operationId()),
                "forge run exec-dispatch " + selected.operationId());
        if (acquired.isEmpty()) {
            return executionLeaseContentionResult(runDir, selected, tee);
        }
        try (AdvisoryLock ignored = acquired.get()) {
            return executePendingDispatchLocked(runDir, selected.operationId(), tee);
        }
    }

    public static PendingOperation requirePendingDispatch(DerivedRunState state) {
        PendingOperation operation = state.pendingOperation();
        if (operation == null || !isDispatchOperation(operation)) {
            throw new ForgeException("error: run does not have a prepared dispatch");
        }
        return operation;
    }

    public static boolean isDispatchOperation(PendingOperation operation) {
        return "node_dispatch".equals(operation.kind()) || "notification".equals(operation.kind());
    }

    public static void requireRecordedDispatchExecution(
            DerivedRunState state,
            PendingOperation operation,
            boolean expectedSuccess) {
        JsonNode execution = state.operationExecutions().get(operation.operationId());
        if (execution == null || execution.isNull()) {
            throw new ForgeException("error: no recorded execution result for dispatch '" + operation.operationId() + "'");
        }
        if (execution.path("active").asBoolean(false)) {
            throw new ForgeException("error: dispatch '" + operation.operationId() + "' is still running");
        }
        if (!execution.hasNonNull("success")) {
            throw new ForgeException("error: dispatch '" + operation.operationId() + "' does not have a completed execution result");
        }
        if (execution.get("success").asBoolean() != expectedSuccess) {
            String expected = expectedSuccess ? "successful" : "failed";
            throw new ForgeException("error: dispatch '"
                    + operation.operationId()
                    + "' does not have a "
                    + expected
                    + " execution result");
        }
        String operationId = requiredRecordedExecutionText(operation.operationId(), execution, "operation_id");
        if (!operationId.equals(operation.operationId())) {
            throw new ForgeException("error: dispatch '"
                    + operation.operationId()
                    + "' recorded execution has operation_id '"
                    + operationId
                    + "'");
        }
        String operationKind = requiredRecordedExecutionText(operation.operationId(), execution, "operation_kind");
        if (!operationKind.equals(operation.kind())) {
            throw new ForgeException("error: dispatch '"
                    + operation.operationId()
                    + "' has kind '"
                    + operationKind
                    + "' not '"
                    + operation.kind()
                    + "'");
        }
    }

    private Map<String, Object> executionLeaseContentionResult(
            Path runDir,
            PendingOperation operation,
            boolean tee) {
        long deadline = System.nanoTime() + EXECUTION_PID_PUBLICATION_WAIT.toNanos();
        while (true) {
            StoredRunContext latest = store.loadRunContext(runDir);
            Map<String, Object> recorded = recordedExecutionResult(runDir, latest.state(), operation, tee);
            if (recorded != null) {
                return recorded;
            }
            JsonNode execution = latest.state().operationExecutions().get(operation.operationId());
            if (!executionAwaitingPidPublication(execution)) {
                Long processId = execution == null || execution.isNull() ? null : pid(execution);
                return alreadyRunningResult(operation, processId);
            }
            if (System.nanoTime() >= deadline) {
                throw new ForgeException("error: dispatch '"
                        + operation.operationId()
                        + "' has not recorded its process pid yet");
            }
            sleepBeforeReloadingDispatchExecutionPid();
        }
    }

    private Map<String, Object> executePendingDispatchLocked(Path runDir, String dispatchId, boolean tee) {
        Path outputDir = RunPaths.dispatchOutputDir(runDir);
        Path stdout = outputDir.resolve(dispatchId + ".stdout");
        Path stderr = outputDir.resolve(dispatchId + ".stderr");
        Path progress = outputDir.resolve(dispatchId + ".progress.ndjson");
        Path resultPath = outputDir.resolve(dispatchId + ".result.json");
        ExecutionClaim claim = store.mutateRun(runDir, context -> {
            PendingOperation pending = requirePendingDispatch(context.state());
            if (!pending.operationId().equals(dispatchId)) {
                throw new ForgeException("error: pending dispatch is '"
                        + pending.operationId()
                        + "' not '"
                        + dispatchId
                        + "'");
            }
            Map<String, Object> recorded = recordedExecutionResult(runDir, context.state(), pending, tee);
            if (recorded != null) {
                return new ExecutionClaim(pending, recorded);
            }
            return new ExecutionClaim(pending, null);
        });
        if (claim.recordedResult() != null) {
            return claim.recordedResult();
        }
        Map<String, Object> recovered = recoverStaleRecordedExecution(runDir, dispatchId, tee);
        if (recovered != null) {
            return recovered;
        }
        PendingOperation operation = claim.operation();
        String startedAt = TimeSupport.now(clock);
        PreparedDispatchLaunch launch = preparedDispatchLaunch(runDir, operation);
        Filesystem.ensureDirectory(outputDir);
        writeBytes(progress, new byte[0]);
        ensureOutputParents(launch.outputPaths());

        boolean launched = true;
        @Nullable String launchFailure = null;
        ProcessResult result;
        DispatchProgressObserver observer = new DispatchProgressObserver(
                runDir,
                operation,
                startedAt,
                stdout,
                stderr,
                resultPath,
                progress);
        try {
            result = Processes.runToFiles(
                    launch.command(),
                    launch.cwd(),
                    launch.env(),
                    stdinBytes(launch.stdinPath()),
                    launch.timeout(),
                    stdout,
                    stderr,
                    observer);
        } catch (ForgeException error) {
            if (!(error.getCause() instanceof IOException) && observer.pid() == null) {
                throw error;
            }
            launched = observer.pid() != null;
            launchFailure = error.getMessage();
            byte[] launchFailureBytes = (launchFailure + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            writeBytes(stdout, new byte[0]);
            writeBytes(stderr, launchFailureBytes);
            result = new ProcessResult(
                    -1,
                    new byte[0],
                    new byte[0],
                    0,
                    launchFailureBytes.length,
                    false,
                    observer.pid());
        }
        String completedAt = TimeSupport.now(clock);
        ProgressStats progressStats = observer.progressStats();
        @Nullable String heartbeatAt = progressStats.hasProgress() ? completedAt : null;
        @Nullable String failureReason = result.succeeded()
                ? null
                : launchFailure != null ? launchFailure
                : (result.timedOut() ? "dispatch timed out" : "dispatch execution failed");
        String message = launchFailure != null
                ? launchFailure
                : result.timedOut()
                        ? "dispatch timed out"
                        : result.succeeded() ? "dispatch completed successfully" : "dispatch failed";
        Map<String, Object> resultJson = processExecutionState(
                runDir,
                operation,
                startedAt,
                completedAt,
                stdout,
                stderr,
                resultPath,
                progress,
                result.stdoutBytes(),
                result.stderrBytes(),
                progressStats.bytes(),
                progressStats.entries(),
                false,
                launched,
                result.pid(),
                result.succeeded(),
                result.exitCode(),
                failureReason,
                heartbeatAt);
        resultJson.put("status", "executed");
        resultJson.put("dispatch_id", operation.operationId());
        resultJson.put("timed_out", result.timedOut());
        resultJson.put("message", message);
        Json.writePretty(resultPath, resultJson);
        String executionEventType = result.succeeded() ? "operation_succeeded" : "operation_failed";
        ProcessResult completedResult = result;
        boolean processLaunched = launched;
        store.mutateRun(runDir, context -> {
            PendingOperation current = requirePendingDispatch(context.state());
            if (!current.operationId().equals(operation.operationId())) {
                throw new ForgeException("error: pending dispatch is '"
                        + current.operationId()
                        + "' not '"
                        + operation.operationId()
                        + "'");
            }
            long seq = nextSeq(context.state());
            String timestamp = TimeSupport.now(clock);
            if (progressStats.hasProgress()) {
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_heartbeat",
                        Map.of(
                                "operation_id", operation.operationId(),
                                "execution", processExecutionState(
                                        runDir,
                                        operation,
                                        startedAt,
                                        null,
                                        stdout,
                                        stderr,
                                        resultPath,
                                        progress,
                                        completedResult.stdoutBytes(),
                                        completedResult.stderrBytes(),
                                        progressStats.bytes(),
                                        progressStats.entries(),
                                        true,
                                        processLaunched,
                                        completedResult.pid(),
                                        null,
                                        null,
                                        null,
                                        timestamp))));
            }
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq,
                    timestamp,
                    executionEventType,
                    Map.of("operation_id", operation.operationId(), "execution", resultJson)));
            return null;
        });
        if (tee) {
            try {
                streamRecordedOutput(stdout);
                streamRecordedOutput(stderr);
            } catch (IOException error) {
                throw new ForgeException("failed to replay recorded dispatch output", error);
            }
        }
        return resultJson;
    }

    private static PreparedDispatchLaunch preparedDispatchLaunch(Path runDir, PendingOperation operation) {
        Path root = absolutePath(runDir);
        JsonNode payload = operation.payload();
        String dispatchId = operation.operationId();
        List<String> command = preparedCommand(dispatchId, payload.get("command"));
        Map<String, String> env = preparedEnv(dispatchId, payload.get("env"));
        validatePreparedEnvPaths(root, dispatchId, env);
        boolean nodeDispatch = "node_dispatch".equals(operation.kind());
        preparedRunOwnedPathArray(root, dispatchId, payload.get("input_paths"), "input_paths", nodeDispatch);
        List<Path> outputPaths = preparedRunOwnedPathArray(
                root,
                dispatchId,
                payload.get("output_paths"),
                "output_paths",
                nodeDispatch);
        @Nullable Path stdinPath = optionalPreparedRunOwnedPath(root, dispatchId, payload.get("stdin_path"), "stdin_path");
        @Nullable Path cwd = optionalPreparedCwd(dispatchId, payload.get("cwd"));
        Duration timeout = Duration.ofMillis(preparedTimeoutMillis(dispatchId, payload));
        return new PreparedDispatchLaunch(command, cwd, env, stdinPath, outputPaths, timeout);
    }

    private static List<String> preparedCommand(String dispatchId, JsonNode command) {
        if (command == null || !command.isArray()) {
            throw invalidPreparedDispatchPath(dispatchId, "command", "must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < command.size(); index++) {
            JsonNode value = command.get(index);
            if (!value.isTextual()) {
                throw invalidPreparedDispatchPath(
                        dispatchId,
                        "command[" + index + "]",
                        "must be a string");
            }
            if (index == 0 && value.asText().isBlank()) {
                throw invalidPreparedDispatchPath(
                        dispatchId,
                        "command[0]",
                        "must be a nonblank executable");
            }
            values.add(value.asText());
        }
        if (values.isEmpty()) {
            throw invalidPreparedDispatchPath(dispatchId, "command", "must contain at least one argument");
        }
        return List.copyOf(values);
    }

    private static long preparedTimeoutMillis(String dispatchId, JsonNode payload) {
        JsonNode value = payload.get("timeout_ms");
        if (value == null || value.isNull()) {
            return DEFAULT_TIMEOUT.toMillis();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0L) {
            throw invalidPreparedDispatchField(dispatchId, "timeout_ms", "must be a non-negative integer");
        }
        return value.asLong();
    }

    private static Map<String, String> preparedEnv(String dispatchId, JsonNode env) {
        if (env == null || !env.isObject()) {
            throw invalidPreparedDispatchPath(dispatchId, "env", "must be an object with string values");
        }
        Map<String, String> values = new TreeMap<>();
        env.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                throw invalidPreparedDispatchPath(
                        dispatchId,
                        "env." + entry.getKey(),
                        "must be a string");
            }
            values.put(entry.getKey(), value.asText());
        });
        return Map.copyOf(values);
    }

    private static void validatePreparedEnvPaths(Path root, String dispatchId, Map<String, String> env) {
        Path envRunDir = requiredPreparedEnvRunOwnedPath(root, dispatchId, env, "FORGE_RUN_DIR", true);
        if (!envRunDir.equals(root)) {
            throw invalidPreparedDispatchPath(
                    dispatchId,
                    "FORGE_RUN_DIR",
                    "must equal the run directory: " + env.get("FORGE_RUN_DIR"));
        }
        requiredPreparedEnvRunOwnedPath(root, dispatchId, env, "FORGE_SPEC_FILE", false);
        requiredPreparedEnvRunOwnedPath(root, dispatchId, env, "FORGE_EVENTS_FILE", false);
        requiredPreparedEnvRunOwnedPath(root, dispatchId, env, "FORGE_PROGRESS_FILE", false);
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("FORGE_INPUT_")
                    || key.startsWith("FORGE_OUTPUT_")
                    || "FORGE_PROMPT_FILE".equals(key)) {
                preparedRunOwnedPath(root, dispatchId, entry.getValue(), key, false);
            }
        }
    }

    private static Path requiredPreparedEnvRunOwnedPath(
            Path root,
            String dispatchId,
            Map<String, String> env,
            String name,
            boolean allowRoot) {
        String value = env.get(name);
        if (value == null) {
            throw invalidPreparedDispatchPath(dispatchId, name, "is required");
        }
        return preparedRunOwnedPath(root, dispatchId, value, name, allowRoot);
    }

    private static List<Path> preparedRunOwnedPathArray(
            Path root,
            String dispatchId,
            @Nullable JsonNode values,
            String field,
            boolean required) {
        if (values == null || values.isNull()) {
            if (required) {
                throw invalidPreparedDispatchPath(dispatchId, field, "must be an array of absolute run-owned paths");
            }
            return List.of();
        }
        if (!values.isArray()) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must be an array of absolute run-owned paths");
        }
        List<Path> paths = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            paths.add(preparedRunOwnedPath(root, dispatchId, values.get(index), field + "[" + index + "]", false));
        }
        return List.copyOf(paths);
    }

    private static @Nullable Path optionalPreparedRunOwnedPath(
            Path root,
            String dispatchId,
            @Nullable JsonNode value,
            String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        return preparedRunOwnedPath(root, dispatchId, value, field, false);
    }

    private static Path preparedRunOwnedPath(
            Path root,
            String dispatchId,
            JsonNode value,
            String field,
            boolean allowRoot) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must be a nonblank absolute run-owned string");
        }
        return preparedRunOwnedPath(root, dispatchId, value.asText(), field, allowRoot);
    }

    private static Path preparedRunOwnedPath(
            Path root,
            String dispatchId,
            String raw,
            String field,
            boolean allowRoot) {
        if (raw.isBlank()) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must be a nonblank absolute run-owned string");
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException error) {
            throw invalidPreparedDispatchPath(dispatchId, field, "is not a valid path: " + raw);
        }
        if (!path.isAbsolute()) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must be absolute: " + raw);
        }
        Path absolute = path.toAbsolutePath();
        Path normalized = absolute.normalize();
        if (!absolute.equals(normalized)) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must be normalized without . or .. components: " + raw);
        }
        if (allowRoot && normalized.equals(root)) {
            return normalized;
        }
        if (normalized.equals(root)) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must name a file below the run directory");
        }
        if (!normalized.startsWith(root)) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must stay inside the run directory: " + raw);
        }
        Optional<Path> symlink = firstSymlinkComponent(root, normalized);
        if (symlink.isPresent()) {
            throw invalidPreparedDispatchPath(dispatchId, field, "must not include symlink components: "
                    + root.relativize(symlink.get()));
        }
        return normalized;
    }

    private static @Nullable Path optionalPreparedCwd(String dispatchId, @Nullable JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidPreparedDispatchPath(dispatchId, "cwd", "must be a nonblank absolute path when present");
        }
        Path cwd;
        try {
            cwd = Path.of(value.asText()).normalize();
        } catch (InvalidPathException error) {
            throw invalidPreparedDispatchPath(dispatchId, "cwd", "is not a valid path: " + value.asText());
        }
        if (!cwd.isAbsolute()) {
            throw invalidPreparedDispatchPath(dispatchId, "cwd", "must be absolute: " + value.asText());
        }
        return cwd.toAbsolutePath().normalize();
    }

    private static ForgeException invalidPreparedDispatchPath(String dispatchId, String field, String reason) {
        return invalidPreparedDispatchField(dispatchId, field, reason);
    }

    private static ForgeException invalidPreparedDispatchField(String dispatchId, String field, String reason) {
        String id = dispatchId.isBlank() ? "unknown" : dispatchId;
        return new ForgeException("error: prepared dispatch field '"
                + field
                + "' for '"
                + id
                + "' "
                + reason);
    }

    private static String requiredRecordedExecutionText(String dispatchId, JsonNode execution, String field) {
        JsonNode value = execution.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ForgeException("error: dispatch '"
                    + dispatchId
                    + "' recorded execution is missing "
                    + field);
        }
        return value.asText();
    }

    private @Nullable Map<String, Object> recordedExecutionResult(
            Path runDir,
            DerivedRunState state,
            PendingOperation operation,
            boolean tee) {
        JsonNode execution = state.operationExecutions().get(operation.operationId());
        if (execution == null || execution.isNull()) {
            return null;
        }
        if (execution.path("active").asBoolean(false)) {
            if (executionIsLive(execution)) {
                return alreadyRunningResult(operation, pid(execution));
            }
            return null;
        }
        if (!execution.hasNonNull("success")) {
            throw new ForgeException("error: dispatch '"
                    + operation.operationId()
                    + "' does not have a completed execution result");
        }
        Map<String, Object> result = readRecordedResult(runDir, execution, operation);
        if (tee) {
            replayRecordedOutput(runDir, execution, "stdout_path");
            replayRecordedOutput(runDir, execution, "stderr_path");
        }
        result.put("status", "already_executed");
        result.put("dispatch_id", operation.operationId());
        result.put("message", "dispatch already executed");
        return result;
    }

    private @Nullable Map<String, Object> recoverStaleRecordedExecution(
            Path runDir,
            String dispatchId,
            boolean tee) {
        Map<String, Object> result = store.mutateRun(runDir, context -> {
            PendingOperation operation = requirePendingDispatch(context.state());
            if (!operation.operationId().equals(dispatchId)) {
                throw new ForgeException("error: pending dispatch is '"
                        + operation.operationId()
                        + "' not '"
                        + dispatchId
                        + "'");
            }
            JsonNode execution = context.state().operationExecutions().get(dispatchId);
            if (execution == null || execution.isNull()
                    || !execution.path("active").asBoolean(false)
                    || executionIsLive(execution)) {
                return null;
            }
            ObjectNode completedExecution = durableCompletedExecution(runDir, operation, execution);
            if (completedExecution != null) {
                String timestamp = TimeSupport.now(clock);
                String eventType = completedExecution.get("success").asBoolean()
                        ? "operation_succeeded"
                        : "operation_failed";
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        nextSeq(context.state()),
                        timestamp,
                        eventType,
                        Map.of("operation_id", dispatchId, "execution", completedExecution)));
                Map<String, Object> payload = new LinkedHashMap<>(Json.mapper().convertValue(completedExecution, MAP_TYPE));
                payload.put("status", "already_executed");
                payload.put("dispatch_id", dispatchId);
                payload.put("message", "dispatch already executed");
                return payload;
            }
            String timestamp = TimeSupport.now(clock);
            ObjectNode failedExecution = execution.deepCopy();
            failedExecution.put("active", false);
            failedExecution.put("launched", execution.path("launched").asBoolean(false));
            failedExecution.put("success", false);
            failedExecution.put("exit_code", -1);
            failedExecution.put("failure_reason", "dispatch execution became inactive before completion");
            failedExecution.put("completed_at", timestamp);
            failedExecution.put("latest_activity_at", timestamp);
            if (!failedExecution.hasNonNull("started_at")) {
                failedExecution.put("started_at", timestamp);
            }

            validateRecordedExecutionPaths(runDir, dispatchId, failedExecution);
            Map<String, Object> payload = new LinkedHashMap<>(Json.mapper().convertValue(failedExecution, MAP_TYPE));
            payload.put("status", "stale_execution_failed");
            payload.put("dispatch_id", dispatchId);
            payload.put("timed_out", false);
            payload.put("message", "dispatch execution became inactive before completion");
            Path resultPath = recordedExecutionPath(runDir, dispatchId, failedExecution, "result_path");
            writeRecoveredRecordedResult(resultPath, payload);

            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    nextSeq(context.state()),
                    timestamp,
                    "operation_failed",
                    Map.of("operation_id", dispatchId, "execution", failedExecution)));
            return payload;
        });
        if (result != null && tee) {
            StoredRunContext context = store.loadRunContext(runDir);
            JsonNode execution = context.state().operationExecutions().get(dispatchId);
            if (execution != null && !execution.isNull()) {
                replayRecordedOutput(runDir, execution, "stdout_path");
                replayRecordedOutput(runDir, execution, "stderr_path");
            }
        }
        return result;
    }

    private static @Nullable ObjectNode durableCompletedExecution(
            Path runDir,
            PendingOperation operation,
            JsonNode activeExecution) {
        Path resultPath = recordedExecutionPath(runDir, operation.operationId(), activeExecution, "result_path");
        if (!Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        JsonNode result = readRecoverableRecordedResultJson(resultPath);
        if (result == null) {
            return null;
        }
        validateRecordedExecutionPaths(runDir, operation.operationId(), activeExecution);
        try {
            return validateCompletedResultSidecar(runDir, operation, resultPath, result);
        } catch (ForgeException ignored) {
            return null;
        }
    }

    private static ObjectNode validateCompletedResult(
            Path runDir,
            PendingOperation operation,
            JsonNode recordedExecution,
            Path resultPath,
            JsonNode result) {
        validateRecordedExecutionPaths(runDir, operation.operationId(), recordedExecution);
        return validateCompletedResultSidecar(runDir, operation, resultPath, result);
    }

    private static ObjectNode validateCompletedResultSidecar(
            Path runDir,
            PendingOperation operation,
            Path resultPath,
            JsonNode result) {
        if (!result.isObject()) {
            throw invalidDurableResult(operation, "result file must contain a JSON object");
        }
        ObjectNode completed = ((ObjectNode) result).deepCopy();
        validateRecordedExecutionPaths(runDir, operation.operationId(), completed);
        String kind = requiredDurableResultText(operation, completed, "kind");
        if (!"process".equals(kind)) {
            throw invalidDurableResult(operation, "kind is '" + kind + "'");
        }
        String operationId = requiredDurableResultText(operation, completed, "operation_id");
        if (!operationId.equals(operation.operationId())) {
            throw invalidDurableResult(operation, "operation_id is '" + operationId + "'");
        }
        String operationKind = requiredDurableResultText(operation, completed, "operation_kind");
        if (!operationKind.equals(operation.kind())) {
            throw invalidDurableResult(operation, "operation_kind is '" + operationKind + "'");
        }
        Path completedResultPath = recordedExecutionPath(runDir, operation.operationId(), completed, "result_path");
        if (!completedResultPath.equals(resultPath)) {
            throw invalidDurableResult(operation, "result_path points at a different file");
        }
        if (!completed.hasNonNull("active") || !completed.get("active").isBoolean() || completed.get("active").asBoolean()) {
            throw invalidDurableResult(operation, "active must be false");
        }
        if (!completed.hasNonNull("success") || !completed.get("success").isBoolean()) {
            throw invalidDurableResult(operation, "success must be boolean");
        }
        JsonNode exitCode = completed.get("exit_code");
        if (exitCode == null || !exitCode.isIntegralNumber() || !exitCode.canConvertToInt()) {
            throw invalidDurableResult(operation, "exit_code must be an integer");
        }
        requiredDurableResultText(operation, completed, "completed_at");
        return completed;
    }

    private static String requiredDurableResultText(PendingOperation operation, JsonNode result, String field) {
        JsonNode value = result.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalidDurableResult(operation, field + " must be a nonblank string");
        }
        return value.asText();
    }

    private static ForgeException invalidDurableResult(PendingOperation operation, String reason) {
        return new ForgeException("error: recorded dispatch result for '"
                + operation.operationId()
                + "' is invalid: "
                + reason);
    }

    private static Map<String, Object> alreadyRunningResult(PendingOperation operation, @Nullable Long pid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "already_running");
        result.put("dispatch_id", operation.operationId());
        result.put("active", true);
        result.put("pid", pid);
        result.put("success", null);
        result.put("timed_out", false);
        result.put("message", "dispatch execution already running");
        return result;
    }

    private static boolean executionIsLive(JsonNode execution) {
        Long processId = pid(execution);
        return processId != null
                && ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean executionAwaitingPidPublication(@Nullable JsonNode execution) {
        return execution != null
                && !execution.isNull()
                && execution.path("active").asBoolean(false)
                && pid(execution) == null;
    }

    private static void sleepBeforeReloadingDispatchExecutionPid() {
        try {
            Thread.sleep(EXECUTION_PID_PUBLICATION_POLL.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ForgeException("interrupted while waiting for dispatch process state", error);
        }
    }

    private static @Nullable Long pid(JsonNode execution) {
        JsonNode pid = execution.get("pid");
        return pid == null || pid.isNull() || !pid.canConvertToLong() ? null : pid.asLong();
    }

    private static Map<String, Object> readRecordedResult(Path runDir, JsonNode execution, PendingOperation operation) {
        String dispatchId = operation.operationId();
        Path resultPath = recordedExecutionPath(runDir, dispatchId, execution, "result_path");
        validateRecordedExecutionOutputPaths(runDir, dispatchId, execution);
        if (Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode result = readRecordedResultJson(resultPath);
            ObjectNode completed = validateCompletedResult(runDir, operation, execution, resultPath, result);
            return new LinkedHashMap<>(Json.mapper().convertValue(completed, MAP_TYPE));
        }
        Map<String, Object> result = new LinkedHashMap<>(Json.mapper().convertValue(execution, MAP_TYPE));
        result.put("status", "executed");
        result.put("dispatch_id", dispatchId);
        result.put("timed_out", false);
        result.put("message", Boolean.TRUE.equals(result.get("success"))
                ? "dispatch completed successfully"
                : "dispatch failed");
        return result;
    }

    private static void replayRecordedOutput(Path runDir, JsonNode execution, String field) {
        Path path = recordedExecutionPath(runDir, text(execution, "operation_id"), execution, field);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            streamRecordedOutput(path);
        } catch (IOException error) {
            throw new ForgeException("failed to replay recorded dispatch output " + path, error);
        }
    }

    private static void validateRecordedExecutionPaths(Path runDir, String dispatchId, JsonNode execution) {
        recordedExecutionPath(runDir, dispatchId, execution, "result_path");
        validateRecordedExecutionOutputPaths(runDir, dispatchId, execution);
    }

    private static void validateRecordedExecutionOutputPaths(Path runDir, String dispatchId, JsonNode execution) {
        recordedExecutionPath(runDir, dispatchId, execution, "progress_path");
        recordedExecutionPath(runDir, dispatchId, execution, "stdout_path");
        recordedExecutionPath(runDir, dispatchId, execution, "stderr_path");
    }

    private static Path recordedExecutionPath(
            Path runDir,
            String dispatchId,
            JsonNode execution,
            String field) {
        JsonNode value = execution.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalidRecordedExecutionPath(dispatchId, field, "must be a nonblank run-relative string");
        }
        Path relative;
        try {
            relative = Path.of(value.asText());
        } catch (InvalidPathException error) {
            throw invalidRecordedExecutionPath(dispatchId, field, "is not a valid run-relative path: " + value.asText());
        }
        if (relative.isAbsolute()) {
            throw invalidRecordedExecutionPath(dispatchId, field, "must be run-relative, not absolute: " + value.asText());
        }
        Path root = absolutePath(runDir);
        Path resolved = root.resolve(relative).normalize();
        if (resolved.equals(root)) {
            throw invalidRecordedExecutionPath(dispatchId, field, "must name a file below the run directory");
        }
        if (!resolved.startsWith(root)) {
            throw invalidRecordedExecutionPath(dispatchId, field, "must stay inside the run directory: " + value.asText());
        }
        rejectRecordedExecutionSymlinkComponents(root, resolved, dispatchId, field);
        return resolved;
    }

    private static void rejectRecordedExecutionSymlinkComponents(
            Path root,
            Path resolved,
            String dispatchId,
            String field) {
        Optional<Path> symlink = firstSymlinkComponent(root, resolved);
        if (symlink.isPresent()) {
            throw invalidRecordedExecutionPath(dispatchId, field, "must not include symlink components: "
                    + root.relativize(symlink.get()));
        }
    }

    private static Optional<Path> firstSymlinkComponent(Path root, Path resolved) {
        Path current = root;
        for (Path name : root.relativize(resolved)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private static ForgeException invalidRecordedExecutionPath(String dispatchId, String field, String reason) {
        String id = dispatchId.isBlank() ? "unknown" : dispatchId;
        return new ForgeException("error: recorded dispatch execution path '"
                + field
                + "' for '"
                + id
                + "' "
                + reason);
    }

    private static JsonNode readRecordedResultJson(Path path) {
        try {
            return readRecordedResultJsonUnchecked(path);
        } catch (IOException error) {
            throw new ForgeException("failed to read recorded dispatch result " + path, error);
        }
    }

    private static @Nullable JsonNode readRecoverableRecordedResultJson(Path path) {
        try {
            return readRecordedResultJsonUnchecked(path);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static JsonNode readRecordedResultJsonUnchecked(Path path) throws IOException {
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(
                path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return Json.mapper().readValue(input, JsonNode.class);
        }
    }

    private static void streamRecordedOutput(Path path) throws IOException {
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(
                path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            input.transferTo(System.err);
        }
    }

    private static void writeRecoveredRecordedResult(Path path, Object value) {
        Path parent = path.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        byte[] content = Json.toPrettyJson(value).getBytes(StandardCharsets.UTF_8);
        try (SeekableByteChannel channel = Files.newByteChannel(
                path,
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS))) {
            channel.write(ByteBuffer.wrap(content));
        } catch (IOException error) {
            throw new ForgeException("failed to write recorded dispatch result " + path, error);
        }
    }

    private final class DispatchProgressObserver implements Processes.ProgressObserver {
        private final Path runDir;
        private final PendingOperation operation;
        private final String startedAt;
        private final Path stdout;
        private final Path stderr;
        private final Path resultPath;
        private final Path progress;
        private @Nullable Long pid;
        private final DispatchProgressCursor progressCursor;
        private ProgressStats lastStats = new ProgressStats(0, 0);
        private long lastExecutionEventSeq;
        private boolean heartbeatAppendDisabled;

        private DispatchProgressObserver(
                Path runDir,
                PendingOperation operation,
                String startedAt,
                Path stdout,
                Path stderr,
                Path resultPath,
                Path progress) {
            this.runDir = runDir;
            this.operation = operation;
            this.startedAt = startedAt;
            this.stdout = stdout;
            this.stderr = stderr;
            this.resultPath = resultPath;
            this.progress = progress;
            this.progressCursor = new DispatchProgressCursor(progress);
        }

        @Override
        public void starting() {
            lastExecutionEventSeq = store.mutateRun(runDir, context -> {
                PendingOperation current = requirePendingDispatch(context.state());
                if (!operation.operationId().equals(current.operationId())) {
                    throw new ForgeException("error: pending dispatch is '"
                            + current.operationId()
                            + "' not '"
                            + operation.operationId()
                            + "'");
                }
                JsonNode existing = context.state().operationExecutions().get(operation.operationId());
                if (existing != null && existing.path("active").asBoolean(false) && executionIsLive(existing)) {
                    throw new ForgeException("error: dispatch '" + operation.operationId() + "' is already running");
                }
                long seq = nextSeq(context.state());
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq,
                        startedAt,
                        "operation_started",
                        Map.of(
                                "operation_id", operation.operationId(),
                                "execution", processExecutionState(
                                        runDir,
                                        operation,
                                        startedAt,
                                        null,
                                        stdout,
                                        stderr,
                                        resultPath,
                                        progress,
                                        0,
                                        0,
                                        0,
                                        0,
                                        true,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))));
                return seq;
            });
        }

        @Override
        public void started(long pid) {
            this.pid = pid;
            if (!appendExecutionHeartbeat(new ProgressStats(0, 0), TimeSupport.now(clock))) {
                throw new ForgeException("error: failed to record dispatch '" + operation.operationId() + "' process launch");
            }
        }

        @Override
        public void observe() {
            if (heartbeatAppendDisabled) {
                return;
            }
            ProgressStats stats = progressCursor.refresh();
            if (!stats.hasProgress() || stats.equals(lastStats)) {
                return;
            }
            lastStats = stats;
            if (!appendExecutionHeartbeat(stats, TimeSupport.now(clock))) {
                heartbeatAppendDisabled = true;
            }
        }

        private boolean appendExecutionHeartbeat(ProgressStats stats, String timestamp) {
            if (lastExecutionEventSeq == 0L) {
                return false;
            }
            long seq = lastExecutionEventSeq + 1L;
            boolean appended = store.appendEventAfter(
                    runDir,
                    lastExecutionEventSeq,
                    EventEnvelope.of(
                            seq,
                            timestamp,
                            "operation_heartbeat",
                            Map.of(
                                    "operation_id", operation.operationId(),
                                    "execution", processExecutionState(
                                            runDir,
                                            operation,
                                            startedAt,
                                            null,
                                            stdout,
                                            stderr,
                                            resultPath,
                                            progress,
                                            0,
                                            0,
                                            stats.bytes(),
                                            stats.entries(),
                                            true,
                                            true,
                                            pid,
                                            null,
                                            null,
                                            null,
                                            timestamp))));
            if (appended) {
                lastExecutionEventSeq = seq;
            }
            return appended;
        }

        private @Nullable Long pid() {
            return pid;
        }

        private ProgressStats progressStats() {
            return progressCursor.refresh();
        }
    }

    private static Map<String, Object> processExecutionState(
            Path runDir,
            PendingOperation operation,
            String startedAt,
            @Nullable String completedAt,
            Path stdout,
            Path stderr,
            Path resultPath,
            Path progress,
            long stdoutBytes,
            long stderrBytes,
            long progressBytes,
            long progressEntries,
            boolean active,
            boolean launched,
            @Nullable Long pid,
            @Nullable Boolean success,
            @Nullable Integer exitCode,
            @Nullable String failureReason,
            @Nullable String lastHeartbeatAt) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("kind", "process");
        execution.put("operation_id", operation.operationId());
        execution.put("operation_kind", operation.kind());
        execution.put("started_at", startedAt);
        String latestActivityAt = completedAt != null
                ? completedAt
                : lastHeartbeatAt == null ? startedAt : lastHeartbeatAt;
        execution.put("latest_activity_at", latestActivityAt);
        execution.put("latest_output_at", progressEntries > 0 ? latestActivityAt : completedAt);
        execution.put("progress_path", runLocalPath(runDir, progress));
        execution.put("stdout_path", runLocalPath(runDir, stdout));
        execution.put("stderr_path", runLocalPath(runDir, stderr));
        execution.put("result_path", runLocalPath(runDir, resultPath));
        execution.put("stdout_bytes", stdoutBytes);
        execution.put("stderr_bytes", stderrBytes);
        execution.put("progress_bytes", progressBytes);
        execution.put("progress_entries", progressEntries);
        execution.put("latest_progress_at", progressEntries > 0 ? latestActivityAt : null);
        execution.put("last_heartbeat_at", lastHeartbeatAt);
        execution.put("active", active);
        execution.put("pid", pid);
        execution.put("launched", launched);
        execution.put("success", success);
        execution.put("exit_code", exitCode);
        execution.put("failure_reason", failureReason);
        execution.put("completed_at", completedAt);
        return execution;
    }

    private static void ensureOutputParents(List<Path> outputPaths) {
        for (Path outputPath : outputPaths) {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Filesystem.ensureDirectory(parent);
            }
        }
    }

    private static byte[] stdinBytes(@Nullable Path path) {
        if (path == null) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new ForgeException("failed to read dispatch stdin file " + path, error);
        }
    }

    private static void writeBytes(Path path, byte[] bytes) {
        Path parent = path.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        try {
            Files.write(path, bytes);
        } catch (IOException error) {
            throw new ForgeException("failed to write " + path, error);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static long nextSeq(DerivedRunState state) {
        return state.lastEventSeq() == null ? 1 : state.lastEventSeq() + 1;
    }

    private static Path absolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String runLocalPath(Path runDir, Path path) {
        Path root = runDir.toAbsolutePath().normalize();
        Path value = path.toAbsolutePath().normalize();
        if (!value.startsWith(root)) {
            throw new ForgeException("internal error: run-owned path is outside run directory: " + path);
        }
        return root.relativize(value).toString().replace('\\', '/');
    }

    private record ExecutionClaim(
            PendingOperation operation,
            @Nullable Map<String, Object> recordedResult
    ) {
    }

    private record PreparedDispatchLaunch(
            List<String> command,
            @Nullable Path cwd,
            Map<String, String> env,
            @Nullable Path stdinPath,
            List<Path> outputPaths,
            Duration timeout
    ) {
        private PreparedDispatchLaunch {
            command = List.copyOf(command);
            env = Map.copyOf(new TreeMap<>(env));
            outputPaths = List.copyOf(outputPaths);
        }
    }
}
