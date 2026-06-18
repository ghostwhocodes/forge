package dev.llaith.forge.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.util.TimeSupport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

public final class AdvisoryLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private AdvisoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static AdvisoryLock acquire(Path path, String purpose) {
        return openAndLock(path, purpose, true)
                .orElseThrow(() -> new ForgeException("blocking advisory lock acquisition returned no lock"));
    }

    public static Optional<AdvisoryLock> tryAcquire(Path path, String purpose) {
        return openAndLock(path, purpose, false);
    }

    private static Optional<AdvisoryLock> openAndLock(Path path, String purpose, boolean blocking) {
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException error) {
                throw new ForgeException("failed to create lock directory " + parent, error);
            }
        }
        try {
            FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            FileLock lock = blocking ? channel.lock() : tryLock(channel);
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            writeMetadata(channel, path, purpose);
            return Optional.of(new AdvisoryLock(channel, lock));
        } catch (IOException error) {
            throw new ForgeException("failed to lock " + path, error);
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException error) {
            return null;
        }
    }

    private static void writeMetadata(FileChannel channel, Path path, String purpose) {
        LockMetadata metadata = new LockMetadata(
                ProcessHandle.current().pid(),
                TimeSupport.now(Clock.system(ZoneId.systemDefault())),
                purpose,
                System.getProperty("sun.java.command", ""));
        byte[] bytes = Json.toPrettyJson(metadata).getBytes(StandardCharsets.UTF_8);
        try {
            channel.truncate(0);
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException error) {
            throw new ForgeException("failed to write lock metadata " + path, error);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException error) {
            throw new ForgeException("failed to release advisory lock", error);
        }
    }

    private record LockMetadata(
            long pid,
            @JsonProperty("acquired_at") String acquiredAt,
            String purpose,
            String command
    ) {
    }
}
