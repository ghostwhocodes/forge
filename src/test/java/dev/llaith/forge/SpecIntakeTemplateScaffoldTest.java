package dev.llaith.forge;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.template.TemplateBundle;
import dev.llaith.forge.template.TemplateIntakeMode;
import dev.llaith.forge.template.Templates;
import dev.llaith.forge.template.analysis.TemplateAnalysis;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SpecIntakeTemplateScaffoldTest {
    @TempDir
    private Path tempDir;

    @Test
    void builtInTemplatesLoadAndValidateCheckedInWorkflowSpecs() {
        List<TemplateBundle> bundles = Templates.loadBuiltInTemplates();

        assertThat(bundles).hasSize(6);
        assertThat(bundles).extracting(bundle -> bundle.manifest().id())
                .containsExactly(
                        "implement-change",
                        "review-only",
                        "review-and-fix",
                        "auto-review-and-fix",
                        "architecture-guard",
                        "qa-gap-guard"
                );
        assertThat(bundles).allSatisfy(bundle -> {
            assertThat(bundle.workflow().workflowId()).isNotBlank();
            assertThat(bundle.referencedFiles()).isNotEmpty();
        });
    }

    @Test
    void builtInTemplateResolutionHonorsConfiguredSourceRoot() {
        String previous = System.getProperty("forge.sourceRoot");
        try {
            System.setProperty("forge.sourceRoot", Path.of("").toAbsolutePath().normalize().toString());

            Path templateDir = Templates.builtInTemplateDir("implement-change");

            assertThat(templateDir).isAbsolute();
            assertThat(templateDir).endsWith(Path.of("templates", "implement-change"));
            assertThat(templateDir.resolve("template.json")).isRegularFile();
            assertThat(Templates.defaultScaffoldTemplateIds())
                    .containsExactly("implement-change", "review-only", "review-and-fix");
        } finally {
            restoreProperty("forge.sourceRoot", previous);
        }
    }

    @Test
    void invalidConfiguredSourceRootReportsTemplateLocatorError() {
        String previous = System.getProperty("forge.sourceRoot");
        try {
            System.setProperty("forge.sourceRoot", tempDir.toString());

            assertThatThrownBy(() -> Templates.loadBuiltInTemplate("implement-change"))
                    .isInstanceOf(ForgeException.class)
                    .hasMessageContaining("configured source root does not contain built-in templates");
        } finally {
            restoreProperty("forge.sourceRoot", previous);
        }
    }

    @Test
    void templateIntakeModeRejectsUnknownJsonValue() {
        assertThatThrownBy(() -> TemplateIntakeMode.fromJson("unknown"))
                .isInstanceOf(ForgeException.class)
                .hasMessage("error: unknown template intake mode 'unknown'");
    }

    @Test
    void specFreezePathsWritesCompatibleStatusPayload() {
        Path out = tempDir.resolve("frozen.json");

        CapturedInvocation invocation = capture(() -> Main.exitCode(new String[]{
                "spec", "freeze-paths",
                "--spec=templates/review-only/workflow.json",
                "--write",
                "--out=" + out,
                "--freeze=hooks,cwd"
        }));

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        assertThat(invocation.stdout()).contains("\"status\" : \"written\"");
        assertThat(Files.exists(out)).isTrue();
        WorkflowSpec frozen = WorkflowSpecs.load(out);
        assertThat(frozen.workflowId()).isEqualTo("review_only");
    }

    @Test
    void specFreezePathsFreezesSelectedGroupsAndReportsChanges() throws Exception {
        Path specRoot = writeFreezePathFixture();
        Path spec = specRoot.resolve("spec.json");
        Path out = tempDir.resolve("selected-frozen.json");

        CapturedInvocation invocation = capture(() -> Main.exitCode(new String[]{
                "spec", "freeze-paths",
                "--spec=" + spec,
                "--write",
                "--out=" + out,
                "--freeze=hooks,cwd"
        }));

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        assertThat(invocation.stdout()).contains(
                "\"changed\" : true",
                "\"freeze\" : [ \"hooks\", \"cwd\" ]",
                "\"field\" : \"agents.planner.cwd\"",
                "\"field\" : \"nodes.build.cwd\"",
                "\"field\" : \"notifications.default_hook.path\""
        );
        JsonNode frozen = Json.mapper().readTree(out.toFile());
        assertThat(frozen.at("/agents/planner/cwd").asText()).isEqualTo(specRoot.resolve("workspace").toString());
        assertThat(frozen.at("/agents/planner/command/1").asText()).isEqualTo("scripts/agent.sh");
        assertThat(frozen.at("/agents/planner/env/CONFIG_FILE").asText()).isEqualTo("config/agent.json");
        assertThat(frozen.at("/nodes/0/cwd").asText()).isEqualTo(specRoot.resolve("workspace").toString());
        assertThat(frozen.at("/nodes/0/command/0").asText()).isEqualTo("./scripts/build.sh");
        assertThat(frozen.at("/notifications/default_hook/path").asText())
                .isEqualTo(specRoot.resolve("hooks/notify.sh").toString());
        assertThat(frozen.at("/notifications/default_hook/env/TOKEN_FILE").asText()).isEqualTo("config/token.txt");
    }

    @Test
    void specFreezePathsCheckAndStdoutApplyAllGroups() throws Exception {
        Path specRoot = writeFreezePathFixture();
        Path spec = specRoot.resolve("spec.json");

        CapturedInvocation checked = capture(() -> Main.exitCode(new String[]{
                "spec", "freeze-paths",
                "--spec=" + spec,
                "--check"
        }));

        assertThat(checked.exitCode()).isZero();
        assertThat(checked.stderr()).isEmpty();
        assertThat(checked.stdout()).contains(
                "\"changed\" : true",
                "\"field\" : \"agents.planner.command[1]\"",
                "\"field\" : \"agents.planner.env.CONFIG_FILE\"",
                "\"field\" : \"nodes.build.command[0]\"",
                "\"field\" : \"nodes.build.env.BUILD_CONFIG\"",
                "\"field\" : \"notifications.default_hook.env.TOKEN_FILE\""
        );

        CapturedInvocation stdout = capture(() -> Main.exitCode(new String[]{
                "spec", "freeze-paths",
                "--spec=" + spec,
                "--stdout"
        }));

        assertThat(stdout.exitCode()).isZero();
        assertThat(stdout.stderr()).isEmpty();
        JsonNode frozen = Json.mapper().readTree(stdout.stdout());
        assertThat(frozen.at("/agents/planner/command/0").asText()).isEqualTo("bash");
        assertThat(frozen.at("/agents/planner/command/1").asText())
                .isEqualTo(specRoot.resolve("scripts/agent.sh").toString());
        assertThat(frozen.at("/agents/planner/env/CONFIG_FILE").asText())
                .isEqualTo(specRoot.resolve("config/agent.json").toString());
        assertThat(frozen.at("/nodes/0/command/0").asText())
                .isEqualTo(specRoot.resolve("scripts/build.sh").toString());
        assertThat(frozen.at("/nodes/0/command/1").asText())
                .isEqualTo(specRoot.resolve("config/build.json").toString());
        assertThat(frozen.at("/notifications/default_hook/env/TOKEN_FILE").asText())
                .isEqualTo(specRoot.resolve("config/token.txt").toString());
    }

    @Test
    void intakeSimpleWritesRequestArtifactAndSelectsDefaultTemplate() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path out = tempDir.resolve("out");

        CapturedInvocation invocation = capture(() -> Main.exitCode(new String[]{
                "intake", "simple",
                "--repo=" + repo,
                "--goal=Implement this",
                "--out=" + out
        }));

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        assertThat(invocation.stdout()).contains("\"template_id\" : \"implement-change\"");
        String request = Files.readString(out.resolve("artifacts/request.json"));
        assertThat(request).contains("\"request_kind\" : \"change_request\"");
        assertThat(request).contains("\"repo_root\" : \"" + repo.toRealPath());
    }

    @Test
    void templateCommandsListShowValidateAndAnalyzeRunsWithFixtures() throws Exception {
        CapturedInvocation list = capture(() -> Main.exitCode(new String[]{"template", "list"}));
        assertThat(list.exitCode()).isZero();
        assertThat(list.stdout()).contains("implement-change", "qa-gap-guard");

        CapturedInvocation show = capture(() -> Main.exitCode(new String[]{
                "template", "show", "--template=implement-change"
        }));
        assertThat(show.exitCode()).isZero();
        assertThat(show.stdout()).contains("\"template_id\" : \"implement-change\"");
        JsonNode showJson = Json.mapper().readTree(show.stdout());
        assertThat(showJson.get("source").asText()).isEqualTo("built_in");
        JsonNode manifestReference = showJson.get("referenced_files").get(0);
        assertThat(manifestReference.get("kind").asText()).isEqualTo("manifest");
        assertThat(manifestReference.get("relative_path").asText()).isEqualTo("template.json");
        assertThat(manifestReference.has("absolute_path")).isFalse();
        List<JsonNode> referencedFiles = new ArrayList<>();
        showJson.get("referenced_files").forEach(referencedFiles::add);
        assertThat(referencedFiles).anySatisfy(reference -> {
            assertThat(reference.get("kind").asText()).isEqualTo("workflow");
            assertThat(reference.get("relative_path").asText()).isEqualTo("workflow.json");
            assertThat(reference.has("path")).isTrue();
        });

        CapturedInvocation validate = capture(() -> Main.exitCode(new String[]{
                "template", "validate", "--path=templates/review-only"
        }));
        assertThat(validate.exitCode()).isZero();
        assertThat(validate.stdout()).contains("\"status\" : \"valid\"");
        JsonNode validateJson = Json.mapper().readTree(validate.stdout());
        assertThat(validateJson.get("routing_mode").asText()).isEqualTo("structured");
        assertThat(validateJson.get("routing_mode_source").asText()).isEqualTo("explicit");
        assertThat(validateJson.at("/tool_requirements/all_of/0/command").asText()).isEqualTo("bash");
        assertThat(validateJson.at("/tool_requirements/all_of/1/command").asText()).isEqualTo("codex");
        assertThat(validateJson.at("/tool_requirements/all_of/0").has("available")).isTrue();
        assertThat(validateJson.has("warnings")).isFalse();

        Path missingToolsBundle = writeMissingToolsTemplateBundle();
        CapturedInvocation validateMissingTools = capture(() -> Main.exitCode(new String[]{
                "template", "validate", "--path=" + missingToolsBundle
        }));
        assertThat(validateMissingTools.exitCode()).isZero();
        JsonNode missingToolsJson = Json.mapper().readTree(validateMissingTools.stdout());
        assertThat(missingToolsJson.at("/tool_requirements/satisfied").asBoolean()).isFalse();
        assertThat(missingToolsJson.at("/tool_requirements/all_of/0/command").asText())
                .isEqualTo("missing-forge-test-tool");
        assertThat(missingToolsJson.at("/tool_requirements/all_of/0/available").asBoolean()).isFalse();
        assertThat(missingToolsJson.at("/tool_requirements/any_of/0/satisfied").asBoolean()).isFalse();
        assertThat(missingToolsJson.at("/tool_requirements/missing/0").asText())
                .contains("missing-forge-test-tool");

        Path rawEdgesV2Bundle = writeRawEdgesV2TemplateBundle();
        CapturedInvocation validateRawEdgesV2 = capture(() -> Main.exitCode(new String[]{
                "template", "validate", "--path=" + rawEdgesV2Bundle
        }));
        assertThat(validateRawEdgesV2.exitCode()).isEqualTo(1);
        assertThat(validateRawEdgesV2.stdout()).isEmpty();
        assertThat(validateRawEdgesV2.stderr()).contains("routing_mode must be structured");

        Path bundleRoot = writeAnalysisTemplateBundle();
        Path runs = tempDir.resolve("runs");
        Path runDir = runs.resolve("run-one");
        createAnalysisRun(runDir, bundleRoot);
        Path analysisOut = tempDir.resolve("analysis");
        CapturedInvocation analyze = capture(() -> Main.exitCode(new String[]{
                "template", "analyze-runs",
                "--path=" + bundleRoot,
                "--runs=" + runs,
                "--slug=run-one",
                "--out=" + analysisOut
        }));
        assertThat(analyze.exitCode()).isZero();
        assertThat(analyze.stderr()).isEmpty();
        assertThat(analyze.stdout()).contains(
                "\"status\" : \"ok\"",
                "\"template_id\" : \"analysis-template\"",
                "\"analyzed_run_count\" : 1",
                "\"category\" : \"prompt_edit\"",
                "\"category\" : \"artifact_contract\""
        );
        assertThat(Files.exists(analysisOut.resolve("analysis.json"))).isTrue();
        assertThat(Files.readString(analysisOut.resolve("summary.md")))
                .contains("Template Run Analysis: analysis-template", "Clarify prompt guidance");
    }

    @Test
    void templateProposeUpdateInspectsRunsAndWritesProposalArtifacts() throws Exception {
        Path bundleRoot = writeAnalysisTemplateBundle();
        Path runs = tempDir.resolve("proposal-runs");
        Path runDir = runs.resolve("run-one");
        createAnalysisRun(runDir, bundleRoot);

        Path proposalOut = tempDir.resolve("proposal");
        CapturedInvocation propose = capture(() -> Main.exitCode(new String[]{
                "template", "propose-update",
                "--path=" + bundleRoot,
                "--runs=" + runs,
                "--out=" + proposalOut,
                "--slug=run-one"
        }));
        assertThat(propose.exitCode()).isZero();
        assertThat(propose.stderr()).isEmpty();
        assertThat(propose.stdout()).contains(
                "\"status\" : \"ok\"",
                "\"suggestion_count\"",
                "\"proposal_patch\"",
                "\"proposed_files_dir\""
        );
        assertThat(Files.exists(proposalOut.resolve("analysis.json"))).isTrue();
        assertThat(Files.exists(proposalOut.resolve("summary.md"))).isTrue();
        assertThat(Files.readString(proposalOut.resolve("proposal.patch")))
                .contains("PROPOSED_UPDATES.md", "prompts/plan.txt", "scripts/publish.sh", "workflow.json");
        assertThat(Files.readString(proposalOut.resolve("proposed-files/prompts/plan.txt")))
                .contains("Run-analysis proposal");
        assertThat(Files.readString(proposalOut.resolve("proposed-files/workflow.json")))
                .contains("Run-analysis proposal");
    }

    @Test
    void templateAnalysisSummarizesSignalsAndWritesArtifactsDirectly() throws Exception {
        Path bundleRoot = writeAnalysisTemplateBundle();
        TemplateBundle bundle = Templates.loadTemplateBundleAt(bundleRoot);
        Path runsDir = tempDir.resolve("direct-analysis-runs");
        createAnalysisRun(runsDir.resolve("run-one"), bundleRoot);
        Path outDir = tempDir.resolve("direct-analysis-out");

        TemplateAnalysis.TemplateRunAnalysisResult result = TemplateAnalysis.analyzeRuns(
                bundle,
                "path",
                runsDir,
                List.of("run-one"),
                outDir);

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.analyzedRunCount()).isEqualTo(1);
        assertThat(result.skippedRunCount()).isZero();
        assertThat(result.analyzedSlugs()).containsExactly("run-one");
        assertThat(result.summary().completedRuns()).isZero();
        assertThat(result.summary().escalatedRuns()).isEqualTo(1);
        assertThat(result.summary().failedRuns()).isZero();
        assertThat(result.summary().pendingRuns()).isZero();
        assertThat(result.summary().totalDispatchFailures()).isEqualTo(1);
        assertThat(result.summary().totalHumanReworkSignals()).isEqualTo(2);
        assertThat(result.summary().totalInvalidArtifacts()).isEqualTo(1);
        assertThat(result.nodeMetrics()).anySatisfy(metric -> {
            assertThat(metric.nodeId()).isEqualTo("plan");
            assertThat(metric.kind()).isEqualTo("agent");
            assertThat(metric.extraAttempts()).isEqualTo(1);
            assertThat(metric.runSlugs()).containsExactly("run-one");
        });
        assertThat(result.nodeMetrics()).anySatisfy(metric -> {
            assertThat(metric.nodeId()).isEqualTo("review");
            assertThat(metric.kind()).isEqualTo("human");
            assertThat(metric.humanReworkSignals()).isEqualTo(2);
            assertThat(metric.loopbacks()).isEqualTo(1);
        });
        assertThat(result.nodeMetrics()).anySatisfy(metric -> {
            assertThat(metric.nodeId()).isEqualTo("publish");
            assertThat(metric.kind()).isEqualTo("command");
            assertThat(metric.dispatchFailures()).isEqualTo(1);
            assertThat(metric.escalations()).isEqualTo(1);
        });
        assertThat(result.artifactFindings()).containsExactly(
                new TemplateAnalysis.ArtifactFinding(
                        "report",
                        "publish",
                        "invalid_json",
                        "application/json",
                        bundleRoot.resolve("scripts/publish.sh").toString(),
                        1,
                        List.of("run-one"),
                        "artifact file does not contain valid JSON at "
                                + runsDir.resolve("run-one/artifacts/report.json")));
        assertThat(result.suggestions()).extracting(TemplateAnalysis.ImprovementSuggestion::id)
                .containsExactly(
                        "artifact-report-invalid_json",
                        "human-instruction-review",
                        "prompt-edit-plan",
                        "retry-policy-publish",
                        "script-edit-publish");
        assertThat(result.emittedArtifacts()).isNotNull();
        assertThat(Path.of(result.emittedArtifacts().analysisJson())).isRegularFile();
        assertThat(Path.of(result.emittedArtifacts().summaryMd())).isRegularFile();
        assertThat(Files.readString(Path.of(result.emittedArtifacts().summaryMd())))
                .contains("Artifact findings: 1", "Clarify prompt guidance for node 'plan'");
    }

    @Test
    void templateAnalysisBuildsWorkflowPromptAndScriptProposalsDirectly() throws Exception {
        Path bundleRoot = writeAnalysisTemplateBundle();
        TemplateBundle bundle = Templates.loadTemplateBundleAt(bundleRoot);
        Path runsDir = tempDir.resolve("direct-proposal-runs");
        createAnalysisRun(runsDir.resolve("run-one"), bundleRoot);
        Path outDir = tempDir.resolve("direct-proposal-out");

        TemplateAnalysis.TemplateUpdateProposalResult result = TemplateAnalysis.proposeUpdate(
                bundle,
                "path",
                runsDir,
                List.of("run-one"),
                outDir);

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.analyzedRunCount()).isEqualTo(1);
        assertThat(result.suggestionCount()).isEqualTo(5);
        assertThat(result.proposedFileCount()).isEqualTo(4);

        Path proposedFilesDir = Path.of(result.emittedArtifacts().proposedFilesDir());
        Path proposedPrompt = proposedFilesDir.resolve("prompts/plan.txt");
        Path proposedScript = proposedFilesDir.resolve("scripts/publish.sh");
        Path proposedWorkflow = proposedFilesDir.resolve("workflow.json");
        Path proposedUpdates = proposedFilesDir.resolve("PROPOSED_UPDATES.md");
        assertThat(proposedPrompt).isRegularFile();
        assertThat(proposedScript).isRegularFile();
        assertThat(proposedWorkflow).isRegularFile();
        assertThat(proposedUpdates).isRegularFile();
        assertThat(Files.readString(proposedPrompt))
                .contains("[Run-analysis proposal: prompt-edit-plan]");
        assertThat(Files.readString(proposedScript))
                .contains("# Proposed run-analysis updates:")
                .contains("[high] Tighten helper script behavior for node 'publish'");
        JsonNode workflow = Json.read(proposedWorkflow, JsonNode.class);
        assertThat(workflow.at("/nodes/1/instructions").asText())
                .contains("Run-analysis proposal: Refine the human review contract for node 'review'");
        assertThat(workflow.at("/nodes/2/retry_policy/max_attempts").asLong()).isEqualTo(2);
        assertThat(Files.readString(Path.of(result.emittedArtifacts().proposalPatch())))
                .contains("diff --git a/analysis-template/PROPOSED_UPDATES.md b/analysis-template/PROPOSED_UPDATES.md")
                .contains("diff --git a/analysis-template/prompts/plan.txt b/analysis-template/prompts/plan.txt")
                .contains("diff --git a/analysis-template/scripts/publish.sh b/analysis-template/scripts/publish.sh")
                .contains("diff --git a/analysis-template/workflow.json b/analysis-template/workflow.json");
    }

    @Test
    void templateAnalysisRejectsSelectedRunsMissingRequiredFilesDirectly() throws Exception {
        Path bundleRoot = writeAnalysisTemplateBundle();
        TemplateBundle bundle = Templates.loadTemplateBundleAt(bundleRoot);
        Path runsDir = Files.createDirectories(tempDir.resolve("missing-files-runs"));
        Files.createDirectories(runsDir.resolve("run-one"));

        assertThatThrownBy(() -> TemplateAnalysis.analyzeRuns(
                bundle,
                "path",
                runsDir,
                List.of("run-one"),
                null))
                .hasMessageContaining("run 'run-one' is missing spec.json or events.ndjson");
    }

    @Test
    void scaffoldRepoCopiesBuiltInTemplateBundle() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("scaffold"));

        CapturedInvocation invocation = capture(() -> Main.exitCode(new String[]{
                "scaffold", "repo",
                "--repo=" + repo,
                "--template=review-only"
        }));

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        assertThat(invocation.stdout()).contains("\"status\" : \"scaffolded\"");
        assertThat(Files.exists(repo.resolve(".forge/templates/review-only/workflow.json"))).isTrue();
        assertThat(Files.exists(repo.resolve(".forge/templates/review-only/template.json"))).isTrue();
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

    private record CapturedInvocation(int exitCode, String stdout, String stderr) {
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @FunctionalInterface
    private interface ExitSupplier {
        int get();
    }

    private Path writeFreezePathFixture() throws Exception {
        Path specRoot = Files.createDirectory(tempDir.resolve("spec-root-" + System.nanoTime()));
        Files.createDirectories(specRoot.resolve("scripts"));
        Files.createDirectories(specRoot.resolve("config"));
        Files.createDirectories(specRoot.resolve("hooks"));
        Files.createDirectories(specRoot.resolve("workspace"));
        Files.writeString(specRoot.resolve("scripts/agent.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(specRoot.resolve("scripts/build.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(specRoot.resolve("config/agent.json"), "{}\n");
        Files.writeString(specRoot.resolve("config/build.json"), "{}\n");
        Files.writeString(specRoot.resolve("config/token.txt"), "secret\n");
        Files.writeString(specRoot.resolve("hooks/notify.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(specRoot.resolve("spec.json"), """
                {
                  "version": 2,
                  "routing_mode": "structured",
                  "workflow_id": "freeze_paths_fixture",
                  "entry_node": "build",
                  "agents": {
                    "planner": {
                      "display_name": "Planner",
                      "runner": "codex",
                      "command": ["bash", "scripts/agent.sh", "--flag"],
                      "cwd": "workspace",
                      "env": {
                        "CONFIG_FILE": "config/agent.json"
                      },
                      "prompt_delivery": "stdin_file"
                    }
                  },
                  "artifacts": {
                    "request": {
                      "path": "artifacts/request.json",
                      "media_type": "application/json"
                    }
                  },
                  "notifications": {
                    "default_hook": {
                      "path": "hooks/notify.sh",
                      "cwd": "workspace",
                      "env": {
                        "TOKEN_FILE": "config/token.txt"
                      }
                    }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "build",
                      "inputs": ["request"],
                      "outputs": [],
                      "timeout_ms": 10000,
                      "retry_policy": { "max_attempts": 1 },
                      "message": "build",
                      "command": ["./scripts/build.sh", "config/build.json", "--mode"],
                      "cwd": "workspace",
                      "env": {
                        "BUILD_CONFIG": "config/build.json"
                      },
                      "capture": "combined",
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "loops": []
                }
                """);
        return specRoot;
    }

    private Path writeAnalysisTemplateBundle() throws Exception {
        Path bundleRoot = Files.createDirectories(tempDir.resolve("analysis-template-" + System.nanoTime()));
        Files.createDirectories(bundleRoot.resolve("prompts"));
        Files.createDirectories(bundleRoot.resolve("scripts"));
        Files.writeString(bundleRoot.resolve("template.json"), """
                {
                  "id": "analysis-template",
                  "version": 1,
                  "display_name": "Analysis Template",
                  "description": "Template used for Java run analysis tests.",
                  "workflow": "workflow.json",
                  "supports_intake": ["simple"],
                  "prompt_files": ["prompts/plan.txt"],
                  "script_files": ["scripts/publish.sh"]
                }
                """);
        Files.writeString(bundleRoot.resolve("prompts/plan.txt"), "Write a plan and respect the output contract.\n");
        Files.writeString(bundleRoot.resolve("scripts/publish.sh"), "#!/usr/bin/env bash\nset -euo pipefail\n");
        Files.writeString(bundleRoot.resolve("workflow.json"), """
                {
                  "version": 2,
                  "routing_mode": "structured",
                  "workflow_id": "analysis_workflow",
                  "entry_node": "plan",
                  "agents": {
                    "planner": {
                      "display_name": "Planner",
                      "runner": "agent",
                      "command": ["codex", "exec"],
                      "env": {},
                      "prompt_delivery": "stdin_file"
                    }
                  },
                  "artifacts": {
                    "request": {
                      "path": "artifacts/request.json",
                      "media_type": "application/json"
                    },
                    "plan": {
                      "path": "artifacts/plan.md",
                      "media_type": "text/markdown"
                    },
                    "report": {
                      "path": "artifacts/report.json",
                      "media_type": "application/json"
                    }
                  },
                  "notifications": {},
                  "nodes": [
                    {
                      "kind": "agent",
                      "id": "plan",
                      "inputs": ["request"],
                      "outputs": ["plan"],
                      "retry_policy": { "max_attempts": 1 },
                      "message": "Write the plan",
                      "agent_id": "planner",
                      "prompt_file": "prompts/plan.txt",
                      "route": { "mode": "always", "to": "review" }
                    },
                    {
                      "kind": "human",
                      "id": "review",
                      "inputs": ["request", "plan"],
                      "outputs": [],
                      "retry_policy": { "max_attempts": 1 },
                      "message": "Review the plan",
                      "fields": [
                        {
                          "name": "approval",
                          "required": true,
                          "kind": "enum",
                          "allowed_values": ["approved", "rework"]
                        }
                      ],
                      "instructions": "Use approval=approved or approval=rework.",
                      "route": {
                        "mode": "by_field",
                        "field": "approval",
                        "cases": [
                          { "equals": "approved", "to": "publish" },
                          { "equals": "rework", "continue_loop": "review_loop" }
                        ]
                      }
                    },
                    {
                      "kind": "command",
                      "id": "publish",
                      "inputs": ["request", "plan"],
                      "outputs": ["report"],
                      "retry_policy": { "max_attempts": 1 },
                      "message": "Publish the report",
                      "command_file": "scripts/publish.sh",
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "loops": [
                    {
                      "id": "review_loop",
                      "controller_node": "review",
                      "entry_node": "plan",
                      "budget": { "kind": "literal", "max_iterations": 2 },
                      "on_exhaust": { "to": "__escalate__", "reason": "review loop exhausted" }
                    }
                  ]
                }
                """);
        return bundleRoot;
    }

    private Path writeRawEdgesV2TemplateBundle() throws Exception {
        Path bundleRoot = Files.createDirectories(tempDir.resolve("raw-edges-v2"));
        Files.writeString(bundleRoot.resolve("template.json"), """
                {
                  "id": "raw-edges-v2",
                  "version": 1,
                  "display_name": "Raw Edges V2",
                  "description": "Template used for routing-mode validation.",
                  "workflow": "workflow.json",
                  "supports_intake": ["simple"]
                }
                """);
        Files.writeString(bundleRoot.resolve("workflow.json"), """
                {
                  "version": 2,
                  "routing_mode": "raw_edges",
                  "workflow_id": "raw_edges_v2",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        return bundleRoot;
    }

    private Path writeMissingToolsTemplateBundle() throws Exception {
        Path bundleRoot = Files.createDirectories(tempDir.resolve("missing-tools-template"));
        Files.writeString(bundleRoot.resolve("template.json"), """
                {
                  "id": "missing-tools-template",
                  "version": 1,
                  "display_name": "Missing Tools Template",
                  "description": "Template used for tool requirement validation.",
                  "workflow": "workflow.json",
                  "supports_intake": ["simple"]
                }
                """);
        Files.writeString(bundleRoot.resolve("workflow.json"), """
                {
                  "version": 2,
                  "tool_requirements": {
                    "all_of": ["missing-forge-test-tool"],
                    "any_of": [["missing-forge-test-alt-one", "missing-forge-test-alt-two"]]
                  },
                  "routing_mode": "structured",
                  "workflow_id": "missing_tools_workflow",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        return bundleRoot;
    }

    private static void createAnalysisRun(Path runDir, Path bundleRoot) throws Exception {
        Files.createDirectories(runDir.resolve("artifacts"));
        Files.copy(bundleRoot.resolve("workflow.json"), runDir.resolve("spec.json"));
        Files.writeString(runDir.resolve("artifacts/request.json"), "{\"goal\":\"refresh\"}\n");
        Files.writeString(runDir.resolve("artifacts/plan.md"), "Initial plan\n");
        Files.writeString(runDir.resolve("artifacts/report.json"), "not-json\n");
        appendEvents(runDir, List.of(
                EventEnvelope.of(1, "2026-03-30T12:00:00Z", "run_initialized", Map.of(
                        "run_id", "run-one",
                        "slug", "run-one",
                        "workflow_id", "analysis_workflow",
                        "spec_version", 2,
                        "entry_node", "plan"
                )),
                EventEnvelope.of(2, "2026-03-30T12:00:01Z", "node_entered", Map.of("node_id", "plan")),
                EventEnvelope.of(3, "2026-03-30T12:00:02Z", "artifact_written", Map.of(
                        "artifact", Map.of(
                                "name", "plan",
                                "path", "artifacts/plan.md",
                                "producer_node", "plan",
                                "media_type", "text/markdown"
                        )
                )),
                EventEnvelope.of(4, "2026-03-30T12:00:03Z", "transition_taken", Map.of(
                        "from", "plan",
                        "to", "review"
                )),
                EventEnvelope.of(5, "2026-03-30T12:00:04Z", "node_entered", Map.of("node_id", "review")),
                EventEnvelope.of(6, "2026-03-30T12:00:05Z", "human_input_recorded", Map.of(
                        "node_id", "review",
                        "fields", Map.of("approval", "rework")
                )),
                EventEnvelope.of(7, "2026-03-30T12:00:06Z", "transition_taken", Map.of(
                        "from", "review",
                        "to", "plan"
                )),
                EventEnvelope.of(8, "2026-03-30T12:00:07Z", "node_entered", Map.of("node_id", "plan")),
                EventEnvelope.of(9, "2026-03-30T12:00:08Z", "transition_taken", Map.of(
                        "from", "plan",
                        "to", "review"
                )),
                EventEnvelope.of(10, "2026-03-30T12:00:09Z", "node_entered", Map.of("node_id", "review")),
                EventEnvelope.of(11, "2026-03-30T12:00:10Z", "human_input_recorded", Map.of(
                        "node_id", "review",
                        "fields", Map.of("approval", "approved")
                )),
                EventEnvelope.of(12, "2026-03-30T12:00:11Z", "transition_taken", Map.of(
                        "from", "review",
                        "to", "publish"
                )),
                EventEnvelope.of(13, "2026-03-30T12:00:12Z", "node_entered", Map.of("node_id", "publish")),
                EventEnvelope.of(14, "2026-03-30T12:00:13Z", "artifact_written", Map.of(
                        "artifact", Map.of(
                                "name", "report",
                                "path", "artifacts/report.json",
                                "producer_node", "publish",
                                "media_type", "application/json"
                        )
                )),
                EventEnvelope.of(15, "2026-03-30T12:00:14Z", "node_failed", Map.of(
                        "node_id", "publish",
                        "reason", "script failed",
                        "retryable", false
                )),
                EventEnvelope.of(16, "2026-03-30T12:00:15Z", "run_escalated", Map.of(
                        "node_id", "publish",
                        "reason", "publish failed"
                ))
        ));
    }

    private static void appendEvents(Path runDir, List<EventEnvelope> events) {
        Path eventsPath = runDir.resolve("events.ndjson");
        for (EventEnvelope event : events) {
            EventLog.appendEvent(eventsPath, event);
        }
    }
}
