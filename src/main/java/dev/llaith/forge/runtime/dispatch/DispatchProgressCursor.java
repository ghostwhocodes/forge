package dev.llaith.forge.runtime.dispatch;

import dev.llaith.forge.ForgeException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.IntConsumer;

public final class DispatchProgressCursor {
    private final ProgressFileSource source;
    private long bytes;
    private long completedEntries;
    private boolean currentLineHasContent;

    public DispatchProgressCursor(Path path) {
        this(new PathProgressFileSource(path));
    }

    public DispatchProgressCursor(ProgressFileSource source) {
        this.source = source;
    }

    public ProgressStats refresh() {
        try {
            if (!source.exists()) {
                reset();
                return stats();
            }
            long size = source.size();
            if (size < bytes) {
                reset();
            }
            if (size > bytes) {
                long read = source.readFrom(bytes, this::acceptByte);
                bytes += read;
            }
            return stats();
        } catch (IOException error) {
            throw new ForgeException("failed to inspect dispatch progress file " + source.description(), error);
        }
    }

    private void reset() {
        bytes = 0;
        completedEntries = 0;
        currentLineHasContent = false;
    }

    private void acceptByte(int value) {
        int b = value & 0xff;
        if (b == '\n') {
            if (currentLineHasContent) {
                completedEntries++;
            }
            currentLineHasContent = false;
            return;
        }
        if (!isLineWhitespace(b)) {
            currentLineHasContent = true;
        }
    }

    private ProgressStats stats() {
        long entries = completedEntries + (currentLineHasContent ? 1 : 0);
        return new ProgressStats(bytes, entries);
    }

    private static boolean isLineWhitespace(int b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\f';
    }

    public record ProgressStats(long bytes, long entries) {
        public boolean hasProgress() {
            return bytes > 0 || entries > 0;
        }
    }

    public interface ProgressFileSource {
        boolean exists() throws IOException;

        long size() throws IOException;

        long readFrom(long offset, IntConsumer consumer) throws IOException;

        String description();
    }

    private static final class PathProgressFileSource implements ProgressFileSource {
        private final Path path;

        private PathProgressFileSource(Path path) {
            this.path = path;
        }

        @Override
        public boolean exists() {
            return Files.exists(path);
        }

        @Override
        public long size() throws IOException {
            return Files.size(path);
        }

        @Override
        public long readFrom(long offset, IntConsumer consumer) throws IOException {
            long total = 0;
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(offset);
                ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                int read;
                while ((read = channel.read(buffer)) > 0) {
                    total += read;
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        consumer.accept(buffer.get() & 0xff);
                    }
                    buffer.clear();
                }
            }
            return total;
        }

        @Override
        public String description() {
            return path.toString();
        }
    }
}
