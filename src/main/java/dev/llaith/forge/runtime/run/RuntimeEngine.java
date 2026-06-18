package dev.llaith.forge.runtime.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.runtime.dispatch.DispatchLifecycle;
import dev.llaith.forge.runtime.subrun.SubrunLifecycle;
import dev.llaith.forge.spec.ToolRequirementChecker;
import dev.llaith.forge.spec.WorkflowSpecCompiler;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.storage.AdvisoryLock;
import dev.llaith.forge.storage.ArtifactStore;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.FilesystemRunStore;
import dev.llaith.forge.storage.ProjectionBacklogEntry;
import dev.llaith.forge.storage.RunPaths;
import dev.llaith.forge.storage.StoredRunContext;
import dev.llaith.forge.template.TemplateBundle;
import dev.llaith.forge.template.Templates;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Hashing;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.util.PathSupport;
import dev.llaith.forge.util.TextSupport;
import dev.llaith.forge.util.TimeSupport;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.planner.WorkflowPlanner;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import dev.llaith.forge.workflow.runner.PendingDispatch;
import dev.llaith.forge.workflow.runner.PendingHumanReview;
import dev.llaith.forge.workflow.runner.PendingSubrun;
import dev.llaith.forge.workflow.runner.RunAction;
import dev.llaith.forge.workflow.runner.WorkflowRunner;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.ArtifactRole;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.PendingOperation;
import dev.llaith.forge.workflow.state.RunStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class RuntimeEngine {
    private final FilesystemRunStore store;
    private final DispatchLifecycle dispatchLifecycle;
    private final SubrunLifecycle subrunLifecycle;
    private final RuntimeAdvancement advancement;
    private final Clock clock;

    public RuntimeEngine() {
        this(new FilesystemRunStore(), Clock.system(ZoneId.systemDefault()));
    }

    RuntimeEngine(FilesystemRunStore store, Clock clock) {
        this.store = store;
        this.dispatchLifecycle = new DispatchLifecycle(clock);
        this.subrunLifecycle = new SubrunLifecycle(clock);
        this.advancement = new RuntimeAdvancement(RuntimeEngine::notificationOperationFor);
        this.clock = clock;
    }

    public void init(InitOptions options) {
        Path stagingLock = RunPaths.stagingLockPath(options.runsDir, options.slug);
        Optional<AdvisoryLock> acquired = AdvisoryLock.tryAcquire(
                stagingLock,
                "forge run init " + options.slug.asString());
        if (acquired.isEmpty()) {
            throw new ForgeException("error: another init is already in progress for slug '"
                    + options.slug.asString()
                    + "'; cannot proceed while a live init holds the staging lock");
        }
        try (AdvisoryLock ignored = acquired.get()) {
            Path runDir = RunPaths.runDir(options.runsDir, options.slug);
            if (Files.exists(runDir)) {
                if (!options.force) {
                    throw new ForgeException("error: run already exists at " + runDir + "; use --force to replace it");
                }
            }
            Path stagingDir = RunPaths.stagingDir(options.runsDir, options.slug);
            if (Files.exists(stagingDir)) {
                deleteRecursively(stagingDir);
            }
            boolean published = false;
            try {
                Filesystem.ensureDirectory(stagingDir);
                WorkflowSpecCompiler.CompiledWorkflow compiled = WorkflowSpecCompiler.compileFrozenRunPackage(
                        options.spec,
                        runDir,
                        stagingDir,
                        RuntimeEngine::resolveTemplateWorkflow);
                requireAvailableTools(compiled.spec(), stagingDir);
                WorkflowSpec spec = compiled.spec();
                JsonNode workflowIr = compiled.workflowIr();
                JsonNode workflowInterface = compiled.workflowInterface();
                String requestArtifact = requestArtifactName(workflowInterface);

                List<ArtifactRecord> artifacts = new ArrayList<>();
                Map<String, ArtifactMetadata> metadata = new TreeMap<>();
                for (Map.Entry<String, Path> entry : options.artifacts.entrySet()) {
                    String name = entry.getKey();
                    Path source = entry.getValue();
                    Path publishedTarget = PathSupport.canonicalArtifactPath(runDir, name, source.toString());
                    Path stagedTarget = PathSupport.canonicalArtifactPath(stagingDir, name, source.toString());
                    copy(source, stagedTarget);
                    ArtifactRecord record = new ArtifactRecord(
                            name,
                            publishedTarget.toString(),
                            null,
                            mediaTypeFor(stagedTarget));
                    artifacts.add(record);
                    metadata.put(name, new ArtifactMetadata(
                            Hashing.sha256Hex(stagedTarget),
                            ArtifactBinding.local(),
                            requestArtifact.equals(name) ? ArtifactRole.REQUEST : ArtifactRole.STANDARD));
                }

                store.writeFrozenRunFiles(stagingDir, spec, workflowIr, workflowInterface);
                store.appendEvents(stagingDir, WorkflowRunner.planInitEvents(
                        spec,
                        options.slug,
                        artifacts,
                        metadata,
                        TimeSupport.now(clock)));
                promoteStagedRun(stagingDir, runDir, options.force);
                published = true;
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "initialized");
                response.put("slug", options.slug.asString());
                response.put("run_dir", runDir.toString());
                response.put("entry_node", spec.entryNode());
                System.out.print(Json.toPrettyJson(response));
            } finally {
                if (!published && Files.exists(stagingDir)) {
                    deleteRecursively(stagingDir);
                }
            }
        }
    }

    private static void requireAvailableTools(WorkflowSpec rootSpec, Path stagingDir) {
        List<String> failures = new ArrayList<>();
        appendToolRequirementFailures(failures, "workflow '" + rootSpec.workflowId() + "'", rootSpec);
        Path frozenSubruns = RunPaths.subrunFrozenWorkflowsDir(stagingDir);
        if (Files.isDirectory(frozenSubruns)) {
            for (Path specPath : frozenSubrunSpecPaths(frozenSubruns)) {
                WorkflowSpec childSpec = WorkflowSpecs.load(specPath);
                appendToolRequirementFailures(
                        failures,
                        "child workflow '" + childSpec.workflowId() + "' at " + stagingDir.relativize(specPath),
                        childSpec);
            }
        }
        if (!failures.isEmpty()) {
            throw new ForgeException("error: workflow tool requirements are not satisfied; "
                    + String.join("; ", failures));
        }
    }

    private static List<Path> frozenSubrunSpecPaths(Path frozenSubruns) {
        try (Stream<Path> stream = Files.find(
                frozenSubruns,
                Integer.MAX_VALUE,
                (path, attributes) -> {
                    Path fileName = path.getFileName();
                    return attributes.isRegularFile()
                            && fileName != null
                            && "spec.json".equals(fileName.toString());
                })) {
            return stream.sorted().toList();
        } catch (IOException error) {
            throw new ForgeException("failed to inspect frozen subrun workflows under " + frozenSubruns, error);
        }
    }

    private static void appendToolRequirementFailures(
            List<String> failures,
            String label,
            WorkflowSpec spec) {
        if (spec.effectiveToolRequirements().isEmpty()) {
            return;
        }
        ToolRequirementChecker.Report report = ToolRequirementChecker.check(spec.effectiveToolRequirements());
        if (!report.satisfied()) {
            failures.add(label + ": " + String.join(", ", report.missingDescriptions()));
        }
    }

    public void next(Locator locator) {
        RunAction action = appendProjectedAction(locator);
        DerivedRunState state = loadDerivedState(RunPaths.eventsPath(runDir(locator)));
        System.out.print(Json.toPrettyJson(rustActionPayload(action, state)));
    }

    public void status(Locator locator) {
        StoredRunContext context = load(locator);
        RunAction projected = projectOrNoop(context);
        StringBuilder out = new StringBuilder();
        out.append("Run: ").append(locator.slug.asString()).append('\n');
        out.append("Status: ").append(statusText(context.state())).append('\n');
        if (context.state().currentNode() != null) {
            out.append("Node: ").append(context.state().currentNode()).append('\n');
        }
        out.append("Action: ").append(projected.type()).append('\n');
        if (projected.dispatch() != null) {
            out.append("Dispatch: ").append(projected.dispatch().dispatchId()).append('\n');
        }
        System.out.print(out);
    }

    public void showProgress(Locator locator) {
        StoredRunContext context = load(locator);
        System.out.print(Json.toPrettyJson(progressPayload(locator, context)));
    }

    public void showHuman(Locator locator) {
        StoredRunContext context = load(locator);
        System.out.print(Json.toPrettyJson(humanReviewPayload(locator, context)));
    }

    public void recover(Locator locator) {
        Path runDir = runDir(locator);
        load(locator);
        List<ProjectionBacklogEntry> recovered = store.recoverProjectionBacklog(runDir);
        System.out.print(Json.toPrettyJson(Map.of(
                "status", recovered.isEmpty() ? "no_projection_backlog" : "projection_backlog_recovered",
                "projection_backlog", recovered
        )));
    }

    public void watch(WatchOptions options) {
        while (true) {
            StoredRunContext context = load(options.locator);
            emitProgressFrame(options.locator, context, renderMode(options));
            if (options.untilTerminal && isTerminal(context.state().runStatus())) {
                return;
            }
            sleep(options.intervalMs);
        }
    }

    public void auto(AutoOptions options) {
        Path runDir = RunPaths.runDir(options.locator.runsDir, options.locator.slug);
        store.loadRunContext(runDir);
        Path autoLock = RunPaths.autoLockPath(runDir);
        rejectLegacyAutoLock(runDir, autoLock);
        Optional<AdvisoryLock> acquired = AdvisoryLock.tryAcquire(autoLock, "forge run auto");
        if (acquired.isEmpty()) {
            throw new ForgeException("error: auto runner already active for " + runDir);
        }
        try (AdvisoryLock ignored = acquired.get()) {
            AutoResult result = runAutoLoop(options);
            StoredRunContext finalContext = load(options.locator);
            if ("terminal".equals(result.status)) {
                emitAutoTerminalFrame(finalContext, options.watchMode);
                if (finalContext.state().runStatus() != RunStatus.COMPLETED) {
                    throw new ForgeException("error: " + terminalFailureReason(finalContext.state()));
                }
            } else if ("human_review".equals(result.status)) {
                emitAutoHumanFrame(options.locator, finalContext, options.watchMode);
            }
        }
    }

    public void resolveHuman(ResolveHumanOptions options) {
        StoredRunContext context = load(options.locator);
        PendingHumanReview review = requireHumanReview(context);
        validateHumanFields(review, options.fields);
        RunAction projected = projectedAfterHumanResolution(context, options.fields);
        if (options.dryRun) {
            Map<String, Object> projectedState = new LinkedHashMap<>();
            projectedState.put("current_node", projectedCurrentNode(context, projected));
            projectedState.put("run_status", projectedRunStatus(projected));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "human_resolution_valid");
            response.put("dry_run", true);
            response.put("node_id", review.nodeId());
            response.put("field_schema", review.fields());
            response.put("instructions", review.instructions());
            response.put("fields", options.fields);
            response.put("projected_action", rustActionPayload(projected, context.state()));
            response.put("projected_state", projectedState);
            response.put("mutates_run", false);
            System.out.print(Json.toPrettyJson(response));
            return;
        }

        Map<String, Object> response = store.mutateRun(runDir(options.locator), locked -> {
            PendingHumanReview lockedReview = requireHumanReview(locked);
            validateHumanFields(lockedReview, options.fields);
            long seq = nextSeq(locked.state());
            String timestamp = TimeSupport.now(clock);
            EventLog.appendEvent(locked.eventsPath(), EventEnvelope.of(seq++, timestamp, "human_input_recorded", Map.of(
                    "node_id", lockedReview.nodeId(),
                    "fields", options.fields
            )));
            PendingOperation operation = locked.state().pendingOperation();
            if (operation != null && "human_review".equals(operation.kind())) {
                EventLog.appendEvent(locked.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_completed",
                        Map.of("operation_id", operation.operationId())));
            }
            advancement.appendRouteEvents(locked, seq, timestamp, options.fields);
            DerivedRunState finalState = loadDerivedState(locked.eventsPath());
            return Map.of(
                "status", "human_resolved",
                "current_node", finalState.currentNode() == null ? "" : finalState.currentNode(),
                "run_status", statusText(finalState)
            );
        });
        System.out.print(Json.toPrettyJson(response));
    }

    public void execDispatch(ExecOptions options) {
        Map<String, Object> resultJson = executePendingDispatch(options.locator, options.tee);
        System.out.print(Json.toPrettyJson(resultJson));
    }

    Map<String, Object> executePendingDispatch(Locator locator, boolean tee) {
        return dispatchLifecycle.executePendingDispatch(runDir(locator), tee);
    }

    public void completeDispatch(DispatchCompletionOptions options) {
        Map<String, Object> response = completePreparedDispatch(options.locator, options.dispatchId, options.decisions);
        System.out.print(Json.toPrettyJson(response));
    }

    private Map<String, Object> completePreparedDispatch(
            Locator locator,
            String dispatchId,
            Map<String, String> decisions) {
        return store.mutateRun(runDir(locator), context -> {
            String status = completePreparedDispatchUnlocked(context, dispatchId, decisions);
            DerivedRunState finalState = loadDerivedState(context.eventsPath());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", status);
            response.put("dispatch_id", dispatchId);
            response.put("run_status", statusText(finalState));
            response.put("current_node", finalState.currentNode());
            return response;
        });
    }

    private String completePreparedDispatchUnlocked(
            StoredRunContext context,
            String dispatchId,
            Map<String, String> decisions) {
        PendingOperation operation = DispatchLifecycle.requirePendingDispatch(context.state());
        if (!operation.operationId().equals(dispatchId)) {
            throw new ForgeException("error: pending dispatch is '" + operation.operationId() + "' not '" + dispatchId + "'");
        }
        DispatchLifecycle.requireRecordedDispatchExecution(context.state(), operation, true);
        if ("notification".equals(operation.kind())) {
            completePreparedNotificationUnlocked(context, operation);
            return "notification_delivered";
        }
        long seq = nextSeq(context.state());
        String timestamp = TimeSupport.now(clock);
        JsonNode node = currentNode(context.spec(), context.state());
        Map<String, String> resolvedDecisions = resolveDispatchDecisions(context, node, decisions);
        List<ArtifactRecord> outputArtifacts = completedOutputArtifacts(context, node);
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                seq++,
                timestamp,
                "operation_completed",
                Map.of("operation_id", operation.operationId())));
        for (ArtifactRecord artifact : outputArtifacts) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq++,
                    timestamp,
                    "artifact_written",
                    Map.of("artifact", artifact)));
        }
        for (Map.Entry<String, String> decision : resolvedDecisions.entrySet()) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "decision_recorded", Map.of(
                    "key", decision.getKey(),
                    "value", decision.getValue()
            )));
        }
        seq = advancement.appendRouteEvents(context, seq, timestamp, resolvedDecisions);
        for (ArtifactRecord artifact : outputArtifacts) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq++,
                    timestamp,
                    "artifact_metadata_recorded",
                    Map.of(
                            "artifact_name", artifact.name(),
                            "blob_id", Hashing.sha256Hex(Path.of(artifact.path())),
                            "binding", ArtifactBinding.local(),
                            "role", ArtifactRole.STANDARD)));
        }
        return "dispatch_completed";
    }

    public void failDispatch(DispatchFailureOptions options) {
        Map<String, Object> response = failPreparedDispatch(options.locator, options.dispatchId, options.reason, options.retryable);
        System.out.print(Json.toPrettyJson(response));
    }

    private Map<String, Object> failPreparedDispatch(
            Locator locator,
            String dispatchId,
            String reason,
            boolean retryable) {
        return store.mutateRun(runDir(locator), context -> {
            DispatchFailureResult result = failPreparedDispatchUnlocked(context, dispatchId, reason, retryable);
            DerivedRunState finalState = loadDerivedState(context.eventsPath());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("dispatch_id", dispatchId);
            response.put("retryable", retryable);
            response.put("current_node", finalState.currentNode());
            response.put("run_status", statusText(finalState));
            response.put("status", result.status());
            response.put("notification_level", result.notificationLevel());
            response.put("node_id", result.nodeId());
            response.put("attempt", result.attempt());
            response.put("max_attempts", result.maxAttempts());
            return response;
        });
    }

    private DispatchFailureResult failPreparedDispatchUnlocked(
            StoredRunContext context,
            String dispatchId,
            String reason,
            boolean retryable) {
        return failPreparedDispatchUnlocked(context, dispatchId, reason, retryable, true);
    }

    private DispatchFailureResult failPreparedDispatchUnlocked(
            StoredRunContext context,
            String dispatchId,
            String reason,
            boolean retryable,
            boolean requireFailedExecution) {
        PendingOperation operation = DispatchLifecycle.requirePendingDispatch(context.state());
        if (!operation.operationId().equals(dispatchId)) {
            throw new ForgeException("error: pending dispatch is '" + operation.operationId() + "' not '" + dispatchId + "'");
        }
        if (requireFailedExecution) {
            DispatchLifecycle.requireRecordedDispatchExecution(context.state(), operation, false);
        }
        if ("notification".equals(operation.kind())) {
            String level = failPreparedNotificationUnlocked(context, operation, reason);
            return DispatchFailureResult.notification(level);
        }
        long seq = nextSeq(context.state());
        String timestamp = TimeSupport.now(clock);
        JsonNode node = currentNode(context.spec(), context.state());
        String nodeId = requiredText(node, "id");
        long currentAttempt = context.state().nodeVisitCounts().getOrDefault(nodeId, 1L);
        long maxAttempts = Math.max(1L, retryPolicyMaxAttempts(node));
        boolean shouldRetry = retryable && currentAttempt < maxAttempts;
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "node_failed", Map.of(
                "node_id", nodeId,
                "reason", reason,
                "retryable", shouldRetry
        )));
        if (shouldRetry) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "node_entered", Map.of(
                    "node_id", nodeId)));
        } else {
            String escalationReason = retryable
                    ? "node '" + nodeId + "' failed after " + currentAttempt + " attempt(s): " + reason
                    : "node '" + nodeId + "' failed without retry: " + reason;
            RunAction action = RunAction.escalate(escalationReason);
            advancement.appendTerminalEventAndNotification(context, seq, timestamp, action);
        }
        return DispatchFailureResult.node(nodeId, currentAttempt, maxAttempts, shouldRetry);
    }

    private void completePreparedNotificationUnlocked(StoredRunContext context, PendingOperation operation) {
        long seq = nextSeq(context.state());
        String timestamp = TimeSupport.now(clock);
        String level = requiredText(operation.payload(), "level");
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "notification_delivered", Map.of(
                "notification_id", operation.operationId(),
                "level", level)));
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "operation_completed", Map.of(
                "operation_id", operation.operationId())));
        if ("complete".equals(level)) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "run_completed", Map.of(
                    "message", textOr(operation.payload(), "message", "run complete"))));
        } else if ("escalate".equals(level)) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "run_escalated", Map.of(
                    "reason", textOr(operation.payload(), "message", "workflow escalated"))));
        }
    }

    private String failPreparedNotificationUnlocked(StoredRunContext context, PendingOperation operation, String reason) {
        long seq = nextSeq(context.state());
        String timestamp = TimeSupport.now(clock);
        String level = requiredText(operation.payload(), "level");
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "notification_failed", Map.of(
                "notification_id", operation.operationId(),
                "level", level,
                "reason", reason)));
        EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq++, timestamp, "operation_completed", Map.of(
                "operation_id", operation.operationId())));
        if ("complete".equals(level) || "escalate".equals(level)) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(seq, timestamp, "node_failed", Map.of(
                    "node_id", context.state().currentNode() == null ? "" : context.state().currentNode(),
                    "reason", reason,
                    "retryable", false)));
        }
        return level;
    }

    private AutoResult runAutoLoop(AutoOptions options) {
        while (true) {
            StoredRunContext context = load(options.locator);
            if (options.watchMode != null) {
                emitProgressFrame(options.locator, context, renderMode(options.watchMode));
            }
            if (isTerminal(context.state().runStatus())) {
                return new AutoResult("terminal", context.state());
            }
            PendingOperation pending = context.state().pendingOperation();
            if (pending != null) {
                if ("node_dispatch".equals(pending.kind()) || "notification".equals(pending.kind())) {
                    Map<String, Object> result = executePendingDispatch(options.locator, options.tee);
                    if ("already_running".equals(result.get("status"))) {
                        throw new ForgeException("error: dispatch '"
                                + pending.operationId()
                                + "' is already running; inspect `forge run show-progress`");
                    }
                    if (Boolean.TRUE.equals(result.get("success"))) {
                        try {
                            completePreparedDispatch(options.locator, pending.operationId(), Map.of());
                        } catch (ForgeException error) {
                            failPreparedDispatchAfterCompletionRejection(
                                    options.locator,
                                    pending.operationId(),
                                    "dispatch completion rejected: " + error.getMessage());
                        }
                    } else {
                        failPreparedDispatch(options.locator, pending.operationId(),
                                dispatchFailureReason(result),
                                true);
                    }
                    continue;
                }
                if ("subrun".equals(pending.kind())) {
                    if (advancePendingSubrun(options.locator)) {
                        continue;
                    }
                    return new AutoResult("blocked", loadDerivedState(RunPaths.eventsPath(runDir(options.locator))));
                }
                if ("human_review".equals(pending.kind())) {
                    return new AutoResult("human_review", context.state());
                }
                throw new ForgeException("error: unsupported pending operation kind '" + pending.kind() + "'");
            }

            RunAction action = WorkflowPlanner.project(context.spec(), context.state());
            switch (action.type()) {
                case "dispatch", "subrun" -> {
                    appendProjectedAction(options.locator);
                    continue;
                }
                case "human_review" -> {
                    RunAction prepared = appendProjectedAction(options.locator);
                    if ("dispatch".equals(prepared.type())) {
                        continue;
                    }
                    return new AutoResult(
                            "human_review",
                            loadDerivedState(RunPaths.eventsPath(runDir(options.locator))));
                }
                case "complete", "escalate" -> {
                    RunAction prepared = appendProjectedAction(options.locator);
                    if ("dispatch".equals(prepared.type())) {
                        continue;
                    }
                    return new AutoResult("terminal", loadDerivedState(RunPaths.eventsPath(runDir(options.locator))));
                }
                case "noop" -> {
                    return new AutoResult("idle", context.state());
                }
                default -> throw new ForgeException("error: unsupported projected action '" + action.type() + "'");
            }
        }
    }

    private Map<String, Object> failPreparedDispatchAfterCompletionRejection(
            Locator locator,
            String dispatchId,
            String reason) {
        return store.mutateRun(runDir(locator), context -> {
            DispatchFailureResult result = failPreparedDispatchUnlocked(context, dispatchId, reason, true, false);
            DerivedRunState finalState = loadDerivedState(context.eventsPath());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("dispatch_id", dispatchId);
            response.put("retryable", true);
            response.put("current_node", finalState.currentNode());
            response.put("run_status", statusText(finalState));
            response.put("status", result.status());
            response.put("notification_level", result.notificationLevel());
            response.put("node_id", result.nodeId());
            response.put("attempt", result.attempt());
            response.put("max_attempts", result.maxAttempts());
            return response;
        });
    }

    private RunAction appendProjectedAction(Locator locator) {
        return store.mutateRun(runDir(locator), context -> {
            if (context.state().pendingOperation() != null || isTerminal(context.state().runStatus())) {
                return WorkflowPlanner.project(context.spec(), context.state());
            }
            RunAction action = WorkflowPlanner.project(context.spec(), context.state());
            appendPreparedActionUnlocked(context, action);
            DerivedRunState projected = loadDerivedState(context.eventsPath());
            return WorkflowPlanner.project(context.spec(), projected);
        });
    }

    private boolean advancePendingSubrun(Locator parentLocator) {
        return subrunLifecycle.advancePendingSubrun(
                runDir(parentLocator),
                this::advanceChildSubrun,
                advancement::appendRouteEvents);
    }

    private void advanceChildSubrun(Path childRunDir, String childSlug) {
        Path childRunsDir = childRunDir.getParent();
        if (childRunsDir == null) {
            throw new ForgeException("child run '" + childSlug + "' has no runs directory");
        }
        runAutoLoop(new AutoOptions(
                new Locator(childRunsDir, RunSlug.parse(childSlug)),
                null,
                false));
    }

    private RunAction appendPreparedActionUnlocked(StoredRunContext context, RunAction action) {
        long seq = nextSeq(context.state());
        String timestamp = TimeSupport.now(clock);
        ObjectNode notification = notificationOperationFor(context, action);
        if (notification != null && "human_review".equals(action.type())) {
            EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq,
                    timestamp,
                    "operation_prepared",
                    Map.of("operation", notification)));
            return new RunAction(
                    "dispatch",
                    null,
                    null,
                    null,
                    textOr(notification, "message", "notification dispatch prepared"),
                    null,
                    null);
        }
        switch (action.type()) {
            case "dispatch" -> EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq,
                    timestamp,
                    "operation_prepared",
                    Map.of("operation", operationFor(context, action.dispatch()))));
            case "human_review" -> EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq,
                    timestamp,
                    "operation_prepared",
                    Map.of("operation", operationFor(action.humanReview()))));
            case "subrun" -> EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                    seq,
                    timestamp,
                    "operation_prepared",
                    Map.of("operation", operationFor(context, action.subrun()))));
            case "complete", "escalate" -> advancement.appendTerminalEventAndNotification(context, seq, timestamp, action);
            default -> {
            }
        }
        return action;
    }

    private Map<String, Object> progressPayload(Locator locator, StoredRunContext context) {
        RunAction action = actionForObserver(context);
        PendingOperation pending = context.state().pendingOperation();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("current_node", context.state().currentNode());
        payload.put("run_status", statusText(context.state()));
        payload.put("terminal_state", terminalState(context.state().runStatus()));
        payload.put("readiness", pending == null ? "projected_only" : "prepared");
        payload.put("operator_hint", operatorHint(context.state(), action));
        payload.put("action", actionPayload(action, pending));
        payload.put("pending_dispatch_id", pending != null && DispatchLifecycle.isDispatchOperation(pending)
                ? pending.operationId()
                : dispatchId(action));
        payload.put("pending_dispatch_kind", pendingDispatchKind(pending, action));
        payload.put("last_event_seq", context.state().lastEventSeq());
        payload.put("projected_action", action.type());
        payload.put("pending_operation", pending == null ? "" : pending.operationId());
        payload.put("pending_execution", pending == null
                ? null
                : context.state().operationExecutions().get(pending.operationId()));
        payload.put("projection_backlog", store.projectionBacklog(context.runDir()));
        payload.put("run_dir", RunPaths.runDir(locator.runsDir, locator.slug).toString());
        return payload;
    }

    private Map<String, Object> progressSummaryPayload(Locator locator, StoredRunContext context) {
        Map<String, Object> full = progressPayload(locator, context);
        Map<String, Object> summary = new LinkedHashMap<>();
        copyIfPresent(full, summary, "current_node");
        copyIfPresent(full, summary, "run_status");
        copyIfPresent(full, summary, "terminal_state");
        copyIfPresent(full, summary, "readiness");
        copyIfPresent(full, summary, "operator_hint");
        copyIfPresent(full, summary, "pending_dispatch_id");
        copyIfPresent(full, summary, "pending_dispatch_kind");
        copyIfPresent(full, summary, "action");
        return summary;
    }

    private Map<String, Object> humanReviewPayload(Locator locator, StoredRunContext context) {
        PendingHumanReview review = requireHumanReview(context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "human_review");
        payload.put("node_id", review.nodeId());
        payload.put("message", review.message());
        payload.put("visible_artifacts", visibleArtifacts(context, review.nodeId()));
        payload.put("instructions", review.instructions());
        payload.put("field_schema", review.fields());
        payload.put("resolve_examples", List.of(
                "forge run resolve-human --runs=" + locator.runsDir + " --slug="
                        + locator.slug.asString() + " --field=<key>=<value>",
                "forge run resolve-human --runs=" + locator.runsDir + " --slug="
                        + locator.slug.asString() + " --field=<key>=<value> --dry-run"
        ));
        payload.put("notification_pending", false);
        payload.put("pending_notification_id", null);
        payload.put("projection_backlog", store.projectionBacklog(context.runDir()));
        return payload;
    }

    private PendingHumanReview requireHumanReview(StoredRunContext context) {
        PendingOperation pending = context.state().pendingOperation();
        if (pending != null && "human_review".equals(pending.kind())) {
            return humanReviewFromNode(context.spec(), pending.nodeId());
        }
        RunAction action = WorkflowPlanner.project(context.spec(), context.state());
        if (action.humanReview() != null) {
            return action.humanReview();
        }
        throw new ForgeException("error: current node is not a human node");
    }

    private PendingHumanReview humanReviewFromNode(WorkflowSpec spec, @Nullable String nodeId) {
        if (nodeId == null) {
            throw new ForgeException("error: current node is not a human node");
        }
        JsonNode node = findNode(spec, nodeId);
        if (!"human".equals(text(node, "kind"))) {
            throw new ForgeException("error: current node is not a human node");
        }
        List<JsonNode> fields = new ArrayList<>();
        JsonNode rawFields = node.get("fields");
        if (rawFields != null && rawFields.isArray()) {
            rawFields.forEach(field -> fields.add(field.deepCopy()));
        }
        return new PendingHumanReview(
                nodeId,
                textOr(node, "message", "Human review required"),
                fields,
                textOrNull(node, "instructions"));
    }

    private List<Map<String, Object>> visibleArtifacts(StoredRunContext context, String nodeId) {
        JsonNode node = findNode(context.spec(), nodeId);
        JsonNode inputs = node.get("inputs");
        List<Map<String, Object>> artifacts = new ArrayList<>();
        if (inputs != null && inputs.isArray()) {
            for (JsonNode input : inputs) {
                String name = input.asText();
                ArtifactRecord record = context.state().artifactIndex().get(name);
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("name", name);
                artifact.put("path", record == null ? null : record.path());
                artifact.put("available", record != null);
                artifact.put("media_type", record == null ? null : record.mediaType());
                artifacts.add(artifact);
            }
        }
        return List.copyOf(artifacts);
    }

    private void validateHumanFields(PendingHumanReview review, Map<String, String> values) {
        Map<String, JsonNode> schemaByName = new TreeMap<>();
        for (JsonNode field : review.fields()) {
            schemaByName.put(text(field, "name"), field);
        }
        for (String valueName : values.keySet()) {
            if (!schemaByName.containsKey(valueName)) {
                throw new ForgeException("error: unknown human field '" + valueName + "'");
            }
        }
        for (Map.Entry<String, JsonNode> entry : schemaByName.entrySet()) {
            JsonNode field = entry.getValue();
            if (field.has("required") && field.get("required").asBoolean() && !values.containsKey(entry.getKey())) {
                throw new ForgeException("error: missing required human field '" + entry.getKey() + "'");
            }
            JsonNode allowed = field.get("allowed_values");
            if (values.containsKey(entry.getKey()) && allowed != null && allowed.isArray() && !allowed.isEmpty()) {
                boolean matches = false;
                for (JsonNode allowedValue : allowed) {
                    if (allowedValue.asText().equals(values.get(entry.getKey()))) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    throw new ForgeException("error: invalid value for human field '" + entry.getKey() + "'");
                }
            }
        }
    }

    private RunAction projectedAfterHumanResolution(StoredRunContext context, Map<String, String> fields) {
        JsonNode route = currentNode(context.spec(), context.state()).get("route");
        if (route == null || route.isNull()) {
            return RunAction.complete("workflow completed");
        }
        return WorkflowPlanner.route(route, routeDecisionValue(route, fields));
    }

    private RunAction actionForObserver(StoredRunContext context) {
        PendingOperation pending = context.state().pendingOperation();
        if (pending == null) {
            return WorkflowPlanner.project(context.spec(), context.state());
        }
        if ("node_dispatch".equals(pending.kind())) {
            return new RunAction("dispatch", null, null, null, null, null, null);
        }
        if ("notification".equals(pending.kind())) {
            return new RunAction(
                    "dispatch",
                    null,
                    null,
                    null,
                    textOr(pending.payload(), "message", "notification dispatch prepared"),
                    null,
                    null);
        }
        if ("human_review".equals(pending.kind())) {
            return RunAction.humanReview(humanReviewFromNode(context.spec(), pending.nodeId()));
        }
        if ("subrun".equals(pending.kind())) {
            return new RunAction("subrun", null, null, null, null, null, null);
        }
        throw new ForgeException("unsupported pending operation kind: " + pending.kind());
    }

    private Map<String, Object> actionPayload(RunAction action, @Nullable PendingOperation pending) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.type());
        payload.put("dispatch_id", pending != null && DispatchLifecycle.isDispatchOperation(pending)
                ? pending.operationId()
                : dispatchId(action));
        payload.put("node_id", nodeId(action, pending));
        payload.put("reason", action.reason());
        payload.put("message", action.message());
        return payload;
    }

    private static Map<String, Object> rustActionPayload(RunAction action, @Nullable DerivedRunState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.type());
        switch (action.type()) {
            case "dispatch" -> {
                PendingDispatch dispatch = action.dispatch();
                if (dispatch != null) {
                    payload.put("dispatch_id", dispatch.dispatchId());
                    payload.put("dispatch_kind", "notification_hook".equals(dispatch.runner()) ? "notification" : "node");
                    payload.put("runner", dispatch.runner());
                    payload.put("command", dispatch.command());
                    payload.put("cwd", dispatch.cwd());
                    payload.put("env", dispatch.env());
                    payload.put("input_paths", dispatch.inputPaths());
                    payload.put("output_paths", dispatch.outputPaths());
                    payload.put("stdin_path", dispatch.stdinPath());
                    payload.put("message", dispatch.message());
                    payload.put("timeout_ms", dispatch.timeoutMs());
                }
            }
            case "human_review" -> {
                PendingHumanReview review = action.humanReview();
                if (review != null) {
                    payload.put("node_id", review.nodeId());
                    payload.put("message", review.message());
                    payload.put("fields", review.fields());
                    payload.put("visible_artifacts", List.of());
                    payload.put("instructions", review.instructions());
                }
            }
            case "subrun" -> {
                PendingSubrun subrun = action.subrun();
                if (subrun != null) {
                    payload.put("node_id", subrun.nodeId());
                    payload.put("child_slug", subrun.childSlug());
                    payload.put("frozen_child_spec_path", subrun.frozenChildSpecPath());
                    payload.put("frozen_child_ir_path", subrun.frozenChildIrPath());
                    payload.put("frozen_child_interface_path", subrun.frozenChildInterfacePath());
                    payload.put("request_artifact_path", subrun.requestArtifactPath());
                    payload.put("child_run_dir", subrun.childRunDir());
                    payload.put("summary_artifact", subrun.summaryArtifact());
                    payload.put("import_artifacts", subrun.importArtifacts());
                    payload.put("message", subrun.message());
                }
            }
            case "complete" -> {
                payload.put("message", action.message());
                payload.put("final_artifacts", state == null ? List.of() : List.copyOf(state.artifactIndex().keySet()));
            }
            case "escalate" -> {
                payload.put("node_id", state == null ? null : state.currentNode());
                payload.put("reason", action.reason());
                payload.put("recommended_next_steps", List.of(
                        state != null && state.runStatus() == RunStatus.FAILED
                                ? "Inspect the failing node output"
                                : "Inspect the latest node output and artifacts",
                        state != null && state.runStatus() == RunStatus.FAILED
                                ? "Decide whether to retry, replan, or abort"
                                : "Record an explicit human decision before resuming"));
            }
            case "noop" -> payload.put("message", action.message());
            default -> {
            }
        }
        return payload;
    }

    private static @Nullable String projectedCurrentNode(StoredRunContext context, RunAction action) {
        return switch (action.type()) {
            case "transition", "continue_loop" -> action.target();
            default -> context.state().currentNode();
        };
    }

    private static String projectedRunStatus(RunAction action) {
        return switch (action.type()) {
            case "complete" -> "completed";
            case "escalate" -> "escalated";
            default -> "running";
        };
    }

    private static @Nullable String dispatchId(RunAction action) {
        return action.dispatch() == null ? null : action.dispatch().dispatchId();
    }

    private static @Nullable String nodeId(RunAction action, @Nullable PendingOperation pending) {
        if (pending != null) {
            return pending.nodeId();
        }
        if (action.dispatch() != null) {
            return action.dispatch().nodeId();
        }
        if (action.humanReview() != null) {
            return action.humanReview().nodeId();
        }
        if (action.subrun() != null) {
            return action.subrun().nodeId();
        }
        return action.target();
    }

    private static @Nullable String terminalState(RunStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case ESCALATED -> "escalated";
            default -> null;
        };
    }

    private static String terminalFailureFallback(RunStatus status) {
        return status == RunStatus.FAILED ? "run failed" : "workflow escalated";
    }

    private static String terminalFailureReason(DerivedRunState state) {
        return state.escalationReason() == null
                ? terminalFailureFallback(state.runStatus())
                : state.escalationReason();
    }

    private static String dispatchFailureReason(Map<String, Object> result) {
        Object reason = result.get("failure_reason");
        if (reason instanceof String text && !text.isBlank()) {
            return text;
        }
        return Boolean.TRUE.equals(result.get("timed_out"))
                ? "dispatch timed out"
                : "dispatch execution failed";
    }

    private static @Nullable String operatorHint(DerivedRunState state, RunAction action) {
        if (state.runStatus() == RunStatus.WAITING_FOR_HUMAN || action.humanReview() != null) {
            return "inspect with forge run show-human";
        }
        if (state.runStatus() == RunStatus.WAITING_FOR_AGENT || action.dispatch() != null) {
            return "advance with forge run exec-dispatch or forge run auto";
        }
        return null;
    }

    private static @Nullable String pendingDispatchKind(@Nullable PendingOperation pending, RunAction action) {
        if (pending != null) {
            if ("node_dispatch".equals(pending.kind())) {
                return "node";
            }
            if ("notification".equals(pending.kind())) {
                return "notification";
            }
        }
        return action.dispatch() != null ? "node" : null;
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.ESCALATED;
    }

    private static void sleep(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ForgeException("interrupted while watching run", error);
        }
    }

    private void emitProgressFrame(Locator locator, StoredRunContext context, WatchRenderMode mode) {
        Map<String, Object> payload = mode.summary
                ? progressSummaryPayload(locator, context)
                : progressPayload(locator, context);
        if (mode.jsonl) {
            System.out.println(Json.toJson(payload));
        } else {
            System.out.print(Json.toPrettyJson(payload));
        }
    }

    private void emitAutoHumanFrame(Locator locator, StoredRunContext context, @Nullable String watchMode) {
        emitAutoFrame(humanReviewPayload(locator, context), watchMode);
    }

    private void emitAutoTerminalFrame(StoredRunContext context, @Nullable String watchMode) {
        RunAction action = WorkflowPlanner.project(context.spec(), context.state());
        emitAutoFrame(terminalObserverPayload(action, context.state()), watchMode);
    }

    private static void emitAutoFrame(Map<String, Object> payload, @Nullable String watchMode) {
        WatchRenderMode mode = watchMode == null ? null : renderMode(watchMode);
        if (mode != null && mode.jsonl) {
            System.out.println(Json.toJson(payload));
        } else {
            System.out.print(Json.toPrettyJson(payload));
        }
    }

    private static Map<String, Object> terminalObserverPayload(RunAction action, DerivedRunState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (state.runStatus()) {
            case COMPLETED -> {
                payload.put("status", "completed");
                payload.put("message", action.message() == null ? completedMessage(state) : action.message());
                payload.put("final_artifacts", List.copyOf(state.artifactIndex().keySet()));
            }
            case FAILED -> {
                payload.put("status", "failed");
                payload.put("node_id", state.currentNode());
                payload.put("reason", action.reason() == null ? terminalFailureReason(state) : action.reason());
                payload.put("recommended_next_steps", terminalRecommendedNextSteps(state));
            }
            case ESCALATED -> {
                payload.put("status", "escalated");
                payload.put("node_id", state.currentNode());
                payload.put("reason", action.reason() == null ? terminalFailureReason(state) : action.reason());
                payload.put("recommended_next_steps", terminalRecommendedNextSteps(state));
            }
            default -> throw new ForgeException("error: run is not terminal");
        }
        return payload;
    }

    private static String completedMessage(DerivedRunState state) {
        if (state.completedMessage() != null && !state.completedMessage().isBlank()) {
            return state.completedMessage();
        }
        String slug = state.slug() == null || state.slug().isBlank() ? "unknown" : state.slug();
        return "run '" + slug + "' completed";
    }

    private static List<String> terminalRecommendedNextSteps(DerivedRunState state) {
        return List.of(
                state.runStatus() == RunStatus.FAILED
                        ? "Inspect the failing node output"
                        : "Inspect the latest node output and artifacts",
                state.runStatus() == RunStatus.FAILED
                        ? "Decide whether to retry, replan, or abort"
                        : "Record an explicit human decision before resuming");
    }

    private static WatchRenderMode renderMode(WatchOptions options) {
        return new WatchRenderMode(options.jsonl, options.summary);
    }

    private static WatchRenderMode renderMode(String watchMode) {
        return new WatchRenderMode("jsonl".equals(watchMode), "summary".equals(watchMode));
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private StoredRunContext load(Locator locator) {
        return store.loadRunContext(runDir(locator));
    }

    private static Path runDir(Locator locator) {
        return RunPaths.runDir(locator.runsDir, locator.slug);
    }

    private RunAction projectOrNoop(StoredRunContext context) {
        if (context.state().pendingOperation() != null) {
            return RunAction.noop("operation is already pending");
        }
        return WorkflowPlanner.project(context.spec(), context.state());
    }

    private static long retryPolicyMaxAttempts(JsonNode node) {
        JsonNode retry = node.get("retry_policy");
        if (retry == null || retry.isNull() || !retry.hasNonNull("max_attempts")) {
            return 1L;
        }
        return retry.get("max_attempts").asLong(1L);
    }

    private static @Nullable ObjectNode notificationOperationFor(StoredRunContext context, RunAction action) {
        NotificationRequest request = notificationRequest(context, action);
        if (request == null
                || context.state().deliveredNotifications().containsKey(request.notificationId())
                || context.state().failedNotifications().containsKey(request.notificationId())) {
            return null;
        }
        JsonNode hook = notificationHook(context.spec(), request.level());
        if (hook == null || hook.isNull()) {
            return null;
        }
        Path runDir = absolutePath(context.runDir());
        String hookPath = resolveRunPath(runDir, requiredText(hook, "path")).toString();
        Map<String, String> env = new TreeMap<>(stringMap(hook.get("env")));
        env.put("FORGE_NOTIFICATION_ID", request.notificationId());
        env.put("FORGE_LEVEL", request.level().toUpperCase(Locale.ROOT));
        env.put("FORGE_MESSAGE", request.message());
        env.put("FORGE_RUN_DIR", runDir.toString());
        env.put("FORGE_SPEC_FILE", RunPaths.specPath(runDir).toString());
        env.put("FORGE_EVENTS_FILE", RunPaths.eventsPath(runDir).toString());
        env.put("FORGE_PROGRESS_FILE", RunPaths.dispatchOutputDir(runDir)
                .resolve(request.notificationId() + ".progress.ndjson")
                .toString());
        env.put("FORGE_CURRENT_NODE", context.state().currentNode() == null ? "" : context.state().currentNode());
        env.put("FORGE_STAGE", context.state().currentNode() == null ? "" : context.state().currentNode());
        if (context.state().runId() != null) {
            env.put("FORGE_RUN_ID", context.state().runId());
        }
        if (context.state().slug() != null) {
            env.put("FORGE_SLUG", context.state().slug());
            env.put("FORGE_RUN_SLUG", context.state().slug());
        }
        if (context.state().workflowId() != null) {
            env.put("FORGE_WORKFLOW_ID", context.state().workflowId());
        }

        ObjectNode notification = Json.mapper().createObjectNode();
        notification.put("notification_id", request.notificationId());
        notification.put("level", request.level());
        notification.set("command", Json.mapper().valueToTree(List.of(hookPath, request.message())));
        notification.set("env", Json.mapper().valueToTree(env));
        notification.put("message", request.message());
        JsonNode cwd = hook.get("cwd");
        if (cwd != null && cwd.isTextual() && !cwd.asText().isBlank()) {
            notification.put("cwd", resolveRunPath(runDir, cwd.asText()).toString());
        }
        return rustOperation(
                request.notificationId(),
                "notification",
                context.state().currentNode(),
                request.message(),
                "notification",
                notification);
    }

    private static @Nullable NotificationRequest notificationRequest(StoredRunContext context, RunAction action) {
        String slug = context.state().slug() == null ? "unknown" : context.state().slug();
        String currentNode = context.state().currentNode() == null ? "unknown" : context.state().currentNode();
        return switch (action.type()) {
            case "complete" -> new NotificationRequest(
                    "complete",
                    "notify-complete",
                    action.message() == null ? "run complete" : action.message());
            case "escalate" -> new NotificationRequest(
                    "escalate",
                    "notify-escalate",
                    "run '" + slug + "' escalated at node '" + currentNode + "': "
                            + (action.reason() == null ? "workflow escalated" : action.reason()));
            case "human_review" -> {
                PendingHumanReview review = action.humanReview();
                if (review == null) {
                    yield null;
                }
                long visit = context.state().nodeVisitCounts().getOrDefault(review.nodeId(), 1L);
                String message = review.message() == null || review.message().isBlank()
                        ? "run '" + slug + "' is waiting for human review at node '" + review.nodeId() + "'"
                        : "run '" + slug + "' is waiting for human review at node '"
                                + review.nodeId()
                                + "': "
                                + review.message();
                yield new NotificationRequest(
                        "human_review",
                        "notify-human-review-" + review.nodeId() + "-" + visit,
                        message);
            }
            default -> null;
        };
    }

    private static @Nullable JsonNode notificationHook(WorkflowSpec spec, String level) {
        JsonNode notifications = spec.notifications();
        if (notifications == null || notifications.isNull()) {
            return null;
        }
        return switch (level) {
            case "complete" -> firstNonNull(notifications.get("complete_hook"), notifications.get("default_hook"));
            case "escalate" -> firstNonNull(notifications.get("escalate_hook"), notifications.get("default_hook"));
            case "human_review" -> notifications.get("human_review_hook");
            default -> null;
        };
    }

    private static @Nullable JsonNode firstNonNull(@Nullable JsonNode first, @Nullable JsonNode fallback) {
        return first == null || first.isNull() ? fallback : first;
    }

    private static Path resolveRunPath(Path runDir, String path) {
        Path value = Path.of(path);
        return value.isAbsolute() ? value.normalize() : absolutePath(runDir).resolve(value).normalize();
    }

    private static Path absolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String absolutePathString(Path path) {
        return absolutePath(path).toString();
    }

    private static ObjectNode operationFor(StoredRunContext context, PendingDispatch dispatch) {
        JsonNode node = findNode(context.spec(), dispatch.nodeId());
        JsonNode agent = dispatchAgent(context.spec(), node);
        List<String> outputNames = dispatch.outputPaths();
        List<String> outputPaths = outputArtifactPaths(context, outputNames);
        List<String> inputNames = dispatch.inputPaths();
        List<String> envInputPaths = resolveInputPaths(context, inputNames);
        List<String> operationInputPaths = agent == null
                ? envInputPaths
                : List.of(absolutePathString(promptPath(context.runDir(), dispatch.dispatchId())));
        Map<String, String> env = runtimeDispatchEnv(context, dispatch, inputNames, envInputPaths, outputNames, outputPaths);
        List<String> command = dispatch.command();
        @Nullable String stdinPath = dispatch.stdinPath();
        @Nullable String cwd = resolveDispatchCwd(context, dispatch.cwd());

        if (agent != null) {
            Path promptPath = promptPath(context.runDir(), dispatch.dispatchId());
            writeText(promptPath, assemblePrompt(context, node));
            env = new TreeMap<>(env);
            env.put("FORGE_PROMPT_FILE", absolutePathString(promptPath));
            command = applyCodexRequestConfig(context, dispatch.runner(), command);
            String promptPathText = absolutePathString(promptPath);
            String promptDelivery = textOr(agent, "prompt_delivery", "env_path");
            if ("stdin_file".equals(promptDelivery)) {
                stdinPath = promptPathText;
            } else if ("argv_path".equals(promptDelivery)) {
                List<String> withPromptArg = new ArrayList<>(command);
                withPromptArg.add(promptPathText);
                command = List.copyOf(withPromptArg);
                stdinPath = null;
            } else {
                stdinPath = null;
            }
        }

        ObjectNode dispatchPayload = Json.mapper().createObjectNode();
        dispatchPayload.put("dispatch_id", dispatch.dispatchId());
        dispatchPayload.put("node_id", dispatch.nodeId());
        dispatchPayload.put("runner", dispatch.runner());
        dispatchPayload.set("command", Json.mapper().valueToTree(command));
        dispatchPayload.set("env", Json.mapper().valueToTree(env));
        dispatchPayload.set("input_paths", Json.mapper().valueToTree(operationInputPaths));
        dispatchPayload.set("output_paths", Json.mapper().valueToTree(outputPaths));
        if (stdinPath != null) {
            dispatchPayload.put("stdin_path", stdinPath);
        }
        if (cwd != null) {
            dispatchPayload.put("cwd", cwd);
        }
        if (dispatch.timeoutMs() != null) {
            dispatchPayload.put("timeout_ms", dispatch.timeoutMs());
        }
        if (dispatch.message() != null) {
            dispatchPayload.put("message", dispatch.message());
        }
        return rustOperation(
                dispatch.dispatchId(),
                "node_dispatch",
                dispatch.nodeId(),
                dispatch.message(),
                "dispatch",
                dispatchPayload);
    }

    private static ObjectNode operationFor(StoredRunContext context, PendingSubrun subrun) {
        String childSlug = deterministicChildSlug(context.state(), subrun.nodeId());
        Path childRunDir = RunPaths.childRunDir(context.runDir(), childSlug);
        Path frozenChildSpecPath = resolveFrozenChildSpecPath(context.runDir(), subrun.workflowRef());
        Path frozenChildIrPath = siblingWorkflowFile(frozenChildSpecPath, "workflow-ir.json");
        Path frozenChildInterfacePath = siblingWorkflowFile(frozenChildSpecPath, "workflow-interface.json");
        validateFrozenChildSnapshot(frozenChildSpecPath, frozenChildIrPath, frozenChildInterfacePath);
        JsonNode childInterface = Json.read(frozenChildInterfacePath, JsonNode.class);
        WorkflowSpecCompiler.requiredSubrunImports(
                context.spec(),
                subrun.nodeId(),
                childInterface,
                subrun.requestArtifact(),
                subrun.importArtifacts());
        Path requestArtifactPath = preparedSubrunRequestPath(
                childRunDir,
                subrun.requestArtifact(),
                context.state().artifactIndex().get(subrun.requestArtifact()));
        materializePendingSubrunRequest(context, subrun.requestArtifact(), requestArtifactPath);

        ObjectNode subrunPayload = Json.mapper().createObjectNode();
        subrunPayload.put("node_id", subrun.nodeId());
        subrunPayload.put("child_slug", childSlug);
        subrunPayload.put("frozen_child_spec_path", runRelativePath(context.runDir(), frozenChildSpecPath).toString());
        subrunPayload.put("frozen_child_ir_path", runRelativePath(context.runDir(), frozenChildIrPath).toString());
        subrunPayload.put("frozen_child_interface_path", runRelativePath(context.runDir(), frozenChildInterfacePath).toString());
        subrunPayload.put("child_run_dir", runRelativePath(context.runDir(), childRunDir).toString());
        subrunPayload.put("request_artifact", subrun.requestArtifact());
        subrunPayload.put("request_artifact_path", runRelativePath(context.runDir(), requestArtifactPath).toString());
        subrunPayload.put("summary_artifact", subrun.summaryArtifact());
        subrunPayload.set("import_artifacts", Json.mapper().valueToTree(subrun.importArtifacts()));
        return rustOperation(
                childSlug,
                "subrun",
                subrun.nodeId(),
                subrun.message(),
                "subrun",
                subrunPayload);
    }

    private static String deterministicChildSlug(DerivedRunState state, String nodeId) {
        String parentSlug = state.slug() == null || state.slug().isBlank() ? "run" : state.slug();
        long visit = state.nodeVisitCounts().getOrDefault(nodeId, 1L);
        return RunSlug.parse(parentSlug + "-" + sanitizeSlugFragment(nodeId) + "-" + visit).asString();
    }

    private static Path runRelativePath(Path runDir, Path path) {
        Path normalizedRunDir = runDir.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRunDir)) {
            throw new ForgeException("subrun path " + path + " is outside run directory " + runDir);
        }
        return normalizedRunDir.relativize(normalizedPath);
    }

    private static String sanitizeSlugFragment(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch <= 0x7f && (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) {
                out.append(ch);
            } else {
                out.append('-');
            }
        }
        String trimmed = out.toString().replaceAll("^-+|-+$", "");
        return trimmed.isBlank() ? "node" : trimmed;
    }

    private static Path resolveFrozenChildSpecPath(Path runDir, String workflowRef) {
        if (workflowRef.startsWith("template:")) {
            throw new ForgeException("subrun workflow_ref " + workflowRef + " was not frozen to a run-local path");
        }
        Path path = Path.of(workflowRef);
        return path.isAbsolute() ? path : runDir.resolve(path);
    }

    private static Path siblingWorkflowFile(Path specPath, String fileName) {
        Path parent = specPath.getParent();
        return parent == null ? Path.of(fileName) : parent.resolve(fileName);
    }

    private static void validateFrozenChildSnapshot(Path specPath, Path irPath, Path interfacePath) {
        if (!Files.isRegularFile(specPath)) {
            throw new ForgeException("prepared subrun references missing frozen child spec at " + specPath);
        }
        if (!Files.isRegularFile(irPath)) {
            throw new ForgeException("prepared subrun references missing frozen child IR at " + irPath);
        }
        if (!Files.isRegularFile(interfacePath)) {
            throw new ForgeException("prepared subrun references missing frozen child interface at " + interfacePath);
        }
    }

    private static Path preparedSubrunRequestPath(
            Path childRunDir,
            String requestArtifact,
            @Nullable ArtifactRecord source) {
        String fileName = requestArtifact;
        if (source != null) {
            Path sourcePath = Path.of(source.path());
            Path sourceName = sourcePath.getFileName();
            if (sourceName != null && !sourceName.toString().isBlank()) {
                fileName = sourceName.toString();
            }
        }
        return childRunDir.resolve("prepared").resolve(source == null ? sanitizeSlugFragment(fileName) : fileName);
    }

    private static void materializePendingSubrunRequest(
            StoredRunContext context,
            String requestArtifact,
            Path requestArtifactPath) {
        ArtifactRecord source = context.state().artifactIndex().get(requestArtifact);
        if (source == null) {
            throw new ForgeException("missing required request artifact '" + requestArtifact + "'");
        }
        copy(ArtifactStore.resolveArtifactPath(context.runDir(), context.state(), source), requestArtifactPath);
    }

    private static Map<String, String> runtimeDispatchEnv(
            StoredRunContext context,
            PendingDispatch dispatch,
            List<String> inputNames,
            List<String> inputPaths,
            List<String> outputNames,
            List<String> outputPaths) {
        Map<String, String> env = new TreeMap<>(dispatch.env());
        Path runDir = absolutePath(context.runDir());
        env.put("FORGE_RUN_DIR", runDir.toString());
        env.put("FORGE_SPEC_FILE", RunPaths.specPath(runDir).toString());
        env.put("FORGE_EVENTS_FILE", RunPaths.eventsPath(runDir).toString());
        env.put("FORGE_PROGRESS_FILE", RunPaths.dispatchOutputDir(runDir)
                .resolve(dispatch.dispatchId() + ".progress.ndjson")
                .toString());
        env.put("FORGE_CURRENT_NODE", dispatch.nodeId());
        if (context.state().workflowId() != null) {
            env.put("FORGE_WORKFLOW_ID", context.state().workflowId());
        }
        if (context.state().runId() != null) {
            env.put("FORGE_RUN_ID", context.state().runId());
        }
        if (context.state().slug() != null) {
            env.put("FORGE_SLUG", context.state().slug());
            env.put("FORGE_RUN_SLUG", context.state().slug());
        }
        for (int index = 0; index < inputNames.size() && index < inputPaths.size(); index++) {
            env.put("FORGE_INPUT_" + TextSupport.envSuffix(inputNames.get(index)), inputPaths.get(index));
        }
        for (int index = 0; index < outputNames.size() && index < outputPaths.size(); index++) {
            env.put("FORGE_OUTPUT_" + TextSupport.envSuffix(outputNames.get(index)), outputPaths.get(index));
        }
        for (Map.Entry<String, String> decision : context.state().decisions().entrySet()) {
            env.put("FORGE_DECISION_" + TextSupport.envSuffix(decision.getKey()), decision.getValue());
        }
        return Map.copyOf(env);
    }

    private static List<String> outputArtifactPaths(StoredRunContext context, List<String> outputNames) {
        List<String> paths = new ArrayList<>();
        for (String outputName : outputNames) {
            paths.add(absolutePathString(canonicalArtifactPath(context.runDir(), context.spec(), outputName)));
        }
        return List.copyOf(paths);
    }

    private static List<String> resolveInputPaths(StoredRunContext context, List<String> inputNames) {
        List<String> paths = new ArrayList<>();
        for (String inputName : inputNames) {
            ArtifactRecord artifact = context.state().artifactIndex().get(inputName);
            if (artifact == null) {
                throw new ForgeException("missing required input artifact '" + inputName + "'");
            }
            paths.add(absolutePathString(ArtifactStore.resolveArtifactPath(context.runDir(), context.state(), artifact)));
        }
        return List.copyOf(paths);
    }

    private static @Nullable JsonNode dispatchAgent(WorkflowSpec spec, JsonNode node) {
        String kind = text(node, "kind");
        if (!"agent".equals(kind) && !"judge".equals(kind)) {
            return null;
        }
        String agentId = text(node, "agent_id");
        JsonNode agent = spec.agents() == null ? null : spec.agents().get(agentId);
        if (agent == null || agent.isNull()) {
            throw new ForgeException("unknown agent '" + agentId + "'");
        }
        return agent;
    }

    private static Path promptPath(Path runDir, String dispatchId) {
        return RunPaths.dispatchInputDir(runDir).resolve(dispatchId + "-prompt.md");
    }

    private static String assemblePrompt(StoredRunContext context, JsonNode node) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(text(node, "prompt_template")).append("\n\n");
        prompt.append("# Node\n").append(text(node, "id")).append('\n');
        prompt.append("# Workflow\n").append(context.spec().workflowId()).append('\n');
        prompt.append("# Inputs\n");

        List<String> inputNames = stringArray(node.get("inputs"));
        for (String inputName : inputNames) {
            ArtifactRecord artifact = context.state().artifactIndex().get(inputName);
            if (artifact == null) {
                throw new ForgeException("missing required input artifact '" + inputName + "'");
            }
            appendPromptArtifact(context, prompt, inputName, artifact);
        }

        long attempt = context.state().nodeVisitCounts().getOrDefault(text(node, "id"), 1L);
        if (attempt > 1) {
            List<Map.Entry<String, ArtifactRecord>> additionalArtifacts = context.state().artifactIndex().entrySet().stream()
                    .filter(entry -> !inputNames.contains(entry.getKey()))
                    .toList();
            if (!additionalArtifacts.isEmpty()) {
                prompt.append("# Re-entry Context\n");
                prompt.append("Attempt ")
                        .append(attempt)
                        .append(" for node '")
                        .append(text(node, "id"))
                        .append("'. Reuse the prior artifacts below to revise the work.\n\n");
                for (Map.Entry<String, ArtifactRecord> entry : additionalArtifacts) {
                    appendPromptArtifact(context, prompt, entry.getKey(), entry.getValue());
                }
            }
        }

        if (!context.state().decisions().isEmpty()) {
            prompt.append("# Decision Context\n");
            for (Map.Entry<String, String> decision : context.state().decisions().entrySet()) {
                prompt.append(decision.getKey()).append(": ").append(decision.getValue()).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("# Output Paths\n");
        for (String outputName : stringArray(node.get("outputs"))) {
            prompt.append(outputName)
                    .append(": ")
                    .append(canonicalArtifactPath(context.runDir(), context.spec(), outputName))
                    .append('\n');
        }
        return prompt.toString();
    }

    private static void appendPromptArtifact(
            StoredRunContext context,
            StringBuilder prompt,
            String artifactName,
            ArtifactRecord artifact) {
        String content = ArtifactStore.readArtifactText(context.runDir(), context.state(), artifact);
        String label = artifactName.toUpperCase(Locale.ROOT);
        prompt.append("--- ").append(label).append(" ---\n");
        prompt.append(content.trim()).append('\n');
        prompt.append("--- END ").append(label).append(" ---\n\n");
    }

    private static List<String> applyCodexRequestConfig(
            StoredRunContext context,
            String runner,
            List<String> command) {
        if (!"codex".equals(runner) || codexExecSegment(command).isEmpty()) {
            return command;
        }
        Optional<CodexRequestConfig> config = codexRequestConfig(context);
        if (config.isEmpty()) {
            return command;
        }
        List<String> configured = new ArrayList<>(command);
        if (config.get().model() != null) {
            configured.add("--model");
            configured.add(config.get().model());
        }
        if (config.get().reasoningEffort() != null) {
            configured.add("-c");
            configured.add("model_reasoning_effort=\"" + config.get().reasoningEffort() + "\"");
        }
        return List.copyOf(configured);
    }

    private static Optional<Integer> codexExecSegment(List<String> command) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if ("codex".equals(codexBinaryName(command.get(index))) && "exec".equals(command.get(index + 1))) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    private static String codexBinaryName(String argument) {
        Path fileNamePath = Path.of(argument).getFileName();
        String binary = fileNamePath == null ? argument : fileNamePath.toString();
        return binary.endsWith(".exe") ? binary.substring(0, binary.length() - 4) : binary;
    }

    private static Optional<CodexRequestConfig> codexRequestConfig(StoredRunContext context) {
        Optional<ArtifactRecord> artifact = ArtifactStore.optionalRequestArtifact(context.state());
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        Path requestPath = ArtifactStore.resolveArtifactPath(context.runDir(), context.state(), artifact.get());
        if (!Files.isRegularFile(requestPath)) {
            return Optional.empty();
        }
        JsonNode request;
        try {
            request = Json.mapper().readTree(requestPath.toFile());
        } catch (IOException error) {
            return Optional.empty();
        }
        return parseCodexRequestConfig(request);
    }

    private static Optional<CodexRequestConfig> parseCodexRequestConfig(JsonNode request) {
        JsonNode workflowConfig = request.get("workflow_config");
        if (workflowConfig == null || workflowConfig.isNull()) {
            return Optional.empty();
        }
        if (!workflowConfig.isObject()) {
            throw new ForgeException("request.workflow_config must be a JSON object");
        }
        JsonNode codex = workflowConfig.get("codex");
        if (codex == null || codex.isNull()) {
            return Optional.empty();
        }
        if (!codex.isObject()) {
            throw new ForgeException("request.workflow_config.codex must be a JSON object");
        }
        @Nullable String model = optionalNonEmptyString(codex.get("model"), "workflow_config.codex.model");
        @Nullable String effort = optionalNonEmptyString(
                codex.has("reasoning_effort") ? codex.get("reasoning_effort") : codex.get("effort"),
                "workflow_config.codex.reasoning_effort");
        if (effort != null
                && !("low".equals(effort) || "medium".equals(effort)
                || "high".equals(effort) || "xhigh".equals(effort))) {
            throw new ForgeException("workflow_config.codex.reasoning_effort must be one of low, medium, high, xhigh");
        }
        if (model == null && effort == null) {
            return Optional.empty();
        }
        return Optional.of(new CodexRequestConfig(model, effort));
    }

    private static @Nullable String optionalNonEmptyString(@Nullable JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ForgeException(field + " must be a string");
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            throw new ForgeException(field + " must not be empty");
        }
        return text;
    }

    private static @Nullable String resolveDispatchCwd(StoredRunContext context, @Nullable String raw) {
        if (raw == null) {
            return null;
        }
        if (!"$request.repo_root".equals(raw)) {
            return raw;
        }
        ArtifactRecord requestArtifact = ArtifactStore.requestArtifact(context.state());
        String requestText = ArtifactStore.readArtifactText(context.runDir(), context.state(), requestArtifact);
        JsonNode request;
        try {
            request = Json.mapper().readTree(requestText);
        } catch (IOException error) {
            throw new ForgeException("request artifact for cwd token '$request.repo_root' is not valid json", error);
        }
        JsonNode repoRootNode = request.get("repo_root");
        String repoRoot = repoRootNode == null || repoRootNode.isNull() ? "" : repoRootNode.asText().trim();
        if (repoRoot.isBlank()) {
            throw new ForgeException("cwd token '$request.repo_root' requires request.repo_root to be a non-empty string");
        }
        if (!Path.of(repoRoot).isAbsolute()) {
            throw new ForgeException("cwd token '$request.repo_root' requires request.repo_root to be an absolute path, got '"
                    + repoRoot
                    + "'");
        }
        return repoRoot;
    }

    private static Map<String, String> resolveDispatchDecisions(
            StoredRunContext context,
            JsonNode node,
            Map<String, String> explicitDecisions) {
        Map<String, String> resolved = new TreeMap<>();
        if ("judge".equals(text(node, "kind"))) {
            resolved.putAll(extractJudgeDecisions(context.runDir(), context.spec(), node));
        }
        resolved.putAll(explicitDecisions);
        validateJudgeDecisions(node, resolved);
        return Map.copyOf(resolved);
    }

    private static Map<String, String> extractJudgeDecisions(Path runDir, WorkflowSpec spec, JsonNode node) {
        Map<String, String> extracted = new TreeMap<>();
        for (String outputName : stringArray(node.get("outputs"))) {
            Path path = canonicalArtifactPath(runDir, spec, outputName);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            JsonNode value;
            try {
                value = Json.mapper().readTree(path.toFile());
            } catch (IOException error) {
                throw new ForgeException("failed to parse judge artifact '"
                        + outputName
                        + "' as JSON from "
                        + path
                        + ": "
                        + error.getMessage(), error);
            }
            if (!value.isObject()) {
                throw new ForgeException("judge artifact '" + outputName + "' at " + path + " must be a JSON object");
            }
            for (JsonNode field : decisionFields(node)) {
                String name = text(field, "name");
                JsonNode raw = value.get(name);
                if (raw == null || raw.isNull()) {
                    continue;
                }
                if (!raw.isTextual()) {
                    throw new ForgeException("judge artifact '" + outputName + "' field '" + name + "' must be a string");
                }
                extracted.put(name, raw.asText());
            }
        }
        return Map.copyOf(extracted);
    }

    private static void validateJudgeDecisions(JsonNode node, Map<String, String> decisions) {
        if (!"judge".equals(text(node, "kind"))) {
            return;
        }
        for (JsonNode field : decisionFields(node)) {
            String name = text(field, "name");
            String value = decisions.get(name);
            List<String> allowed = stringArray(field.get("allowed_values"));
            if (value == null) {
                throw new ForgeException("judge node '"
                        + text(node, "id")
                        + "' did not produce required decision field '"
                        + name
                        + "'; expected one of: "
                        + String.join(", ", allowed));
            }
            if (!allowed.contains(value)) {
                throw new ForgeException("judge node '"
                        + text(node, "id")
                        + "' produced invalid value '"
                        + value
                        + "' for decision field '"
                        + name
                        + "'; expected one of: "
                        + String.join(", ", allowed));
            }
        }
    }

    private static List<JsonNode> decisionFields(JsonNode node) {
        JsonNode raw = node.get("decision_fields");
        if (raw == null || !raw.isArray()) {
            return List.of();
        }
        List<JsonNode> fields = new ArrayList<>();
        raw.forEach(field -> fields.add(field.deepCopy()));
        return List.copyOf(fields);
    }

    private static List<ArtifactRecord> completedOutputArtifacts(StoredRunContext context, JsonNode node) {
        List<ArtifactRecord> artifacts = new ArrayList<>();
        for (String outputName : stringArray(node.get("outputs"))) {
            Path path = canonicalArtifactPath(context.runDir(), context.spec(), outputName);
            if (!Files.isRegularFile(path)) {
                throw new ForgeException("output artifact '"
                        + outputName
                        + "' declared by node '"
                        + text(node, "id")
                        + "' was not produced at "
                        + path);
            }
            artifacts.add(new ArtifactRecord(
                    outputName,
                    path.toString(),
                    text(node, "id"),
                    artifactMediaType(context.spec(), outputName, path)));
        }
        return List.copyOf(artifacts);
    }

    private static Path canonicalArtifactPath(Path runDir, WorkflowSpec spec, String artifactName) {
        JsonNode artifact = spec.artifacts() == null ? null : spec.artifacts().get(artifactName);
        if (artifact == null || artifact.isNull()) {
            throw new ForgeException("unknown output artifact '" + artifactName + "'");
        }
        String declaredPath = text(artifact, "path");
        if (declaredPath.isBlank()) {
            throw new ForgeException("artifact '" + artifactName + "' is missing path");
        }
        return PathSupport.canonicalArtifactPath(runDir, artifactName, declaredPath);
    }

    private static String artifactMediaType(WorkflowSpec spec, String artifactName, Path path) {
        JsonNode artifact = spec.artifacts() == null ? null : spec.artifacts().get(artifactName);
        String mediaType = artifact == null ? "" : text(artifact, "media_type");
        return mediaType.isBlank() ? mediaTypeFor(path) : mediaType;
    }

    private static ObjectNode operationFor(PendingHumanReview review) {
        ObjectNode reviewPayload = Json.mapper().createObjectNode();
        reviewPayload.put("node_id", review.nodeId());
        reviewPayload.put("message", review.message());
        reviewPayload.set("fields", Json.mapper().valueToTree(review.fields()));
        reviewPayload.set("visible_artifacts", Json.mapper().createArrayNode());
        if (review.instructions() != null) {
            reviewPayload.put("instructions", review.instructions());
        }
        return rustOperation(
                review.nodeId(),
                "human_review",
                review.nodeId(),
                review.message(),
                "review",
                reviewPayload);
    }

    private static ObjectNode rustOperation(
            String operationId,
            String operationKind,
            @Nullable String nodeId,
            @Nullable String message,
            String payloadField,
            ObjectNode payload) {
        ObjectNode operation = Json.mapper().createObjectNode();
        operation.put("operation_id", operationId);
        operation.put("operation_kind", operationKind);
        if (nodeId != null && !nodeId.isBlank()) {
            operation.put("node_id", nodeId);
        }
        if (message != null && !message.isBlank()) {
            operation.put("message", message);
        }
        operation.set(payloadField, payload);
        return operation;
    }

    private static JsonNode currentNode(WorkflowSpec spec, DerivedRunState state) {
        return findNode(spec, state.currentNode());
    }

    private static JsonNode findNode(WorkflowSpec spec, @Nullable String nodeId) {
        for (JsonNode node : spec.nodes()) {
            if (nodeId != null && nodeId.equals(text(node, "id"))) {
                return node;
            }
        }
        throw new ForgeException("unknown current node '" + nodeId + "'");
    }

    private static String routeDecisionValue(JsonNode route, Map<String, String> decisions) {
        if ("by_field".equals(text(route, "mode"))) {
            String field = text(route, "field");
            String value = decisions.get(field);
            return value == null ? "" : value;
        }
        return decisions.values().stream().findFirst().orElse("");
    }

    private static String statusText(DerivedRunState state) {
        return state.runStatus().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static long nextSeq(DerivedRunState state) {
        return state.lastEventSeq() == null ? 1 : state.lastEventSeq() + 1;
    }

    private static DerivedRunState loadDerivedState(Path eventsPath) {
        return WorkflowReducer.deriveState(EventLog.readEvents(eventsPath));
    }

    private static WorkflowSpecCompiler.ResolvedWorkflow resolveTemplateWorkflow(String templateId) {
        TemplateBundle bundle = Templates.loadBuiltInTemplate(templateId);
        return new WorkflowSpecCompiler.ResolvedWorkflow(
                bundle.workflow(),
                bundle.rootDir(),
                bundle.workflowPath(),
                "template:" + templateId);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new ForgeException("subrun operation is missing " + field);
        }
        return value;
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new TreeMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String mediaTypeFor(Path path) {
        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath == null ? "" : fileNamePath.toString();
        if (fileName.endsWith(".json")) {
            return "application/json";
        }
        if (fileName.endsWith(".md")) {
            return "text/markdown";
        }
        return "application/octet-stream";
    }

    private static void copy(Path source, Path target) {
        Path parent = target.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        try {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new ForgeException("failed to copy artifact " + source + " to " + target, error);
        }
    }

    private static void promoteStagedRun(Path stagingDir, Path runDir, boolean force) {
        if (force && Files.exists(runDir)) {
            deleteRecursively(runDir);
        }
        Path parent = runDir.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        try {
            Files.move(stagingDir, runDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new ForgeException("failed to publish initialized run from " + stagingDir + " to " + runDir, error);
        }
    }

    private static String requestArtifactName(JsonNode workflowInterface) {
        JsonNode inputs = workflowInterface.get("inputs");
        if (inputs == null || !inputs.isObject()) {
            return "request";
        }
        List<String> names = new ArrayList<>();
        inputs.fieldNames().forEachRemaining(names::add);
        return names.size() == 1 ? names.getFirst() : "request";
    }

    private static void writeText(Path path, String text) {
        Path parent = path.getParent();
        if (parent != null) {
            Filesystem.ensureDirectory(parent);
        }
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ForgeException("failed to write " + path, error);
        }
    }

    private static void rejectLegacyAutoLock(Path runDir, Path lockPath) {
        if (!Files.isRegularFile(lockPath)) {
            return;
        }
        try {
            JsonNode metadata = Json.mapper().readTree(lockPath.toFile());
            if (!metadata.isObject()
                    || !metadata.has("pid")
                    || !metadata.has("acquired_at")
                    || !metadata.has("purpose")
                    || !metadata.has("command")) {
                throw corruptPrereleaseAutoLock(runDir, lockPath);
            }
        } catch (IOException error) {
            throw corruptPrereleaseAutoLock(runDir, lockPath);
        }
    }

    private static ForgeException corruptPrereleaseAutoLock(Path runDir, Path lockPath) {
        return new ForgeException("error: run at "
                + runDir
                + " contains an unsupported or corrupt prerelease auto.lock at "
                + lockPath
                + "; remove the lock file or recreate the run before rerunning `forge run auto`");
    }

    private static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (IOException error) {
            throw new ForgeException("failed to remove existing run directory " + path, error);
        }
    }

    private record AutoResult(String status, DerivedRunState finalState) {
    }

    private record DispatchFailureResult(
            String status,
            @Nullable String notificationLevel,
            @Nullable String nodeId,
            @Nullable Long attempt,
            @Nullable Long maxAttempts
    ) {
        private static DispatchFailureResult notification(String level) {
            return new DispatchFailureResult("notification_failed", level, null, null, null);
        }

        private static DispatchFailureResult node(
                String nodeId,
                long attempt,
                long maxAttempts,
                boolean shouldRetry) {
            return new DispatchFailureResult(
                    shouldRetry ? "dispatch_failed_retrying" : "dispatch_failed_escalated",
                    null,
                    nodeId,
                    attempt,
                    maxAttempts);
        }
    }

    private record CodexRequestConfig(@Nullable String model, @Nullable String reasoningEffort) {
    }

    private record WatchRenderMode(boolean jsonl, boolean summary) {
    }

    private record NotificationRequest(String level, String notificationId, String message) {
    }

    public record Locator(Path runsDir, RunSlug slug) {
    }

    public record InitOptions(
            Path spec,
            Path runsDir,
            RunSlug slug,
            Map<String, Path> artifacts,
            boolean force
    ) {
        public InitOptions {
            artifacts = Map.copyOf(new TreeMap<>(artifacts));
        }
    }

    public record ExecOptions(Locator locator, boolean tee) {
    }

    public record AutoOptions(Locator locator, @Nullable String watchMode, boolean tee) {
    }

    public record WatchOptions(
            Locator locator,
            long intervalMs,
            boolean jsonl,
            boolean summary,
            boolean untilTerminal
    ) {
    }

    public record DispatchCompletionOptions(Locator locator, String dispatchId, Map<String, String> decisions) {
        public DispatchCompletionOptions {
            decisions = Map.copyOf(new TreeMap<>(decisions));
        }
    }

    public record DispatchFailureOptions(Locator locator, String dispatchId, String reason, boolean retryable) {
    }

    public record ResolveHumanOptions(Locator locator, Map<String, String> fields, boolean dryRun) {
        public ResolveHumanOptions {
            fields = Map.copyOf(new TreeMap<>(fields));
        }
    }
}
