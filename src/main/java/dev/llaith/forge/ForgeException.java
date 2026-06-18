package dev.llaith.forge;

public final class ForgeException extends RuntimeException {
    private final int exitCode;

    public ForgeException(String message) {
        this(message, 1);
    }

    public ForgeException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public ForgeException(String message, Throwable cause) {
        this(message, cause, 1);
    }

    public ForgeException(String message, Throwable cause, int exitCode) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
