package dev.llaith.forge.intake;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.cli.CliSupport;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.template.TemplateBundle;
import dev.llaith.forge.template.TemplateIntakeMode;
import dev.llaith.forge.template.Templates;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IntakeCli {
    public static final String HELP = """
            forge intake — Request packet normalization

            USAGE
              forge intake simple --repo=PATH --goal=TEXT [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--out=DIR] [--scope=PATH...] [--constraint=TEXT...] [--non-goal=TEXT...] [--acceptance=TEXT...] [--check=TEXT...] [--note=TEXT...] [--base-branch=NAME] [--target-branch=NAME] [--config=PATH|--config-json=JSON]
              forge intake spec --repo=PATH --spec=PATH [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal=TEXT] [--out=DIR] [--scope=PATH...] [--constraint=TEXT...] [--non-goal=TEXT...] [--acceptance=TEXT...] [--check=TEXT...] [--note=TEXT...] [--base-branch=NAME] [--target-branch=NAME] [--config=PATH|--config-json=JSON]
              forge intake issue --repo=PATH (--file=PATH | --text=TEXT) [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal=TEXT] [--out=DIR] [--scope=PATH...] [--constraint=TEXT...] [--non-goal=TEXT...] [--acceptance=TEXT...] [--check=TEXT...] [--note=TEXT...] [--base-branch=NAME] [--target-branch=NAME] [--config=PATH|--config-json=JSON]
              forge intake review --repo=PATH [--template=NAME] [--template-path=PATH] [--workflow=PATH] [--goal=TEXT] [--out=DIR] [--scope=PATH...] [--constraint=TEXT...] [--non-goal=TEXT...] [--acceptance=TEXT...] [--check=TEXT...] [--note=TEXT...] [--base-branch=NAME] [--target-branch=NAME] [--config=PATH|--config-json=JSON] [--fix]
            """;

    private static final String REQUEST_ARTIFACT_RELATIVE_PATH = "artifacts/request.json";

    private IntakeCli() {
    }

    public static void run(List<String> args) {
        if (args.isEmpty() || CliSupport.isHelpFlag(args.getFirst())) {
            System.out.print(HELP);
            return;
        }

        String command = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        if (CliSupport.containsHelp(rest)) {
            System.out.print(HELP);
            return;
        }
        IntakeResult result = switch (command) {
            case "simple" -> simple(rest);
            case "spec" -> spec(rest);
            case "issue" -> issue(rest);
            case "review" -> review(rest);
            default -> throw new ForgeException("error: unknown intake command: " + command + "\n\n" + HELP);
        };
        System.out.print(Json.toPrettyJson(result));
    }

    private static IntakeResult simple(List<String> args) {
        CommonOptions common = parseCommon(args);
        Path repoRoot = repoRoot(common);
        String goal = CliSupport.requireNonBlank(common.goal(), "--goal=TEXT");
        WorkflowSelection workflow = resolveWorkflow(common, IntakeMode.SIMPLE);
        Map<String, Object> request = requestBase("change_request", repoRoot, goal, common, false, false);
        request.put("sources", List.of(source("inline_text", "user_request", null, goal)));
        return writeRequest(common, request, workflow);
    }

    private static IntakeResult spec(List<String> args) {
        CommonOptions common = new CommonOptions();
        String specPath = null;
        for (String arg : args) {
            if (parseCommonOption(arg, common)) {
                continue;
            }
            if (arg.startsWith("--spec=")) {
                specPath = CliSupport.optionValue(arg);
            } else {
                throw unknown(arg);
            }
        }
        Path repoRoot = repoRoot(common);
        FileSource source = loadFile(CliSupport.requireValue(specPath, "--spec=PATH"));
        String goal = common.goal() == null || common.goal().isBlank()
                ? "Implement the feature described in the provided spec."
                : common.goal();
        WorkflowSelection workflow = resolveWorkflow(common, IntakeMode.SPEC);
        Map<String, Object> request = requestBase("feature_spec", repoRoot, goal, common, false, false);
        request.put("sources", List.of(source("spec_file", "feature_spec", source.absolutePath().toString(), source.content())));
        return writeRequest(common, request, workflow);
    }

    private static IntakeResult issue(List<String> args) {
        CommonOptions common = new CommonOptions();
        String file = null;
        String text = null;
        for (String arg : args) {
            if (parseCommonOption(arg, common)) {
                continue;
            }
            if (arg.startsWith("--file=")) {
                file = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--text=")) {
                text = CliSupport.optionValue(arg);
            } else {
                throw unknown(arg);
            }
        }
        if ((file == null) == (text == null)) {
            throw new ForgeException("error: provide exactly one of --file=PATH or --text=TEXT");
        }
        Path repoRoot = repoRoot(common);
        String goal = common.goal() == null || common.goal().isBlank()
                ? "Address the provided review issue."
                : common.goal();
        WorkflowSelection workflow = resolveWorkflow(common, IntakeMode.ISSUE);
        Map<String, Object> request = requestBase("review_issue", repoRoot, goal, common, false, false);
        if (file != null) {
            FileSource source = loadFile(file);
            request.put("sources", List.of(source(
                    "review_comment_file",
                    "review_issue",
                    source.absolutePath().toString(),
                    source.content()
            )));
        } else {
            request.put("sources", List.of(source("issue_text", "review_issue", null, text)));
        }
        return writeRequest(common, request, workflow);
    }

    private static IntakeResult review(List<String> args) {
        CommonOptions common = new CommonOptions();
        boolean fix = false;
        for (String arg : args) {
            if (parseCommonOption(arg, common)) {
                continue;
            }
            if ("--fix".equals(arg)) {
                fix = true;
            } else {
                throw unknown(arg);
            }
        }
        Path repoRoot = repoRoot(common);
        String goal = common.goal() == null || common.goal().isBlank()
                ? (fix
                ? "Review the current branch, curate the findings, and fix the accepted issues."
                : "Review the current branch for correctness issues and actionable risks.")
                : common.goal();
        WorkflowSelection workflow = resolveWorkflow(common, fix ? IntakeMode.REVIEW_FIX : IntakeMode.REVIEW);
        Map<String, Object> request = requestBase("review_request", repoRoot, goal, common, !fix, fix);
        request.put("sources", List.of(source("inline_text", "user_request", null, goal)));
        return writeRequest(common, request, workflow);
    }

    private static CommonOptions parseCommon(List<String> args) {
        CommonOptions common = new CommonOptions();
        for (String arg : args) {
            if (!parseCommonOption(arg, common)) {
                throw unknown(arg);
            }
        }
        return common;
    }

    private static boolean parseCommonOption(String arg, CommonOptions common) {
        if (arg.startsWith("--repo=")) {
            common.repo = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--goal=")) {
            common.goal = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--out=")) {
            common.outDir = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--template=")) {
            common.templateId = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--template-path=")) {
            common.templatePath = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--workflow=")) {
            common.workflowPath = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--scope=")) {
            common.scopePaths.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--constraint=")) {
            common.constraints.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--non-goal=")) {
            common.nonGoals.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--acceptance=")) {
            common.acceptanceCriteria.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--check=")) {
            common.requiredChecks.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--note=")) {
            common.humanNotes.add(CliSupport.optionValue(arg));
        } else if (arg.startsWith("--base-branch=")) {
            common.baseBranch = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--target-branch=")) {
            common.targetBranch = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--config=")) {
            setWorkflowConfig(common, Json.read(Path.of(CliSupport.optionValue(arg)), JsonNode.class));
        } else if (arg.startsWith("--config-json=")) {
            setWorkflowConfig(common, Json.fromJson(CliSupport.optionValue(arg), JsonNode.class));
        } else {
            return false;
        }
        return true;
    }

    private static void setWorkflowConfig(CommonOptions common, JsonNode config) {
        if (common.workflowConfig != null) {
            throw new ForgeException("error: choose only one of --config=PATH or --config-json=JSON");
        }
        if (!config.isObject()) {
            throw new ForgeException("error: workflow config must be a JSON object");
        }
        common.workflowConfig = config;
    }

    private static WorkflowSelection resolveWorkflow(CommonOptions common, IntakeMode intakeMode) {
        int selectionCount = (common.templateId() == null ? 0 : 1)
                + (common.templatePath() == null ? 0 : 1)
                + (common.workflowPath() == null ? 0 : 1);
        if (selectionCount > 1) {
            throw new ForgeException("error: choose only one of --template=NAME, --template-path=PATH, or --workflow=PATH");
        }
        if (common.workflowPath() != null) {
            Path workflowPath = Path.of(common.workflowPath()).toAbsolutePath().normalize();
            WorkflowSpec workflow = Templates.loadSourceWorkflow(workflowPath);
            return new WorkflowSelection(null, workflow.workflowId(), workflowPath.toString());
        }

        TemplateBundle bundle;
        if (common.templateId() != null) {
            bundle = Templates.loadBuiltInTemplate(common.templateId());
            ensureSupports(bundle, intakeMode);
        } else if (common.templatePath() != null) {
            bundle = Templates.loadTemplateBundleAt(Path.of(common.templatePath()));
            ensureSupports(bundle, intakeMode);
        } else {
            bundle = Templates.loadBuiltInTemplates().stream()
                    .filter(candidate -> candidate.manifest().intakeModes().contains(intakeMode.templateMode()))
                    .findFirst()
                    .orElseThrow(() -> new ForgeException(
                            "error: no built-in template supports intake mode '" + intakeMode.label() + "'"
                    ));
        }
        return new WorkflowSelection(bundle.manifest().id(), bundle.workflow().workflowId(), bundle.workflowPath().toString());
    }

    private static void ensureSupports(TemplateBundle bundle, IntakeMode intakeMode) {
        if (!bundle.manifest().intakeModes().contains(intakeMode.templateMode())) {
            throw new ForgeException(
                    "error: template '" + bundle.manifest().id()
                            + "' does not support intake mode '" + intakeMode.label() + "'"
            );
        }
    }

    private static Map<String, Object> requestBase(
            String requestKind,
            Path repoRoot,
            String goal,
            CommonOptions common,
            boolean discoverOnly,
            boolean fixDiscoveredIssues
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("request_kind", requestKind);
        request.put("repo_root", repoRoot.toString());
        request.put("goal", goal);
        request.put("scope_paths", common.scopePaths());
        request.put("constraints", common.constraints());
        request.put("non_goals", common.nonGoals());
        request.put("acceptance_criteria", common.acceptanceCriteria());
        request.put("required_checks", common.requiredChecks());
        request.put("review_policy", Map.of(
                "discover_only", discoverOnly,
                "fix_discovered_issues", fixDiscoveredIssues
        ));
        request.put("doc_policy", Map.of("update_docs", "if_needed"));
        request.put("human_notes", common.humanNotes());
        request.put("selected_findings", List.of());
        if (common.baseBranch() != null) {
            request.put("base_branch", common.baseBranch());
        }
        if (common.targetBranch() != null) {
            request.put("target_branch", common.targetBranch());
        }
        if (common.workflowConfig() != null) {
            request.put("workflow_config", common.workflowConfig());
        }
        return request;
    }

    private static Map<String, Object> source(String kind, String label, String path, String content) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", kind);
        source.put("label", label);
        if (path != null) {
            source.put("path", path);
        }
        if (content != null) {
            source.put("content", content);
        }
        return source;
    }

    private static IntakeResult writeRequest(CommonOptions common, Map<String, Object> request, WorkflowSelection workflow) {
        Path outDir = common.outDir() == null ? Path.of("").toAbsolutePath() : Path.of(common.outDir()).toAbsolutePath();
        Filesystem.ensureDirectory(outDir);
        Path requestPath = outDir.resolve(REQUEST_ARTIFACT_RELATIVE_PATH);
        Filesystem.writeUtf8(requestPath, Json.toPrettyJson(request));
        return new IntakeResult(
                "written",
                request.get("request_kind").toString(),
                workflow.templateId(),
                workflow.workflowId(),
                workflow.workflowPath(),
                requestPath.toString()
        );
    }

    private static Path repoRoot(CommonOptions common) {
        return Filesystem.requireExistingDirectory(
                Path.of(CliSupport.requireValue(common.repo(), "--repo=PATH")),
                "repo root");
    }

    private static FileSource loadFile(String path) {
        Path absolute = Path.of(path).toAbsolutePath().normalize();
        String content = Filesystem.readUtf8(absolute);
        return new FileSource(absolute, content);
    }

    private static ForgeException unknown(String arg) {
        return CliSupport.unknownOption(arg);
    }

    private enum IntakeMode {
        SIMPLE("simple", TemplateIntakeMode.SIMPLE),
        SPEC("spec", TemplateIntakeMode.SPEC),
        ISSUE("issue", TemplateIntakeMode.ISSUE),
        REVIEW("review", TemplateIntakeMode.REVIEW),
        REVIEW_FIX("review_fix", TemplateIntakeMode.REVIEW_FIX);

        private final String label;
        private final TemplateIntakeMode templateMode;

        IntakeMode(String label, TemplateIntakeMode templateMode) {
            this.label = label;
            this.templateMode = templateMode;
        }

        private String label() {
            return label;
        }

        private TemplateIntakeMode templateMode() {
            return templateMode;
        }
    }

    private static final class CommonOptions {
        private String repo;
        private String goal;
        private String outDir;
        private String templateId;
        private String templatePath;
        private String workflowPath;
        private final List<String> scopePaths = new ArrayList<>();
        private final List<String> constraints = new ArrayList<>();
        private final List<String> nonGoals = new ArrayList<>();
        private final List<String> acceptanceCriteria = new ArrayList<>();
        private final List<String> requiredChecks = new ArrayList<>();
        private final List<String> humanNotes = new ArrayList<>();
        private String baseBranch;
        private String targetBranch;
        private JsonNode workflowConfig;

        private String repo() {
            return repo;
        }

        private String goal() {
            return goal;
        }

        private String outDir() {
            return outDir;
        }

        private String templateId() {
            return templateId;
        }

        private String templatePath() {
            return templatePath;
        }

        private String workflowPath() {
            return workflowPath;
        }

        private List<String> scopePaths() {
            return scopePaths;
        }

        private List<String> constraints() {
            return constraints;
        }

        private List<String> nonGoals() {
            return nonGoals;
        }

        private List<String> acceptanceCriteria() {
            return acceptanceCriteria;
        }

        private List<String> requiredChecks() {
            return requiredChecks;
        }

        private List<String> humanNotes() {
            return humanNotes;
        }

        private String baseBranch() {
            return baseBranch;
        }

        private String targetBranch() {
            return targetBranch;
        }

        private JsonNode workflowConfig() {
            return workflowConfig;
        }
    }

    private record FileSource(Path absolutePath, String content) {
    }

    private record WorkflowSelection(String templateId, String workflowId, String workflowPath) {
    }

    private record IntakeResult(
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("request_kind") String requestKind,
            @com.fasterxml.jackson.annotation.JsonProperty("template_id") String templateId,
            @com.fasterxml.jackson.annotation.JsonProperty("workflow_id") String workflowId,
            @com.fasterxml.jackson.annotation.JsonProperty("workflow_path") String workflowPath,
            @com.fasterxml.jackson.annotation.JsonProperty("request_path") String requestPath
    ) {
    }
}
