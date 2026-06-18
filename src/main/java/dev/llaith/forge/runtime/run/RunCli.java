package dev.llaith.forge.runtime.run;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.cli.CliSupport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class RunCli {
    public static final String HELP = """
            forge run — Forge runtime

            USAGE
              forge run init --spec=PATH --runs=DIR --slug=NAME [--artifact=KEY=PATH...] [--force]
              forge run auto --runs=DIR --slug=NAME [--watch|--watch=pretty|--watch=jsonl|--watch=summary] [--tee]
              forge run next --runs=DIR --slug=NAME
              forge run recover --runs=DIR --slug=NAME
              forge run status --runs=DIR --slug=NAME
              forge run show-human --runs=DIR --slug=NAME
              forge run show-progress --runs=DIR --slug=NAME
              forge run watch --runs=DIR --slug=NAME [--interval-ms=1000] [--jsonl|--summary] [--until-terminal]
              forge run exec-dispatch --runs=DIR --slug=NAME [--tee]
              forge run complete-dispatch --runs=DIR --slug=NAME --dispatch-id=ID [--decision=KEY=VALUE...]
              forge run fail-dispatch --runs=DIR --slug=NAME --dispatch-id=ID --reason=TEXT [--retryable=true|false]
              forge run resolve-human --runs=DIR --slug=NAME --field=KEY=VALUE [--field=KEY=VALUE...] [--dry-run]

            OBSERVER NOTES
              watch emits progress snapshots only
              --summary is a lower-noise progress view
              auto emits full-fidelity final human/terminal payloads
            """;

    private RunCli() {
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
        RuntimeEngine engine = new RuntimeEngine();
        switch (command) {
            case "init" -> engine.init(parseInit(rest));
            case "auto" -> engine.auto(parseAuto(rest));
            case "next" -> engine.next(parseRunLocator(rest));
            case "status" -> engine.status(parseRunLocator(rest));
            case "show-progress" -> engine.showProgress(parseRunLocator(rest));
            case "recover" -> engine.recover(parseRunLocator(rest));
            case "show-human" -> engine.showHuman(parseRunLocator(rest));
            case "watch" -> engine.watch(parseWatch(rest));
            case "exec-dispatch" -> engine.execDispatch(parseExecDispatch(rest));
            case "complete-dispatch" -> engine.completeDispatch(parseCompleteDispatch(rest));
            case "fail-dispatch" -> engine.failDispatch(parseFailDispatch(rest));
            case "resolve-human" -> engine.resolveHuman(parseResolveHuman(rest));
            default -> throw new ForgeException("error: unknown run command: " + command + "\n\n" + HELP);
        }
    }

    private static RuntimeEngine.InitOptions parseInit(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        String spec = null;
        Map<String, Path> artifacts = new TreeMap<>();
        boolean force = false;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if (arg.startsWith("--spec=")) {
                spec = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--artifact=")) {
                CliSupport.KeyValue artifact = CliSupport.parseKeyValue(CliSupport.optionValue(arg), "artifact");
                artifacts.put(artifact.key(), Path.of(artifact.value()));
            } else if ("--force".equals(arg)) {
                force = true;
            } else {
                throw unknown(arg);
            }
        }
        require(spec, "--spec=PATH");
        RuntimeEngine.Locator runLocator = locator.require();
        return new RuntimeEngine.InitOptions(Path.of(spec), runLocator.runsDir(), runLocator.slug(), artifacts, force);
    }

    private static RuntimeEngine.AutoOptions parseAuto(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        String watchMode = null;
        boolean tee = false;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if ("--tee".equals(arg)) {
                tee = true;
                continue;
            }
            if ("--watch".equals(arg)) {
                watchMode = "pretty";
                continue;
            }
            if (arg.startsWith("--watch=")) {
                String mode = CliSupport.optionValue(arg);
                if (!"pretty".equals(mode) && !"jsonl".equals(mode) && !"summary".equals(mode)) {
                    throw new ForgeException("error: invalid --watch mode '"
                            + mode
                            + "', expected pretty, jsonl, or summary");
                }
                watchMode = mode;
            } else {
                throw unknown(arg);
            }
        }
        return new RuntimeEngine.AutoOptions(locator.require(), watchMode, tee);
    }

    private static RuntimeEngine.WatchOptions parseWatch(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        long intervalMs = 1000;
        boolean jsonl = false;
        boolean summary = false;
        boolean untilTerminal = false;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if ("--jsonl".equals(arg)) {
                jsonl = true;
                continue;
            }
            if ("--summary".equals(arg)) {
                summary = true;
                continue;
            }
            if ("--until-terminal".equals(arg)) {
                untilTerminal = true;
                continue;
            }
            if (arg.startsWith("--interval-ms=")) {
                parseUnsignedLong(arg, "interval-ms");
                intervalMs = Long.parseUnsignedLong(CliSupport.optionValue(arg));
            } else {
                throw unknown(arg);
            }
        }
        return new RuntimeEngine.WatchOptions(locator.require(), intervalMs, jsonl, summary, untilTerminal);
    }

    private static RuntimeEngine.ExecOptions parseExecDispatch(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        boolean tee = false;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if ("--tee".equals(arg)) {
                tee = true;
            } else {
                throw unknown(arg);
            }
        }
        return new RuntimeEngine.ExecOptions(locator.require(), tee);
    }

    private static RuntimeEngine.DispatchCompletionOptions parseCompleteDispatch(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        String dispatchId = null;
        Map<String, String> decisions = new TreeMap<>();
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if (arg.startsWith("--dispatch-id=")) {
                dispatchId = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--decision=")) {
                CliSupport.KeyValue decision = CliSupport.parseKeyValue(CliSupport.optionValue(arg), "decision");
                decisions.put(decision.key(), decision.value());
            } else {
                throw unknown(arg);
            }
        }
        RuntimeEngine.Locator runLocator = locator.require();
        require(dispatchId, "--dispatch-id=ID");
        return new RuntimeEngine.DispatchCompletionOptions(runLocator, dispatchId, decisions);
    }

    private static RuntimeEngine.DispatchFailureOptions parseFailDispatch(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        String dispatchId = null;
        String reason = null;
        boolean retryable = true;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if (arg.startsWith("--dispatch-id=")) {
                dispatchId = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--reason=")) {
                reason = CliSupport.optionValue(arg);
            } else if (arg.startsWith("--retryable=")) {
                retryable = CliSupport.parseBooleanFlag(CliSupport.optionValue(arg), "retryable");
            } else {
                throw unknown(arg);
            }
        }
        RuntimeEngine.Locator runLocator = locator.require();
        require(dispatchId, "--dispatch-id=ID");
        require(reason, "--reason=TEXT");
        return new RuntimeEngine.DispatchFailureOptions(runLocator, dispatchId, reason, retryable);
    }

    private static RuntimeEngine.ResolveHumanOptions parseResolveHuman(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        Map<String, String> fields = new TreeMap<>();
        boolean dryRun = false;
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            if ("--dry-run".equals(arg)) {
                dryRun = true;
                continue;
            }
            if (arg.startsWith("--field=")) {
                CliSupport.KeyValue field = CliSupport.parseKeyValue(CliSupport.optionValue(arg), "field");
                fields.put(field.key(), field.value());
            } else {
                throw unknown(arg);
            }
        }
        RuntimeEngine.Locator runLocator = locator.require();
        if (fields.isEmpty()) {
            throw CliSupport.missingOption("--field=KEY=VALUE");
        }
        return new RuntimeEngine.ResolveHumanOptions(runLocator, fields, dryRun);
    }

    private static RuntimeEngine.Locator parseRunLocator(List<String> args) {
        LocatorBuilder locator = new LocatorBuilder();
        for (String arg : args) {
            if (parseLocatorOption(arg, locator)) {
                continue;
            }
            throw unknown(arg);
        }
        return locator.require();
    }

    private static boolean parseLocatorOption(String arg, LocatorBuilder locator) {
        if (arg.startsWith("--runs=")) {
            locator.runs = CliSupport.optionValue(arg);
        } else if (arg.startsWith("--slug=")) {
            locator.slug = RunSlug.parse(CliSupport.optionValue(arg));
        } else {
            return false;
        }
        return true;
    }

    private static void require(String value, String option) {
        CliSupport.requireValue(value, option);
    }

    private static void parseUnsignedLong(String arg, String label) {
        try {
            Long.parseUnsignedLong(CliSupport.optionValue(arg));
        } catch (NumberFormatException error) {
            throw new ForgeException("error: invalid " + label, error);
        }
    }

    private static ForgeException unknown(String arg) {
        return CliSupport.unknownOption(arg);
    }

    private static final class LocatorBuilder {
        private String runs;
        private RunSlug slug;

        private RuntimeEngine.Locator require() {
            CliSupport.requireValue(runs, "--runs=DIR");
            if (slug == null) {
                throw CliSupport.missingOption("--slug=NAME");
            }
            return new RuntimeEngine.Locator(Path.of(runs), slug);
        }
    }
}
