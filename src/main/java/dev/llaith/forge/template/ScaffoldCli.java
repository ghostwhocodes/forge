package dev.llaith.forge.template;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.cli.CliSupport;
import dev.llaith.forge.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ScaffoldCli {
    public static final String HELP = """
            forge scaffold — Repo-local Forge asset scaffolding

            USAGE
              forge scaffold repo --repo=PATH [--all] [--template=implement-change|review-only|review-and-fix|auto-review-and-fix|architecture-guard|qa-gap-guard]... [--template-path=PATH...] [--workflow=... (alias)] [--force]
            """;

    private static final Set<String> BUILT_INS = Set.of(
            "implement-change",
            "review-only",
            "review-and-fix",
            "auto-review-and-fix",
            "architecture-guard",
            "qa-gap-guard"
    );

    private ScaffoldCli() {
    }

    public static void run(List<String> args) {
        if (args.isEmpty() || CliSupport.isHelpFlag(args.getFirst())) {
            System.out.print(HELP);
            return;
        }

        String command = args.getFirst();
        if (!"repo".equals(command)) {
            throw new ForgeException("error: unknown scaffold command: " + command + "\n\n" + HELP);
        }
        List<String> rest = args.subList(1, args.size());
        if (CliSupport.containsHelp(rest)) {
            System.out.print(HELP);
            return;
        }
        RepoOptions options = parseRepo(rest);
        Templates.ScaffoldResult result = Templates.scaffoldRepo(
                Path.of(options.repo()),
                options.all(),
                options.templateIds(),
                options.templatePaths(),
                options.force()
        );
        System.out.print(Json.toPrettyJson(result));
    }

    private static RepoOptions parseRepo(List<String> args) {
        String repo = null;
        boolean all = false;
        boolean force = false;
        List<String> templateIds = new ArrayList<>();
        List<Path> templatePaths = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--repo=")) {
                repo = CliSupport.optionValue(arg);
            } else if ("--all".equals(arg)) {
                all = true;
            } else if ("--force".equals(arg)) {
                force = true;
            } else if (arg.startsWith("--template-path=")) {
                templatePaths.add(Path.of(CliSupport.optionValue(arg)));
            } else if (arg.startsWith("--template=") || arg.startsWith("--workflow=")) {
                String workflow = CliSupport.optionValue(arg);
                if (!BUILT_INS.contains(workflow)) {
                    throw new ForgeException("error: unknown workflow '" + workflow + "'");
                }
                templateIds.add(workflow);
            } else {
                throw CliSupport.unknownOption(arg);
            }
        }
        if (repo == null) {
            throw CliSupport.missingOption("--repo=PATH");
        }
        return new RepoOptions(repo, all, templateIds, templatePaths, force);
    }

    private record RepoOptions(
            String repo,
            boolean all,
            List<String> templateIds,
            List<Path> templatePaths,
            boolean force
    ) {
    }
}
