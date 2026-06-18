package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class FilesystemTest {
    @TempDir
    private Path tempDir;

    @Test
    void writesReadsAndCreatesParentDirectories() {
        Path path = tempDir.resolve("nested").resolve("value.txt");

        Filesystem.writeUtf8(path, "hello\n");

        assertThat(Filesystem.readUtf8(path)).isEqualTo("hello\n");
        assertThat(Files.isDirectory(path.getParent())).isTrue();
    }

    @Test
    void readUtf8WrapsIoFailures() {
        Path missing = tempDir.resolve("missing.txt");

        assertThatThrownBy(() -> Filesystem.readUtf8(missing))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to read " + missing);
    }

    @Test
    void writeUtf8WrapsIoFailures() {
        assertThatThrownBy(() -> Filesystem.writeUtf8(tempDir, "content"))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to write " + tempDir);
    }

    @Test
    void ensureDirectoryWrapsCreateFailures() throws Exception {
        Path file = tempDir.resolve("file");
        Files.writeString(file, "not a directory");

        Path blockedChild = file.resolve("child");
        assertThatThrownBy(() -> Filesystem.ensureDirectory(blockedChild))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to create directory " + blockedChild);
    }

    @Test
    void requireExistingDirectoryReturnsCanonicalDirectoryAndRejectsFiles() throws Exception {
        Path directory = tempDir.resolve("dir");
        Filesystem.ensureDirectory(directory);
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "file");

        assertThat(Filesystem.requireExistingDirectory(directory, "repo root")).isEqualTo(directory.toRealPath());
        assertThatThrownBy(() -> Filesystem.requireExistingDirectory(file, "repo root"))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("error: repo root is not a directory");
    }

    @Test
    void requireExistingDirectoryWrapsResolutionFailures() {
        Path missing = tempDir.resolve("missing");

        assertThatThrownBy(() -> Filesystem.requireExistingDirectory(missing, "repo root"))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to resolve repo root at " + missing);
    }
}
