package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HashingTest {
    @TempDir
    private Path tempDir;

    @Test
    void sha256HexMatchesRustBlobIdShapeForBytesAndFiles() throws Exception {
        Path path = tempDir.resolve("artifact.txt");
        Files.writeString(path, "hello\n");

        String expected = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03";
        assertThat(Hashing.sha256Hex("hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(expected);
        assertThat(Hashing.sha256Hex(path)).isEqualTo(expected);
    }

    @Test
    void sha256HexWrapsFileReadFailures() {
        Path missing = tempDir.resolve("missing.bin");

        assertThatThrownBy(() -> Hashing.sha256Hex(missing))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to read bytes for hashing from " + missing);
    }
}
