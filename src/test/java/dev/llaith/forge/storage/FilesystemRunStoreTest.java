package dev.llaith.forge.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class FilesystemRunStoreTest {
    private static final ObjectMapper MAPPER = Json.mapper();

    @TempDir
    private Path tempDir;

    @Test
    void filesystemRunStoreWritesAndLoadsCompatibleRunContext() throws Exception {
        Path runDir = tempDir.resolve("runs/sample");
        WorkflowSpec spec = sampleSpec();
        var ir = MAPPER.createObjectNode()
                .put("workflow_id", "store_test")
                .put("source_spec_sha256", "not-yet-derived");
        var workflowInterface = MAPPER.createObjectNode()
                .set("inputs", MAPPER.createObjectNode());
        FilesystemRunStore store = new FilesystemRunStore();

        store.writeFrozenRunFiles(runDir, spec, ir, workflowInterface);
        store.appendEvent(runDir, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "store_test",
                "spec_version", 2,
                "entry_node", "plan"
        )));
        store.appendEvent(runDir, EventEnvelope.of(2, "2026-04-20T12:00:01Z", "run_completed", Map.of(
                "message", "done"
        )));

        StoredRunContext context = store.loadRunContext(runDir);

        assertThat(context.runDir()).isEqualTo(runDir);
        assertThat(context.spec().workflowId()).isEqualTo("store_test");
        assertThat(context.workflowIr().get("workflow_id").asText()).isEqualTo("store_test");
        assertThat(context.workflowInterface().has("inputs")).isTrue();
        assertThat(context.eventsPath()).isEqualTo(runDir.resolve("events.ndjson"));
        assertThat(context.state().runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(context.state().completedMessage()).isEqualTo("done");
        assertThat(Files.readString(runDir.resolve("run-state-version"))).isEqualTo("1\n");
    }

    @Test
    void runPathHelpersMatchRustLayout() {
        Path runsDir = Path.of("/tmp/runs");
        RunSlug slug = RunSlug.parse("sample");
        Path runDir = runsDir.resolve("sample");

        assertThat(RunPaths.runDir(runsDir, slug)).isEqualTo(runDir);
        assertThat(RunPaths.stagingDir(runsDir, slug)).isEqualTo(runsDir.resolve(".staging/sample"));
        assertThat(RunPaths.stagingLockPath(runsDir, slug)).isEqualTo(runsDir.resolve(".staging/sample.lock"));
        assertThat(RunPaths.specPath(runDir)).isEqualTo(runDir.resolve("spec.json"));
        assertThat(RunPaths.workflowIrPath(runDir)).isEqualTo(runDir.resolve("workflow-ir.json"));
        assertThat(RunPaths.workflowInterfacePath(runDir)).isEqualTo(runDir.resolve("workflow-interface.json"));
        assertThat(RunPaths.eventsPath(runDir)).isEqualTo(runDir.resolve("events.ndjson"));
        assertThat(RunPaths.artifactsDir(runDir)).isEqualTo(runDir.resolve("artifacts"));
        assertThat(RunPaths.dispatchInputDir(runDir)).isEqualTo(runDir.resolve("dispatch/input"));
        assertThat(RunPaths.dispatchOutputDir(runDir)).isEqualTo(runDir.resolve("dispatch/output"));
        assertThat(RunPaths.projectionBacklogDir(runDir)).isEqualTo(runDir.resolve(".projection-backlog"));
        assertThat(RunPaths.subrunFrozenWorkflowSourceDir(runDir, "child"))
                .isEqualTo(runDir.resolve("subruns/frozen-workflows/child/source"));
        assertThat(RunPaths.childRunDir(runDir, "child-1"))
                .isEqualTo(runDir.resolve("subruns/children/child-1"));
        assertThat(RunPaths.mutationLockPath(runDir)).isEqualTo(runDir.resolve(".locks/mutation.lock"));
        assertThat(RunPaths.dispatchExecutionLeasePath(runDir, "review_a-1"))
                .isEqualTo(runDir.resolve(".locks/dispatch-execution/review_a-1.lock"));
    }

    @Test
    void artifactStoreMatchesBlobIdsAndLocalArtifactResolution() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path artifactPath = runDir.resolve("artifacts/report.md");
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, "hello\n");
        ArtifactRecord artifact = new ArtifactRecord("report", "artifacts/report.md", "plan", "text/markdown");
        DerivedRunState state = new DerivedRunState(
                RunStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("report", artifact),
                Map.of());

        assertThat(ArtifactStore.blobIdForText("hello\n"))
                .isEqualTo("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03");
        assertThat(ArtifactStore.blobIdForBytes("hello\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ArtifactStore.blobIdForFile(artifactPath));
        assertThat(ArtifactStore.resolveArtifactPath(runDir, state, artifact)).isEqualTo(artifactPath);
        assertThat(ArtifactStore.readArtifactText(runDir, state, artifact)).isEqualTo("hello\n");
    }

    @Test
    void artifactStoreResolvesImportedChildBindings() throws Exception {
        Path parentRun = tempDir.resolve("runs/parent");
        Path childRun = parentRun.resolve("subruns/children/child");
        Path childArtifactPath = childRun.resolve("artifacts/child-report.md");
        Files.createDirectories(childArtifactPath.getParent());
        Files.writeString(childArtifactPath, "child report\n");
        ArtifactRecord childArtifact = new ArtifactRecord(
                "child_report",
                "artifacts/child-report.md",
                "child",
                "text/markdown");
        EventLog.appendEvent(RunPaths.eventsPath(childRun), EventEnvelope.of(
                1,
                "2026-04-20T12:00:00Z",
                "run_initialized",
                Map.of(
                        "run_id", "child-run",
                        "slug", "child",
                        "workflow_id", "child_workflow",
                        "spec_version", 2,
                        "entry_node", "child"
                )));
        EventLog.appendEvent(RunPaths.eventsPath(childRun), EventEnvelope.of(
                2,
                "2026-04-20T12:00:01Z",
                "artifact_written",
                Map.of("artifact", childArtifact)));

        ArtifactRecord imported = new ArtifactRecord(
                "imported_report",
                "artifacts/imported-report.md",
                "delegate",
                "text/markdown");
        DerivedRunState parentState = new DerivedRunState(
                RunStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("imported_report", imported),
                Map.of("imported_report", new ArtifactMetadata(
                        "blob-1",
                        ArtifactBinding.importedChild("runs/parent/subruns/children/child", "child_report"),
                        dev.llaith.forge.workflow.state.ArtifactRole.STANDARD)));

        assertThat(ArtifactStore.resolveArtifactPath(parentRun, parentState, imported)).isEqualTo(childArtifactPath);
        assertThat(ArtifactStore.readArtifactText(parentRun, parentState, imported)).isEqualTo("child report\n");
    }

    @Test
    void artifactStoreReportsMissingRequestAndBrokenImportedBindings() {
        DerivedRunState empty = DerivedRunState.idle();
        assertThatThrownBy(() -> ArtifactStore.requestArtifact(empty))
                .hasMessageContaining("run does not have a request input artifact");

        ArtifactRecord imported = new ArtifactRecord("imported", "artifacts/imported.md", null, null);
        DerivedRunState broken = new DerivedRunState(
                RunStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("imported", imported),
                Map.of("imported", new ArtifactMetadata(
                        "blob-1",
                        new ArtifactBinding("imported_child", null, "report"),
                        dev.llaith.forge.workflow.state.ArtifactRole.STANDARD)));
        assertThatThrownBy(() -> ArtifactStore.resolveArtifactPath(tempDir, broken, imported))
                .hasMessageContaining("imported child artifact binding is incomplete");
    }

    @Test
    void appendEventAfterUsesTailSequenceWithoutLoadingRunContext() throws Exception {
        Path runDir = tempDir.resolve("append-tail-only");
        Path events = RunPaths.eventsPath(runDir);
        EventLog.appendEvent(events, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "store_test",
                "spec_version", 2,
                "entry_node", "plan"
        )));
        Files.writeString(events, "\n", StandardOpenOption.APPEND);
        FilesystemRunStore store = new FilesystemRunStore();

        boolean appended = store.appendEventAfter(runDir, 1, EventEnvelope.of(
                2,
                "2026-04-20T12:00:01Z",
                "node_entered",
                Map.of("node_id", "plan")));
        boolean stale = store.appendEventAfter(runDir, 1, EventEnvelope.of(
                2,
                "2026-04-20T12:00:02Z",
                "node_entered",
                Map.of("node_id", "other")));

        assertThat(appended).isTrue();
        assertThat(stale).isFalse();
        assertThat(EventLog.lastEventSeq(events).orElseThrow()).isEqualTo(2L);
        assertThat(EventLog.readEvents(events).get(1).textField("node_id")).isEqualTo("plan");
    }

    @Test
    void appendEventAfterRejectsUnexpectedNextSequence() throws Exception {
        Path runDir = tempDir.resolve("append-seq-mismatch");
        EventLog.appendEvent(RunPaths.eventsPath(runDir), EventEnvelope.of(
                1,
                "2026-04-20T12:00:00Z",
                "run_initialized",
                Map.of(
                        "run_id", "run-1",
                        "slug", "sample",
                        "workflow_id", "store_test",
                        "spec_version", 2,
                        "entry_node", "plan"
                )));
        FilesystemRunStore store = new FilesystemRunStore();

        assertThatThrownBy(() -> store.appendEventAfter(runDir, 1, EventEnvelope.of(
                3,
                "2026-04-20T12:00:01Z",
                "node_entered",
                Map.of("node_id", "plan"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event seq must be 2, got 3");
    }

    @Test
    void runFilesRejectUnsupportedAndUnreadableStateVersions() {
        Path runDir = tempDir.resolve("versioned");
        RunFiles.writeRunStateVersion(runDir);
        RunFiles.ensureSupportedRunState(runDir);

        FilesystemRunStore store = new FilesystemRunStore();
        assertThatThrownBy(() -> store.loadRunContext(runDir))
                .hasMessageContaining("failed to read JSON from " + RunPaths.specPath(runDir));

        dev.llaith.forge.util.Filesystem.writeUtf8(RunPaths.runStateVersionPath(runDir), "abc\n");
        assertThatThrownBy(() -> RunFiles.ensureSupportedRunState(runDir))
                .hasMessageContaining("has an unreadable prerelease state marker")
                .hasMessageContaining("delete the run directory and reinitialize it");

        dev.llaith.forge.util.Filesystem.writeUtf8(RunPaths.runStateVersionPath(runDir), "2\n");
        assertThatThrownBy(() -> RunFiles.ensureSupportedRunState(runDir))
                .hasMessageContaining("uses unsupported prerelease state version 2")
                .hasMessageContaining("this build expects " + RunFiles.RUN_STATE_VERSION)
                .hasMessageContaining("delete the run directory and reinitialize it");
    }

    @Test
    void projectionBacklogRecoveryRepublishesFromCanonicalArtifacts() throws Exception {
        Path runDir = tempDir.resolve("runs/sample");
        FilesystemRunStore store = new FilesystemRunStore();
        store.writeFrozenRunFiles(
                runDir,
                sampleSpec(),
                MAPPER.createObjectNode().put("workflow_id", "store_test"),
                MAPPER.createObjectNode());
        store.appendEvent(runDir, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "sample",
                "workflow_id", "store_test",
                "spec_version", 2,
                "entry_node", "plan"
        )));
        Path canonical = runDir.resolve("artifacts/__canonical/report.md");
        Path projection = runDir.resolve("artifacts/report.md");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "# Report\n", StandardCharsets.UTF_8);
        Path record = RunPaths.projectionBacklogDir(runDir).resolve("1-report.json");
        Files.createDirectories(record.getParent());
        Json.writePretty(record, new ProjectionBacklogEntry(1, "report", canonical.toString(), projection.toString()));

        assertThat(store.projectionBacklog(runDir)).containsExactly(
                new ProjectionBacklogEntry(1, "report", canonical.toString(), projection.toString()));

        assertThat(store.recoverProjectionBacklog(runDir)).containsExactly(
                new ProjectionBacklogEntry(1, "report", canonical.toString(), projection.toString()));
        assertThat(Files.readString(projection, StandardCharsets.UTF_8)).isEqualTo("# Report\n");
        assertThat(record).doesNotExist();
        assertThat(store.recoverProjectionBacklog(runDir)).isEmpty();
    }

    @Test
    void projectionBacklogRejectsLegacyFutureAndMissingCanonicalRecords() throws Exception {
        Path runDir = tempDir.resolve("runs/bad");
        FilesystemRunStore store = new FilesystemRunStore();
        store.writeFrozenRunFiles(
                runDir,
                sampleSpec(),
                MAPPER.createObjectNode().put("workflow_id", "store_test"),
                MAPPER.createObjectNode());
        store.appendEvent(runDir, EventEnvelope.of(1, "2026-04-20T12:00:00Z", "run_initialized", Map.of(
                "run_id", "run-1",
                "slug", "bad",
                "workflow_id", "store_test",
                "spec_version", 2,
                "entry_node", "plan"
        )));
        Path canonical = runDir.resolve("artifacts/__canonical/report.md");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "# Report\n", StandardCharsets.UTF_8);
        Path record = RunPaths.projectionBacklogDir(runDir).resolve("2-report.json");
        Files.createDirectories(record.getParent());
        Json.writePretty(record, new ProjectionBacklogEntry(
                2,
                "report",
                canonical.toString(),
                runDir.resolve("artifacts/report.md").toString()));

        assertThatThrownBy(() -> store.projectionBacklog(runDir))
                .hasMessageContaining("references uncommitted event seq 2");
        Files.delete(record);
        Json.writePretty(record, new ProjectionBacklogEntry(
                1,
                "report",
                runDir.resolve("artifacts/__canonical/missing.md").toString(),
                runDir.resolve("artifacts/report.md").toString()));
        assertThatThrownBy(() -> store.projectionBacklog(runDir))
                .hasMessageContaining("points at missing canonical artifact");
        Files.delete(record);

        Files.createDirectories(runDir.resolve(".commit-staging"));
        assertThatThrownBy(() -> store.projectionBacklog(runDir))
                .hasMessageContaining("unsupported prerelease commit staging");
    }

    private static WorkflowSpec sampleSpec() {
        var node = MAPPER.createObjectNode()
                .put("id", "plan")
                .put("kind", "command");
        node.set("command", MAPPER.createArrayNode().add("sh").add("-c").add("true"));
        node.set("route", MAPPER.createObjectNode().put("mode", "always").put("to", "__complete__"));
        return new WorkflowSpec(
                2,
                "structured",
                "store_test",
                "plan",
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                List.of(node),
                MAPPER.createArrayNode(),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
    }
}
