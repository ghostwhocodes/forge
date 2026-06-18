package dev.llaith.forge;

import dev.llaith.forge.cli.CliSupport;
import dev.llaith.forge.intake.IntakeCli;
import dev.llaith.forge.runtime.run.RunCli;
import dev.llaith.forge.spec.SpecCli;
import dev.llaith.forge.template.ScaffoldCli;
import dev.llaith.forge.template.TemplateCli;

import java.util.Arrays;
import java.util.List;

public final class ForgeCli {
    public static final String VERSION = "forge 0.1.0";
    public static final String HELP = """
            forge — Command namespace for Forge

            USAGE
              forge <command> [args...]

            COMMANDS
              intake    Normalize user-facing requests into request artifacts
              run       Run lifecycle commands
              scaffold  Scaffold repo-local workflows and hooks
              spec      Spec preparation commands
              template  List, inspect, validate, analyze, and propose template updates
              version   Print the Forge version
            """;

    private ForgeCli() {
    }

    public static int execute(String[] args) {
        try {
            run(Arrays.asList(args));
            return 0;
        } catch (ForgeException error) {
            if (!error.getMessage().isBlank()) {
                System.err.println(error.getMessage());
            }
            return error.exitCode();
        } catch (RuntimeException error) {
            String message = error.getMessage();
            System.err.println(message == null || message.isBlank() ? error.toString() : message);
            return 1;
        }
    }

    static void run(List<String> args) {
        if (args.isEmpty() || CliSupport.isHelpFlag(args.getFirst())) {
            System.out.print(HELP);
            return;
        }

        String command = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        switch (command) {
            case "intake" -> IntakeCli.run(rest);
            case "run" -> RunCli.run(rest);
            case "scaffold" -> ScaffoldCli.run(rest);
            case "spec" -> SpecCli.run(rest);
            case "template" -> TemplateCli.run(rest);
            case "version", "-V", "--version" -> System.out.println(VERSION);
            default -> throw new ForgeException("error: unknown command: " + command + "\n\n" + HELP);
        }
    }
}
