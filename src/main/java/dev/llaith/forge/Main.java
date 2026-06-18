package dev.llaith.forge;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(exitCode(args));
    }

    public static int exitCode(String[] args) {
        return ForgeCli.execute(args);
    }
}
