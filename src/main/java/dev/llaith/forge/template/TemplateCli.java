package dev.llaith.forge.template;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.cli.CliSupport;
import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.spec.ToolRequirementChecker;
import dev.llaith.forge.template.analysis.TemplateAnalysis;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateCli {
    public static final String HELP = """
            forge template - Template discovery and validation

            USAGE
              forge template list
              forge template show (--template=NAME | --path=PATH)
              forge template validate (--template=NAME | --path=PATH)
              forge template analyze-runs (--template=NAME | --path=PATH) --runs=DIR [--slug=NAME...] [--out=DIR]
              forge template propose-update (--template=NAME | --path=PATH) --runs=DIR --out=DIR [--slug=NAME...]
            """;

    private TemplateCli() {
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
        switch (command) {
            case "list" -> list(rest);
            case "show" -> show(rest);
            case "validate" -> validate(rest);
            case "analyze-runs" -> analyzeRuns(rest);
            case "propose-update" -> proposeUpdate(rest);
            default -> throw new ForgeException("error: unknown template command: " + command + "\n\n" + HELP);
        }
    }

    private static void list(List<String> args) {
        for (String arg : args) {
            throw CliSupport.unknownOption(arg);
        }
        List<Map<String, Object>> templates = Templates.loadBuiltInTemplates().stream()
                .map(bundle -> Map.<String, Object>of(
                        "id", bundle.manifest().id(),
                        "display_name", bundle.manifest().displayName(),
                        "description", bundle.manifest().description(),
                        "workflow_id", bundle.workflow().workflowId(),
                        "workflow_path", bundle.workflowPath().toString(),
                        "supports_intake", bundle.manifest().intakeModes(),
                        "tags", bundle.manifest().tagList()
                ))
                .toList();
        System.out.print(Json.toPrettyJson(Map.of("status", "ok", "templates", templates)));
    }

    private static void show(List<String> args) {
        Selector selector = parseSelector(args);
        TemplateBundle bundle = loadSelected(selector);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("source", selectedSource(selector));
        payload.put("template_id", bundle.manifest().id());
        payload.put("root_dir", bundle.rootDir().toString());
        payload.put("workflow_id", bundle.workflow().workflowId());
        payload.put("workflow_path", bundle.workflowPath().toString());
        payload.put("manifest", bundle.manifest());
        payload.put("referenced_files", bundle.referencedFiles());
        System.out.print(Json.toPrettyJson(payload));
    }

    private static void validate(List<String> args) {
        Selector selector = parseSelector(args);
        TemplateBundle bundle = loadSelected(selector);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "valid");
        payload.put("source", selectedSource(selector));
        payload.put("template_id", bundle.manifest().id());
        payload.put("root_dir", bundle.rootDir().toString());
        payload.put("workflow_id", bundle.workflow().workflowId());
        payload.put("workflow_path", bundle.workflowPath().toString());
        payload.put("routing_mode", bundle.workflow().effectiveRoutingMode());
        payload.put("routing_mode_source", bundle.workflow().routingModeSource());
        payload.put("tool_requirements", ToolRequirementChecker.check(
                bundle.workflow().effectiveToolRequirements()).toPayload());
        List<String> warnings = bundle.workflow().routingModeWarnings();
        if (!warnings.isEmpty()) {
            payload.put("warnings", warnings);
        }
        System.out.print(Json.toPrettyJson(payload));
    }

    private static void analyzeRuns(List<String> args) {
        AnalyzeOptions options = parseAnalyzeOptions(args, false);
        TemplateBundle bundle = loadSelected(options.selector());
        System.out.print(Json.toPrettyJson(TemplateAnalysis.analyzeRuns(
                bundle,
                selectedSource(options.selector()),
                Path.of(options.runsDir()),
                options.slugs(),
                options.outDir().isBlank() ? null : Path.of(options.outDir()))));
    }

    private static void proposeUpdate(List<String> args) {
        AnalyzeOptions options = parseAnalyzeOptions(args, true);
        TemplateBundle bundle = loadSelected(options.selector());
        System.out.print(Json.toPrettyJson(TemplateAnalysis.proposeUpdate(
                bundle,
                selectedSource(options.selector()),
                Path.of(options.runsDir()),
                options.slugs(),
                Path.of(options.outDir()))));
    }

    private static AnalyzeOptions parseAnalyzeOptions(List<String> args, boolean requiresOut) {
        Selector selector = new Selector();
        String runs = null;
        String out = null;
        List<String> slugs = new ArrayList<>();
        for (String arg : args) {
            if (parseSelectorOption(arg, selector)) {
                continue;
            }
            if (arg.startsWith("--runs=")) {
                runs = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--slug=")) {
                slugs.add(RunSlug.parse(CliSupport.optionValue(arg)).asString());
            } else if (arg.startsWith("--out=")) {
                out = CliSupport.optionValue(arg);
            } else {
                throw CliSupport.unknownOption(arg);
            }
        }
        selector.require();
        if (runs == null) {
            throw CliSupport.missingOption("--runs=DIR");
        }
        if (requiresOut && out == null) {
            throw CliSupport.missingOption("--out=DIR");
        }
        return new AnalyzeOptions(selector, runs, out == null ? "" : out, slugs);
    }

    private static Selector parseSelector(List<String> args) {
        Selector selector = new Selector();
        for (String arg : args) {
            if (parseSelectorOption(arg, selector)) {
                continue;
            }
            throw CliSupport.unknownOption(arg);
        }
        selector.require();
        return selector;
    }

    private static TemplateBundle loadSelected(Selector selector) {
        if (selector.template != null) {
            return Templates.loadBuiltInTemplate(selector.template);
        }
        return Templates.loadTemplateBundleAt(Path.of(selector.path));
    }

    private static String selectedSource(Selector selector) {
        return selector.template == null ? "path" : "built_in";
    }

    private static boolean parseSelectorOption(String arg, Selector selector) {
        if (arg.startsWith("--template=")) {
            selector.template = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--path=")) {
            selector.path = CliSupport.optionValue(arg);
        } else {
            return false;
        }
        return true;
    }

    private static final class Selector {
        private String template;
        private String path;

        private void require() {
            if (template != null && path != null) {
                throw new ForgeException("error: choose only one of --template=NAME or --path=PATH");
            }
            if (template == null && path == null) {
                throw new ForgeException("error: missing --template=NAME or --path=PATH");
            }
        }
    }

    private record AnalyzeOptions(Selector selector, String runsDir, String outDir, List<String> slugs) {
    }
}
