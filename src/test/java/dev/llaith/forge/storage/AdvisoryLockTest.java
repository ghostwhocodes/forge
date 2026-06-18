package dev.llaith.forge.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class AdvisoryLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void staleLockFileWithoutHeldLockIsReusableAndMetadataIsRewritten() throws Exception {
        Path path = tempDir.resolve("stale.lock");
        Files.writeString(path, "stale metadata\n");

        try (AdvisoryLock ignored = AdvisoryLock.tryAcquire(path, "test lock").orElseThrow()) {
            String metadata = Files.readString(path);
            assertThat(metadata).contains("\"purpose\" : \"test lock\"");
            assertThat(metadata).contains("\"pid\" :");
            assertThat(metadata).contains("\"acquired_at\" :");
            assertThat(metadata).contains("\"command\" :");
        }
    }

    @Test
    void tryLockReportsContentionWithoutRemovingLockFile() throws Exception {
        Path path = tempDir.resolve("held.lock");

        try (AdvisoryLock first = AdvisoryLock.acquire(path, "first")) {
            assertThat(AdvisoryLock.tryAcquire(path, "second")).isEmpty();
            assertThat(Files.exists(path)).isTrue();
            assertThat(Files.readString(path)).contains("\"purpose\" : \"first\"");
        }

        try (AdvisoryLock third = AdvisoryLock.tryAcquire(path, "third").orElseThrow()) {
            assertThat(Files.readString(path)).contains("\"purpose\" : \"third\"");
        }
    }
}
