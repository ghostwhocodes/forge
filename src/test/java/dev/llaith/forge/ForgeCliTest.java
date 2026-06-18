package dev.llaith.forge;

import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.workflow.event.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class ForgeCliTest {
    @TempDir
    private Path tempDir;

    @Test
    void versionCommandAndAliasesPrintForgeVersion() {
        for (String[] args : new String[][]{{"version"}, {"-V"}, {"--version"}}) {
            CapturedInvocation invocation = capture(() -> Main.exitCode(args));

            assertThat(invocation.exitCode()).isZero();
            assertThat(invocation.stdout()).contains("forge 0.1.0");
            assertThat(invocation.stderr()).isEmpty();
        }
    }

    @Test
    void rootHelpMatchesRustCommandNamespace() {
        for (String[] args : new String[][]{{}, {"--help"}, {"-h"}}) {
            CapturedInvocation invocation = capture(() -> Main.exitCode(args));

            assertThat(invocation.exitCode()).isZero();
            assertThat(invocation.stdout()).contains("forge \u2014 Command namespace for Forge");
            assertThat(invocation.stdout()).contains("forge <command> [args...]");
            assertThat(invocation.stdout()).contains("intake");
            assertThat(invocation.stdout()).contains("template");
            assertThat(invocation.stderr()).isEmpty();
        }
    }

    @Test
    void subcommandHelpPathsExposeCurrentRustCommandSurface() {
        for (String[] args : new String[][]{
                {"intake", "--help"},
                {"intake", "simple", "--help"},
                {"run", "--help"},
                {"run", "init", "--help"},
                {"scaffold", "--help"},
                {"scaffold", "repo", "--help"},
                {"spec", "--help"},
                {"spec", "freeze-paths", "--help"},
                {"template", "--help"},
                {"template", "show", "--help"},
        }) {
            CapturedInvocation invocation = capture(() -> Main.exitCode(args));

            assertThat(invocation.exitCode()).isZero();
            assertThat(invocation.stdout()).contains("USAGE");
            assertThat(invocation.stderr()).isEmpty();
        }
    }

    @Test
    void unknownCommandsUseForgeStyleNestedErrors() {
        assertCommandError(new String[]{"mystery"}, "error: unknown command: mystery");
        assertCommandError(new String[]{"intake", "mystery"}, "error: unknown intake command: mystery");
        assertCommandError(new String[]{"run", "mystery"}, "error: unknown run command: mystery");
        assertCommandError(new String[]{"scaffold", "mystery"}, "error: unknown scaffold command: mystery");
        assertCommandError(new String[]{"spec", "mystery"}, "error: unknown spec command: mystery");
        assertCommandError(new String[]{"template", "mystery"}, "error: unknown template command: mystery");
    }

    @Test
    void intakeParserReportsRequiredAndInvalidArguments() {
        assertCommandError(new String[]{"intake", "simple", "--repo=/tmp"}, "error: missing --goal=TEXT");
        assertCommandError(new String[]{"intake", "spec", "--repo=/tmp"}, "error: missing --spec=PATH");
        assertCommandError(new String[]{"intake", "issue", "--text=hello"}, "error: missing --repo=PATH");
        assertCommandError(new String[]{"intake", "review"}, "error: missing --repo=PATH");
        assertCommandError(
                new String[]{"intake", "issue", "--repo=/tmp", "--file=a", "--text=b"},
                "error: provide exactly one of --file=PATH or --text=TEXT"
        );
        assertCommandError(
                new String[]{"intake", "review", "--repo=/tmp", "--mystery"},
                "error: unknown option: --mystery"
        );
    }

    @Test
    void validIntakeSurfacesWriteRequestArtifacts() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path out = tempDir.resolve("out");
        Path spec = tempDir.resolve("feature.md");
        Path issue = tempDir.resolve("issue.md");
        Files.writeString(spec, "Feature spec\n");
        Files.writeString(issue, "Review issue\n");

        assertCommandSuccess(
                new String[]{"intake", "simple", "--repo=" + repo, "--goal=Do it", "--out=" + out,
                        "--template=implement-change", "--scope=src", "--constraint=fast", "--non-goal=slow",
                        "--acceptance=done", "--check=mvn test", "--note=note", "--base-branch=main",
                        "--target-branch=feature", "--config-json={}"},
                "\"request_kind\" : \"change_request\""
        );
        assertThat(Files.readString(out.resolve("artifacts/request.json")))
                .contains("\"request_kind\" : \"change_request\"");
        assertCommandSuccess(
                new String[]{"intake", "spec", "--repo=" + repo, "--spec=" + spec, "--goal=Implement it",
                        "--workflow=templates/review-only/workflow.json",
                        "--out=" + tempDir.resolve("spec-out")},
                "\"request_kind\" : \"feature_spec\""
        );
        assertCommandSuccess(
                new String[]{"intake", "issue", "--repo=" + repo, "--file=" + issue,
                        "--template-path=templates/implement-change",
                        "--out=" + tempDir.resolve("issue-file-out")},
                "\"request_kind\" : \"review_issue\""
        );
        assertCommandSuccess(
                new String[]{"intake", "issue", "--repo=" + repo, "--text=Investigate",
                        "--out=" + tempDir.resolve("issue-text-out")},
                "\"request_kind\" : \"review_issue\""
        );
        assertCommandSuccess(
                new String[]{"intake", "review", "--repo=" + repo, "--fix", "--goal=Review",
                        "--out=" + tempDir.resolve("review-out")},
                "\"request_kind\" : \"review_request\""
        );
    }

    @Test
    void runParserReportsRequiredAndInvalidArguments() {
        assertCommandError(new String[]{"run", "status", "--slug=sample"}, "error: missing --runs=DIR");
        assertCommandError(
                new String[]{"run", "init", "--spec=workflow.json", "--runs=/tmp/runs"},
                "error: missing --slug=NAME"
        );
        assertCommandError(
                new String[]{"run", "status", "--runs=/tmp/runs", "--slug=bad/slug"},
                "error: invalid slug"
        );
        assertCommandError(
                new String[]{"run", "complete-dispatch", "--runs=/tmp/runs", "--slug=sample", "--dispatch-id=d1",
                        "--decision=missing-equals"},
                "error: invalid decision, expected KEY=VALUE"
        );
        assertCommandError(
                new String[]{"run", "fail-dispatch", "--runs=/tmp/runs", "--slug=sample", "--dispatch-id=d1",
                        "--reason=nope", "--retryable=maybe"},
                "error: invalid retryable, expected true or false"
        );
        assertCommandError(
                new String[]{"run", "auto", "--runs=/tmp/runs", "--slug=sample", "--watch=verbose"},
                "error: invalid --watch mode 'verbose', expected pretty, jsonl, or summary"
        );
        assertCommandError(
                new String[]{"run", "watch", "--runs=/tmp/runs", "--slug=sample", "--interval-ms=bad"},
                "error: invalid interval-ms"
        );
        assertCommandError(
                new String[]{"run", "exec-dispatch", "--runs=/tmp/runs", "--slug=sample", "--bad"},
                "error: unknown option: --bad"
        );
        assertCommandError(
                new String[]{"run", "complete-dispatch", "--runs=/tmp/runs", "--slug=sample"},
                "error: missing --dispatch-id=ID"
        );
        assertCommandError(
                new String[]{"run", "fail-dispatch", "--runs=/tmp/runs", "--slug=sample", "--dispatch-id=d1"},
                "error: missing --reason=TEXT"
        );
        assertCommandError(
                new String[]{"run", "resolve-human", "--runs=/tmp/runs", "--slug=sample"},
                "error: missing --field=KEY=VALUE"
        );
    }

    @Test
    void remainingRunSurfacesReachRuntimeLoadingBoundary() {
        Path runs = tempDir.resolve("missing-runs");
        assertCommandError(
                new String[]{"run", "auto", "--runs=" + runs, "--slug=sample", "--watch", "--tee"},
                "failed to read"
        );
        assertCommandError(
                new String[]{"run", "auto", "--runs=" + runs, "--slug=sample", "--watch=jsonl"},
                "failed to read"
        );
        assertCommandError(
                new String[]{"run", "auto", "--runs=" + runs, "--slug=sample", "--watch=pretty"},
                "failed to read"
        );
        assertCommandError(
                new String[]{"run", "auto", "--runs=" + runs, "--slug=sample", "--watch=summary"},
                "failed to read"
        );
        for (String command : new String[]{"recover", "show-human"}) {
            assertCommandError(
                    new String[]{"run", command, "--runs=" + runs, "--slug=sample"},
                    "failed to read"
            );
        }
        assertCommandError(
                new String[]{"run", "watch", "--runs=" + runs, "--slug=sample", "--interval-ms=1000",
                        "--jsonl", "--until-terminal"},
                "failed to read"
        );
        assertCommandError(
                new String[]{"run", "watch", "--runs=" + runs, "--slug=sample", "--summary"},
                "failed to read"
        );
        assertCommandError(
                new String[]{"run", "resolve-human", "--runs=" + runs, "--slug=sample",
                        "--field=status=accepted", "--dry-run"},
                "failed to read"
        );
    }

    @Test
    void scaffoldSpecAndTemplateParsersReportRequiredAndInvalidArguments() {
        assertCommandError(new String[]{"scaffold", "repo"}, "error: missing --repo=PATH");
        assertCommandError(
                new String[]{"scaffold", "repo", "--repo=/tmp/repo", "--template=missing"},
                "error: unknown workflow 'missing'"
        );
        assertCommandError(new String[]{"spec", "freeze-paths"}, "error: missing --spec=PATH");
        assertCommandError(
                new String[]{"spec", "freeze-paths", "--spec=workflow.json", "--freeze=bad"},
                "error: unknown freeze group 'bad'"
        );
        assertCommandError(
                new String[]{"spec", "freeze-paths", "--spec=workflow.json", "--bad"},
                "error: unknown option: --bad"
        );
        assertCommandError(new String[]{"template", "show"}, "error: missing --template=NAME or --path=PATH");
        assertCommandError(
                new String[]{"template", "show", "--template=implement-change", "--path=/tmp/template"},
                "error: choose only one of --template=NAME or --path=PATH"
        );
        assertCommandError(new String[]{"template", "list", "--template=x"}, "error: unknown option: --template=x");
        assertCommandError(
                new String[]{"template", "analyze-runs", "--template=implement-change"},
                "error: missing --runs=DIR"
        );
        assertCommandError(
                new String[]{"template", "analyze-runs", "--template=implement-change", "--runs=/tmp/runs",
                        "--slug=bad/slug"},
                "error: invalid slug"
        );
        assertCommandError(
                new String[]{"template", "propose-update", "--template=implement-change", "--runs=/tmp/runs"},
                "error: missing --out=DIR"
        );
    }

    @Test
    void validScaffoldSpecAndTemplateSurfacesProduceJson() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("scaffold-repo"));
        Path repoSubset = Files.createDirectory(tempDir.resolve("scaffold-subset"));
        Path repoAlias = Files.createDirectory(tempDir.resolve("scaffold-alias"));
        Path runs = Files.createDirectory(tempDir.resolve("template-runs"));
        Path analysisOut = tempDir.resolve("analysis");
        Path proposalOut = tempDir.resolve("proposal");
        Path frozenSpec = tempDir.resolve("frozen.json");
        writeMinimalAnalysisRun(
                runs,
                "implement-sample",
                Path.of("templates/implement-change/workflow.json"),
                "implement_change",
                "plan");
        writeMinimalAnalysisRun(
                runs,
                "review-sample",
                Path.of("templates/review-only/workflow.json"),
                "review_only",
                "review_scan");

        assertCommandSuccess(
                new String[]{"scaffold", "repo", "--repo=" + repo, "--all", "--force"},
                "\"status\" : \"scaffolded\""
        );
        assertThat(Files.exists(repo.resolve(".forge/templates/implement-change/workflow.json"))).isTrue();
        assertCommandSuccess(
                new String[]{"scaffold", "repo", "--repo=" + repoSubset, "--template=review-only"},
                "\"review-only\""
        );
        assertCommandSuccess(
                new String[]{"scaffold", "repo", "--repo=" + repoAlias, "--workflow=qa-gap-guard"},
                "\"qa-gap-guard\""
        );
        assertCommandSuccess(
                new String[]{"spec", "freeze-paths", "--spec=templates/implement-change/workflow.json", "--write",
                        "--out=" + frozenSpec, "--freeze=hooks,cwd,commands,env"},
                "\"status\" : \"written\""
        );
        assertCommandSuccess(
                new String[]{"template", "show", "--template=implement-change"},
                "\"template_id\" : \"implement-change\""
        );
        assertCommandSuccess(
                new String[]{"template", "validate", "--path=templates/review-only"},
                "\"status\" : \"valid\""
        );
        assertCommandSuccess(
                new String[]{"template", "analyze-runs", "--template=implement-change", "--runs=" + runs,
                        "--slug=implement-sample", "--out=" + analysisOut},
                "\"status\" : \"ok\""
        );
        assertCommandSuccess(
                new String[]{"template", "propose-update", "--path=templates/review-only", "--runs=" + runs,
                        "--out=" + proposalOut, "--slug=review-sample"},
                "\"status\" : \"ok\""
        );
        assertThat(Files.exists(proposalOut.resolve("proposal.patch"))).isTrue();
        assertCommandSuccess(
                new String[]{"template", "list"},
                "\"status\" : \"ok\""
        );
    }

    @Test
    void genericRuntimeFailuresReturnExitOne() {
        CapturedInvocation invocation = capture(() -> ForgeCli.execute((String[]) null));

        assertThat(invocation.exitCode()).isEqualTo(1);
        assertThat(invocation.stdout()).isEmpty();
        assertThat(invocation.stderr()).isNotEmpty();
    }

    @Test
    void forgeExceptionConstructorsPreserveMessageCauseAndExitCode() {
        IllegalStateException cause = new IllegalStateException("cause");

        assertThat(new ForgeException("message").exitCode()).isEqualTo(1);
        assertThat(new ForgeException("message", 5).exitCode()).isEqualTo(5);

        ForgeException defaultExitWithCause = new ForgeException("message", cause);
        assertThat(defaultExitWithCause.exitCode()).isEqualTo(1);
        assertThat(defaultExitWithCause).hasCause(cause);

        ForgeException customExitWithCause = new ForgeException("message", cause, 9);
        assertThat(customExitWithCause.exitCode()).isEqualTo(9);
        assertThat(customExitWithCause).hasCause(cause);
    }

    private static void assertCommandError(String[] args, String expectedError) {
        CapturedInvocation invocation = capture(() -> Main.exitCode(args));

        assertThat(invocation.exitCode()).isEqualTo(1);
        assertThat(invocation.stdout()).isEmpty();
        assertThat(invocation.stderr()).contains(expectedError);
    }

    private static void assertCommandSuccess(String[] args, String expectedOutput) {
        CapturedInvocation invocation = capture(() -> Main.exitCode(args));

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        assertThat(invocation.stdout()).contains(expectedOutput);
    }

    private static void writeMinimalAnalysisRun(
            Path runsDir,
            String slug,
            Path specPath,
            String workflowId,
            String entryNode
    ) throws Exception {
        Path runDir = runsDir.resolve(slug);
        Files.createDirectories(runDir.resolve("artifacts"));
        Files.copy(specPath, runDir.resolve("spec.json"));
        EventLog.appendEvent(runDir.resolve("events.ndjson"), EventEnvelope.of(
                1,
                "2026-03-30T12:00:00Z",
                "run_initialized",
                Map.of(
                        "run_id", slug,
                        "slug", slug,
                        "workflow_id", workflowId,
                        "spec_version", 2,
                        "entry_node", entryNode
                )));
        EventLog.appendEvent(runDir.resolve("events.ndjson"), EventEnvelope.of(
                2,
                "2026-03-30T12:00:01Z",
                "run_completed",
                Map.of("message", "done")));
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

    @FunctionalInterface
    private interface ExitSupplier {
        int get();
    }
}
