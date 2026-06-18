package dev.llaith.forge.util;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class ProcessResult {
    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;
    private final long stdoutBytes;
    private final long stderrBytes;
    private final boolean timedOut;
    private final @Nullable Long pid;

    public ProcessResult(int exitCode, byte[] stdout, byte[] stderr, boolean timedOut) {
        this(exitCode, stdout, stderr, timedOut, null);
    }

    public ProcessResult(int exitCode, byte[] stdout, byte[] stderr, boolean timedOut, @Nullable Long pid) {
        this(exitCode, stdout, stderr, stdout.length, stderr.length, timedOut, pid);
    }

    public ProcessResult(
            int exitCode,
            byte[] stdout,
            byte[] stderr,
            long stdoutBytes,
            long stderrBytes,
            boolean timedOut,
            @Nullable Long pid) {
        this.exitCode = exitCode;
        this.stdout = Arrays.copyOf(stdout, stdout.length);
        this.stderr = Arrays.copyOf(stderr, stderr.length);
        this.stdoutBytes = stdoutBytes;
        this.stderrBytes = stderrBytes;
        this.timedOut = timedOut;
        this.pid = pid;
    }

    public int exitCode() {
        return exitCode;
    }

    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public long stdoutBytes() {
        return stdoutBytes;
    }

    public long stderrBytes() {
        return stderrBytes;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public @Nullable Long pid() {
        return pid;
    }

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
