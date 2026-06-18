package dev.llaith.forge.runtime.subrun;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.spec.WorkflowSpecCompiler;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.storage.ArtifactStore;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.storage.FilesystemRunStore;
import dev.llaith.forge.storage.RunPaths;
import dev.llaith.forge.storage.StoredRunContext;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Hashing;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.util.PathSupport;
import dev.llaith.forge.util.TimeSupport;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import dev.llaith.forge.workflow.runner.WorkflowRunner;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.ArtifactRole;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.PendingOperation;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class SubrunLifecycle {
    private final FilesystemRunStore store;
    private final Clock clock;

    public SubrunLifecycle(Clock clock) {
        this.store = new FilesystemRunStore();
        this.clock = clock;
    }

    public boolean advancePendingSubrun(
            Path parentRunDir,
            ChildRunAdvancer childRunAdvancer,
            ParentRouteEvents parentRouteEvents) {
        SubrunOperation subrun = store.mutateRun(parentRunDir, context -> {
            PendingOperation pending = requirePendingSubrun(context.state());
            SubrunOperation current = subrunOperation(parentRunDir, pending);
            if (!hasOperationExecution(context.eventsPath(), "operation_started", current.operationId())) {
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        nextSeq(context.state()),
                        TimeSupport.now(clock),
                        "operation_started",
                        Map.of(
                                "operation_id", current.operationId(),
                                "execution", subrunExecution(current, null, null))));
            }
            return current;
        });

        try {
            ensureChildRunInitialized(subrun);
            childRunAdvancer.advance(subrun.childRunDir(), subrun.childSlug());
        } catch (ForgeException error) {
            reconcileFailedSubrun(
                    parentRunDir,
                    subrun,
                    "subrun execution failed: " + error.getMessage(),
                    parentRouteEvents);
            return true;
        }

        DerivedRunState childState = loadDerivedState(RunPaths.eventsPath(subrun.childRunDir()));
        return switch (childState.runStatus()) {
            case COMPLETED -> {
                reconcileTerminalSubrun(parentRunDir, subrun, childState, "complete", null, parentRouteEvents);
                yield true;
            }
            case ESCALATED -> {
                reconcileTerminalSubrun(parentRunDir, subrun, childState, "escalate", null, parentRouteEvents);
                yield true;
            }
            case FAILED -> {
                String reason = childState.escalationReason() == null
                        ? "child run '" + subrun.childSlug() + "' failed"
                        : childState.escalationReason();
                reconcileFailedSubrun(parentRunDir, subrun, reason, parentRouteEvents);
                yield true;
            }
            default -> false;
        };
    }

    private void ensureChildRunInitialized(SubrunOperation subrun) {
        Path childRunDir = subrun.childRunDir();
        boolean hasSpec = Files.isRegularFile(RunPaths.specPath(childRunDir));
        boolean hasIr = Files.isRegularFile(RunPaths.workflowIrPath(childRunDir));
        boolean hasInterface = Files.isRegularFile(RunPaths.workflowInterfacePath(childRunDir));
        boolean hasEvents = Files.isRegularFile(RunPaths.eventsPath(childRunDir));
        if (hasSpec && hasIr && hasInterface && hasEvents) {
            store.loadRunContext(childRunDir);
            return;
        }
        if (hasSpec || hasIr || hasInterface || hasEvents) {
            throw new ForgeException("child run '" + subrun.childSlug() + "' has partial initialized state in "
                    + childRunDir
                    + " (spec.json="
                    + hasSpec
                    + ", workflow-ir.json="
                    + hasIr
                    + ", workflow-interface.json="
                    + hasInterface
                    + ", events.ndjson="
                    + hasEvents
                    + ")");
        }
        ensureOnlyPreparedFilesExist(childRunDir);
        WorkflowSpec childSpec = WorkflowSpecs.load(subrun.frozenChildSpecPath());
        JsonNode childIr = Json.read(subrun.frozenChildIrPath(), JsonNode.class);
        JsonNode childInterface = Json.read(subrun.frozenChildInterfacePath(), JsonNode.class);
        copyFrozenNestedSubruns(subrun.frozenChildSpecPath(), childRunDir);

        Path childCanonicalRequest = canonicalArtifactPath(
                childRunDir,
                childSpec,
                subrun.requestArtifact());
        copy(subrun.requestArtifactPath(), childCanonicalRequest);
        ArtifactRecord request = new ArtifactRecord(
                subrun.requestArtifact(),
                childCanonicalRequest.toString(),
                null,
                artifactMediaType(childSpec, subrun.requestArtifact(), childCanonicalRequest));
        store.writeFrozenRunFiles(childRunDir, childSpec, childIr, childInterface);
        store.appendEvents(childRunDir, WorkflowRunner.planInitEvents(
                childSpec,
                RunSlug.parse(subrun.childSlug()),
                List.of(request),
                Map.of(subrun.requestArtifact(), new ArtifactMetadata(
                        Hashing.sha256Hex(childCanonicalRequest),
                        ArtifactBinding.local(),
                        ArtifactRole.REQUEST)),
                TimeSupport.now(clock)));
    }

    private void reconcileTerminalSubrun(
            Path parentRunDir,
            SubrunOperation subrun,
            DerivedRunState childState,
            String status,
            @Nullable String failureReason,
            ParentRouteEvents parentRouteEvents) {
        store.mutateRun(parentRunDir, context -> {
            PendingOperation pending = requirePendingSubrun(context.state());
            if (!pending.operationId().equals(subrun.operationId())) {
                throw new ForgeException("error: pending subrun is '"
                        + pending.operationId()
                        + "' not '"
                        + subrun.operationId()
                        + "'");
            }
            long seq = nextSeq(context.state());
            String timestamp = TimeSupport.now(clock);
            String effectiveFailureReason = failureReason;
            if (effectiveFailureReason == null && "complete".equals(status)) {
                effectiveFailureReason = missingRequiredImportReason(context.spec(), subrun, childState);
            }
            if (effectiveFailureReason == null) {
                SubrunArtifacts artifacts = writeSubrunArtifacts(context, subrun, childState, status);
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "artifact_written",
                        Map.of("artifact", artifacts.summary())));
                for (ArtifactRecord imported : artifacts.imported()) {
                    EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                            seq++,
                            timestamp,
                            "artifact_written",
                            Map.of("artifact", imported)));
                }
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_succeeded",
                        Map.of(
                                "operation_id", subrun.operationId(),
                                "execution", subrunExecution(subrun, TimeSupport.now(clock), status))));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_completed",
                        Map.of("operation_id", subrun.operationId())));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "decision_recorded",
                        Map.of(
                                "key", "subrun_status",
                                "value", status,
                                "source_node", subrun.nodeId())));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "artifact_metadata_recorded",
                        Map.of(
                                "artifact_name", artifacts.summary().name(),
                                "blob_id", Hashing.sha256Hex(Path.of(artifacts.summary().path())),
                                "binding", ArtifactBinding.local(),
                                "role", ArtifactRole.STANDARD)));
                for (Map.Entry<String, ArtifactMetadata> entry : artifacts.importedMetadata().entrySet()) {
                    EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                            seq++,
                            timestamp,
                            "artifact_metadata_recorded",
                            Map.of(
                                    "artifact_name", entry.getKey(),
                                    "blob_id", entry.getValue().blobId(),
                                    "binding", entry.getValue().binding(),
                                    "role", entry.getValue().role())));
                }
            } else {
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_failed",
                        Map.of(
                                "operation_id", subrun.operationId(),
                                "execution", subrunExecution(
                                        subrun,
                                        TimeSupport.now(clock),
                                        null,
                                        effectiveFailureReason))));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "operation_completed",
                        Map.of("operation_id", subrun.operationId())));
                EventLog.appendEvent(context.eventsPath(), EventEnvelope.of(
                        seq++,
                        timestamp,
                        "decision_recorded",
                        Map.of(
                                "key", "subrun_status",
                                "value", "failed",
                                "source_node", subrun.nodeId())));
            }
            parentRouteEvents.append(context, seq, timestamp, Map.of(
                    "subrun_status", effectiveFailureReason == null ? status : "failed"));
            return null;
        });
    }

    private void reconcileFailedSubrun(
            Path parentRunDir,
            SubrunOperation subrun,
            String reason,
            ParentRouteEvents parentRouteEvents) {
        reconcileTerminalSubrun(parentRunDir, subrun, DerivedRunState.idle(), "failed", reason, parentRouteEvents);
    }

    private static SubrunArtifacts writeSubrunArtifacts(
            StoredRunContext context,
            SubrunOperation subrun,
            DerivedRunState childState,
            String status) {
        Path summaryPath = canonicalArtifactPath(context.runDir(), context.spec(), subrun.summaryArtifact());
        Map<String, String> importedPaths = new TreeMap<>();
        List<ArtifactRecord> imported = new ArrayList<>();
        Map<String, ArtifactMetadata> importedMetadata = new TreeMap<>();
        for (Map.Entry<String, String> entry : subrun.importArtifacts().entrySet()) {
            String childArtifact = entry.getKey();
            String parentArtifact = entry.getValue();
            ArtifactRecord childRecord = childState.artifactIndex().get(childArtifact);
            if (childRecord == null) {
                continue;
            }
            Path childPath = ArtifactStore.resolveArtifactPath(subrun.childRunDir(), childState, childRecord);
            ArtifactRecord parentRecord = new ArtifactRecord(
                    parentArtifact,
                    childPath.toString(),
                    subrun.nodeId(),
                    artifactMediaType(context.spec(), parentArtifact, childPath));
            imported.add(parentRecord);
            importedPaths.put(parentArtifact, childPath.toString());
            ArtifactMetadata childMetadata = childState.artifactMetadata().get(childArtifact);
            String blobId = childMetadata == null ? Hashing.sha256Hex(childPath) : childMetadata.blobId();
            importedMetadata.put(parentArtifact, new ArtifactMetadata(
                    blobId,
                    ArtifactBinding.importedChild(subrun.childRunDir().toString(), childArtifact),
                    ArtifactRole.STANDARD));
        }
        String summary = renderSubrunSummary(subrun, childState, status, importedPaths);
        writeText(summaryPath, summary);
        ArtifactRecord summaryRecord = new ArtifactRecord(
                subrun.summaryArtifact(),
                summaryPath.toString(),
                subrun.nodeId(),
                artifactMediaType(context.spec(), subrun.summaryArtifact(), summaryPath));
        return new SubrunArtifacts(summaryRecord, List.copyOf(imported), Map.copyOf(importedMetadata));
    }

    private static String renderSubrunSummary(
            SubrunOperation subrun,
            DerivedRunState childState,
            String status,
            Map<String, String> importedPaths) {
        String childSlug = childState.slug() == null ? subrun.childSlug() : childState.slug();
        String workflowId = childState.workflowId() == null ? "unknown" : childState.workflowId();
        return "# Child Run Summary\n\n"
                + "- Child slug: " + childSlug + "\n"
                + "- Child workflow id: " + workflowId + "\n"
                + "- Child terminal status: " + status + "\n"
                + "- Imported artifacts: " + (importedPaths.isEmpty() ? "none" : importedPaths) + "\n";
    }

    private static @Nullable String missingRequiredImportReason(
            WorkflowSpec parentSpec,
            SubrunOperation subrun,
            DerivedRunState childState) {
        Set<String> requiredImportArtifacts = requiredImportArtifacts(parentSpec, subrun);
        for (String childArtifact : requiredImportArtifacts) {
            if (!subrun.importArtifacts().containsKey(childArtifact)) {
                continue;
            }
            if (!childState.artifactIndex().containsKey(childArtifact)) {
                return "required import artifact '"
                        + childArtifact
                        + "' was not produced by completed child run '"
                        + subrun.childSlug()
                        + "'";
            }
        }
        return null;
    }

    private static Set<String> requiredImportArtifacts(WorkflowSpec parentSpec, SubrunOperation subrun) {
        JsonNode childInterface = Json.read(subrun.frozenChildInterfacePath(), JsonNode.class);
        return WorkflowSpecCompiler.requiredSubrunImports(
                parentSpec,
                subrun.nodeId(),
                childInterface,
                subrun.requestArtifact(),
                subrun.importArtifacts());
    }

    private static Map<String, Object> subrunExecution(
            SubrunOperation subrun,
            @Nullable String completedAt,
            @Nullable String outcome) {
        return subrunExecution(subrun, completedAt, outcome, null);
    }

    private static Map<String, Object> subrunExecution(
            SubrunOperation subrun,
            @Nullable String completedAt,
            @Nullable String outcome,
            @Nullable String failureReason) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("kind", "subrun");
        execution.put("operation_id", subrun.operationId());
        execution.put("operation_kind", "subrun");
        execution.put("node_id", subrun.nodeId());
        execution.put("child_slug", subrun.childSlug());
        execution.put("child_run_dir", subrun.childRunDir().toString());
        execution.put("started_at", null);
        execution.put("completed_at", completedAt);
        execution.put("outcome", outcome);
        execution.put("failure_reason", failureReason);
        return execution;
    }

    private static boolean hasOperationExecution(Path eventsPath, String eventType, String operationId) {
        for (EventEnvelope event : EventLog.readEvents(eventsPath)) {
            if (eventType.equals(event.type()) && operationId.equals(event.textField("operation_id"))) {
                return true;
            }
        }
        return false;
    }

    private static PendingOperation requirePendingSubrun(DerivedRunState state) {
        PendingOperation operation = state.pendingOperation();
        if (operation == null || !"subrun".equals(operation.kind())) {
            throw new ForgeException("error: run does not have a prepared subrun");
        }
        return operation;
    }

    private static SubrunOperation subrunOperation(Path parentRunDir, PendingOperation operation) {
        JsonNode payload = operation.payload();
        String operationId = operation.operationId();
        String childSlug = requiredText(payload, "child_slug");
        if (!operationId.equals(childSlug)) {
            throw new ForgeException("error: prepared subrun operation_id '"
                    + operationId
                    + "' does not match payload child_slug '"
                    + childSlug
                    + "'");
        }
        Path frozenWorkflowDir = RunPaths.subrunFrozenWorkflowsDir(parentRunDir);
        Path childrenDir = RunPaths.subrunChildrenDir(parentRunDir);
        Path frozenChildSpecPath = preparedSubrunPath(
                parentRunDir,
                operationId,
                payload,
                "frozen_child_spec_path",
                frozenWorkflowDir,
                "frozen workflow");
        Path frozenChildIrPath = preparedSubrunPath(
                parentRunDir,
                operationId,
                payload,
                "frozen_child_ir_path",
                frozenWorkflowDir,
                "frozen workflow");
        Path frozenChildInterfacePath = preparedSubrunPath(
                parentRunDir,
                operationId,
                payload,
                "frozen_child_interface_path",
                frozenWorkflowDir,
                "frozen workflow");
        Path childRunDir = preparedSubrunPath(
                parentRunDir,
                operationId,
                payload,
                "child_run_dir",
                childrenDir,
                "child run");
        Path requestArtifactPath = preparedSubrunPath(
                parentRunDir,
                operationId,
                payload,
                "request_artifact_path",
                childRunDir,
                "request artifact");
        requireSameParentDirectory(operationId, "frozen_child_ir_path", frozenChildSpecPath, frozenChildIrPath);
        requireSameParentDirectory(operationId, "frozen_child_interface_path", frozenChildSpecPath, frozenChildInterfacePath);
        return new SubrunOperation(
                operationId,
                requiredText(payload, "node_id"),
                childSlug,
                frozenChildSpecPath,
                frozenChildIrPath,
                frozenChildInterfacePath,
                childRunDir,
                requiredText(payload, "request_artifact"),
                requestArtifactPath,
                requiredText(payload, "summary_artifact"),
                stringMap(payload.get("import_artifacts")));
    }

    private static void ensureOnlyPreparedFilesExist(Path childRunDir) {
        if (!Files.isDirectory(childRunDir)) {
            Filesystem.ensureDirectory(childRunDir);
            return;
        }
        try (var stream = Files.list(childRunDir)) {
            for (Path entry : stream.toList()) {
                Path fileName = entry.getFileName();
                String entryName = fileName == null ? entry.toString() : fileName.toString();
                if (!"prepared".equals(entryName)) {
                    throw new ForgeException("child run dir '"
                            + childRunDir
                            + "' contains unexpected pre-init entry '"
                            + entryName
                            + "'; refusing to reconstruct partial state");
                }
            }
        } catch (IOException error) {
            throw new ForgeException("failed to inspect child run directory " + childRunDir, error);
        }
    }

    private static void copyFrozenNestedSubruns(Path frozenChildSpecPath, Path childRunDir) {
        Path parent = frozenChildSpecPath.getParent();
        if (parent == null) {
            return;
        }
        Path frozenNested = parent.resolve("subruns");
        if (Files.isDirectory(frozenNested)) {
            copyDirectory(frozenNested, RunPaths.subrunsDir(childRunDir));
        }
    }

    private static Path preparedSubrunPath(
            Path parentRunDir,
            String operationId,
            JsonNode payload,
            String field,
            Path expectedRoot,
            String rootName) {
        String raw = requiredText(payload, field);
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException error) {
            throw invalidPreparedSubrunPath(operationId, field, "must be a valid path: " + raw);
        }
        if (path.isAbsolute()) {
            throw invalidPreparedSubrunPath(operationId, field, "must be run-relative: " + raw);
        }
        Path rawNormalized = path.normalize();
        if (!path.equals(rawNormalized)) {
            throw invalidPreparedSubrunPath(operationId, field, "must be normalized without . or .. components: " + raw);
        }
        Path absoluteRoot = absolutePath(parentRunDir);
        Path resolved = absolutePath(parentRunDir.resolve(path));
        if (resolved.equals(absoluteRoot)) {
            throw invalidPreparedSubrunPath(operationId, field, "must name a file below the run directory");
        }
        if (!resolved.startsWith(absoluteRoot)) {
            throw invalidPreparedSubrunPath(operationId, field, "must stay inside the run directory");
        }
        Optional<Path> symlink = firstSymlinkComponent(absoluteRoot, resolved);
        if (symlink.isPresent()) {
            throw invalidPreparedSubrunPath(operationId, field, "must not include symlink components: "
                    + rootName + " path '" + absoluteRoot.relativize(symlink.get()) + "'");
        }
        if (!resolved.startsWith(absolutePath(expectedRoot))) {
            throw invalidPreparedSubrunPath(operationId, field, "must stay inside " + rootName + " tree");
        }
        return resolved;
    }

    private static void requireSameParentDirectory(
            String operationId,
            String field,
            Path frozenChildSpecPath,
            Path siblingPath) {
        Path specParent = frozenChildSpecPath.getParent();
        Path siblingParent = siblingPath.getParent();
        if (specParent == null || siblingParent == null || !specParent.equals(siblingParent)) {
            throw invalidPreparedSubrunPath(operationId, field, "must share frozen workflow parent with frozen_child_spec_path");
        }
    }

    private static ForgeException invalidPreparedSubrunPath(String operationId, String field, String reason) {
        String id = operationId == null || operationId.isBlank() ? "unknown" : operationId;
        return new ForgeException("error: prepared subrun field '" + field + "' for '"
                + id + "' " + reason);
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

    private static Path absolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path canonicalArtifactPath(Path runDir, WorkflowSpec spec, String artifactName) {
        JsonNode artifact = spec.artifacts() == null ? null : spec.artifacts().get(artifactName);
        if (artifact == null || artifact.isNull()) {
            throw new ForgeException("unknown artifact '" + artifactName + "'");
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
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new ForgeException("failed to copy artifact " + source + " to " + target, error);
        }
    }

    private static void copyDirectory(Path sourceRoot, Path targetRoot) {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path target = targetRoot.resolve(sourceRoot.relativize(source));
                if (Files.isDirectory(source)) {
                    Filesystem.ensureDirectory(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Filesystem.ensureDirectory(parent);
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException error) {
            throw new ForgeException("failed to copy directory " + sourceRoot + " to " + targetRoot, error);
        }
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

    private static DerivedRunState loadDerivedState(Path eventsPath) {
        return WorkflowReducer.deriveState(EventLog.readEvents(eventsPath));
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new TreeMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new ForgeException("field '" + field + "' is required");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static long nextSeq(DerivedRunState state) {
        return state.lastEventSeq() == null ? 1 : state.lastEventSeq() + 1;
    }

    public interface ChildRunAdvancer {
        void advance(Path childRunDir, String childSlug);
    }

    public interface ParentRouteEvents {
        long append(StoredRunContext context, long seq, String timestamp, Map<String, String> decisions);
    }

    private record SubrunOperation(
            String operationId,
            String nodeId,
            String childSlug,
            Path frozenChildSpecPath,
            Path frozenChildIrPath,
            Path frozenChildInterfacePath,
            Path childRunDir,
            String requestArtifact,
            Path requestArtifactPath,
            String summaryArtifact,
            Map<String, String> importArtifacts
    ) {
        private SubrunOperation {
            importArtifacts = Map.copyOf(new TreeMap<>(importArtifacts));
        }
    }

    private record SubrunArtifacts(
            ArtifactRecord summary,
            List<ArtifactRecord> imported,
            Map<String, ArtifactMetadata> importedMetadata
    ) {
        private SubrunArtifacts {
            imported = List.copyOf(imported);
            importedMetadata = Map.copyOf(new TreeMap<>(importedMetadata));
        }
    }
}
