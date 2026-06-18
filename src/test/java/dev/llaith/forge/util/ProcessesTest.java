package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ProcessesTest {
    @TempDir
    private Path tempDir;

    @Test
    void runCapturesStdoutStderrExitCodeAndEnvironment() {
        ProcessResult result = Processes.run(
                List.of("sh", "-c", "printf \"$FORGE_TEST_ENV\"; printf err >&2"),
                null,
                Map.of("FORGE_TEST_ENV", "out"),
                new byte[0],
                Duration.ofSeconds(5)
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(new String(result.stdout(), StandardCharsets.UTF_8)).isEqualTo("out");
        assertThat(new String(result.stderr(), StandardCharsets.UTF_8)).isEqualTo("err");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.pid()).isNotNull();
    }

    @Test
    void runReportsNonZeroExitWithoutThrowing() {
        ProcessResult result = Processes.run(
                List.of("sh", "-c", "exit 7"),
                null,
                Map.of(),
                new byte[0],
                Duration.ofSeconds(5)
        );

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void processResultConvenienceConstructorDerivesPayloadSizes() {
        ProcessResult result = new ProcessResult(0, "out".getBytes(StandardCharsets.UTF_8),
                "err".getBytes(StandardCharsets.UTF_8), false);

        assertThat(result.stdoutBytes()).isEqualTo(3);
        assertThat(result.stderrBytes()).isEqualTo(3);
        assertThat(result.pid()).isNull();
    }

    @Test
    void runHonorsWorkingDirectoryAndStdin() throws Exception {
        ProcessResult result = Processes.run(
                List.of("sh", "-c", "pwd -P; printf ':'; cat"),
                tempDir,
                Map.of(),
                "stdin".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(5)
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(new String(result.stdout(), StandardCharsets.UTF_8))
                .isEqualTo(tempDir.toRealPath() + System.lineSeparator() + ":stdin");
    }

    @Test
    void runToFilesStreamsStdoutAndStderrWithoutBufferingResultPayloads() throws Exception {
        Path stdout = tempDir.resolve("process.stdout");
        Path stderr = tempDir.resolve("process.stderr");

        ProcessResult result = Processes.runToFiles(
                List.of("sh", "-c", "printf stdout; printf stderr >&2"),
                null,
                Map.of(),
                new byte[0],
                Duration.ofSeconds(5),
                stdout,
                stderr,
                () -> {
                }
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdoutBytes()).isEqualTo(6);
        assertThat(result.stderrBytes()).isEqualTo(6);
        assertThat(Files.readString(stdout)).isEqualTo("stdout");
        assertThat(Files.readString(stderr)).isEqualTo("stderr");
    }

    @Test
    void runReportsTimeoutsAndDestroysProcess() {
        ProcessResult result = Processes.run(
                List.of("sh", "-c", "sleep 5"),
                null,
                Map.of(),
                new byte[0],
                Duration.ofMillis(20)
        );

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void runWrapsLaunchFailures() {
        assertThatThrownBy(() -> Processes.run(
                List.of("forge-command-that-does-not-exist"),
                null,
                Map.of(),
                new byte[0],
                Duration.ofSeconds(1)
        ))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to launch process");
    }

    @Test
    void runRejectsEmptyCommand() {
        assertThatThrownBy(() -> Processes.run(List.of(), null, Map.of(), new byte[0], Duration.ofSeconds(1)))
                .isInstanceOf(ForgeException.class)
                .hasMessage("error: process command must not be empty");
    }
}
