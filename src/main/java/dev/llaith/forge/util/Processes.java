package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class Processes {
    private Processes() {
    }

    public static ProcessResult run(
            List<String> command,
            @Nullable Path cwd,
            Map<String, String> environment,
            byte[] stdin,
            Duration timeout
    ) {
        return run(command, cwd, environment, stdin, timeout, () -> {
        });
    }

    public static ProcessResult run(
            List<String> command,
            @Nullable Path cwd,
            Map<String, String> environment,
            byte[] stdin,
            Duration timeout,
            ProgressObserver observer
    ) {
        if (command.isEmpty()) {
            throw new ForgeException("error: process command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.environment().putAll(environment);

        try {
            observer.starting();
            Process process = builder.start();
            boolean finished = false;
            try {
                observer.started(process.pid());
                CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                        () -> readAll(process.getInputStream(), "stdout")
                );
                CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                        () -> readAll(process.getErrorStream(), "stderr")
                );
                try (OutputStream processStdin = process.getOutputStream()) {
                    processStdin.write(stdin);
                }
                boolean completed = waitForProcess(process, timeout, observer);
                if (!completed) {
                    process.destroyForcibly();
                    process.waitFor();
                    finished = true;
                    return new ProcessResult(-1, stdout.get(), stderr.get(), true, process.pid());
                }
                finished = true;
                return new ProcessResult(process.exitValue(), stdout.get(), stderr.get(), false, process.pid());
            } finally {
                if (!finished && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (IOException error) {
            throw new ForgeException("failed to launch process " + command, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ForgeException("interrupted while running process " + command, error);
        } catch (ExecutionException error) {
            throw new ForgeException("failed to capture process output " + command, error);
        }
    }

    public static ProcessResult runToFiles(
            List<String> command,
            @Nullable Path cwd,
            Map<String, String> environment,
            byte[] stdin,
            Duration timeout,
            Path stdoutPath,
            Path stderrPath,
            ProgressObserver observer
    ) {
        if (command.isEmpty()) {
            throw new ForgeException("error: process command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.environment().putAll(environment);

        try {
            ensureParent(stdoutPath);
            ensureParent(stderrPath);
            observer.starting();
            Process process = builder.start();
            boolean finished = false;
            try {
                observer.started(process.pid());
                CompletableFuture<Long> stdout = CompletableFuture.supplyAsync(
                        () -> streamToFile(process.getInputStream(), stdoutPath, "stdout")
                );
                CompletableFuture<Long> stderr = CompletableFuture.supplyAsync(
                        () -> streamToFile(process.getErrorStream(), stderrPath, "stderr")
                );
                try (OutputStream processStdin = process.getOutputStream()) {
                    processStdin.write(stdin);
                }
                boolean completed = waitForProcess(process, timeout, observer);
                if (!completed) {
                    process.destroyForcibly();
                    process.waitFor();
                    finished = true;
                    return new ProcessResult(
                            -1,
                            new byte[0],
                            new byte[0],
                            stdout.get(),
                            stderr.get(),
                            true,
                            process.pid());
                }
                finished = true;
                return new ProcessResult(
                        process.exitValue(),
                        new byte[0],
                        new byte[0],
                        stdout.get(),
                        stderr.get(),
                        false,
                        process.pid());
            } finally {
                if (!finished && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (IOException error) {
            throw new ForgeException("failed to launch process " + command, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ForgeException("interrupted while running process " + command, error);
        } catch (ExecutionException error) {
            throw new ForgeException("failed to capture process output " + command, error);
        }
    }

    private static boolean waitForProcess(Process process, Duration timeout, ProgressObserver observer)
            throws InterruptedException {
        long remainingMillis = Math.max(0L, timeout.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
        while (remainingMillis > 0L) {
            boolean completed = process.waitFor(Math.min(remainingMillis, 100L), TimeUnit.MILLISECONDS);
            if (completed) {
                return true;
            }
            observer.observe();
            remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        }
        return process.waitFor(0L, TimeUnit.MILLISECONDS);
    }

    private static byte[] readAll(java.io.InputStream stream, String label) {
        try {
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new ForgeException("failed to read process " + label, error);
        }
    }

    private static long streamToFile(InputStream stream, Path path, String label) {
        try (InputStream input = stream;
             OutputStream output = Files.newOutputStream(
                     path,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            return total;
        } catch (IOException error) {
            throw new ForgeException("failed to stream process " + label + " to " + path, error);
        }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @FunctionalInterface
    public interface ProgressObserver {
        default void starting() {
        }

        default void started(long pid) {
        }

        void observe();
    }
}
