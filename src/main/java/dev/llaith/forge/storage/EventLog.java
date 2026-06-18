package dev.llaith.forge.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public final class EventLog {
    private static final ObjectMapper MAPPER = Json.mapper();

    private EventLog() {
    }

    public static void appendEvent(Path path, EventEnvelope event) {
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException error) {
                throw new ForgeException("failed to create event log directory " + parent, error);
            }
        }
        byte[] line = (Json.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException error) {
            throw new ForgeException("failed to append event to " + path, error);
        }
    }

    public static List<EventEnvelope> readEvents(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<EventEnvelope> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                events.add(MAPPER.readValue(line, EventEnvelope.class));
            }
        } catch (IOException error) {
            throw new ForgeException("failed to parse event line in " + path, error);
        }
        return List.copyOf(events);
    }

    public static OptionalLong lastEventSeq(Path path) {
        if (!Files.exists(path)) {
            return OptionalLong.empty();
        }
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long position = channel.size();
            if (position == 0L) {
                return OptionalLong.empty();
            }
            ByteBuffer oneByte = ByteBuffer.allocate(1);
            ByteArrayOutputStream reversedLine = new ByteArrayOutputStream();
            while (position > 0L) {
                position--;
                oneByte.clear();
                channel.position(position);
                if (channel.read(oneByte) <= 0) {
                    continue;
                }
                oneByte.flip();
                int value = oneByte.get() & 0xff;
                if (value == '\n') {
                    OptionalLong seq = parseReversedLastEventSeq(path, reversedLine);
                    if (seq.isPresent()) {
                        return seq;
                    }
                    reversedLine.reset();
                    continue;
                }
                if (value != '\r') {
                    reversedLine.write(value);
                }
            }
            return parseReversedLastEventSeq(path, reversedLine);
        } catch (IOException error) {
            throw new ForgeException("failed to read last event from " + path, error);
        }
    }

    private static OptionalLong parseReversedLastEventSeq(Path path, ByteArrayOutputStream reversedLine) {
        byte[] reversed = reversedLine.toByteArray();
        if (reversed.length == 0) {
            return OptionalLong.empty();
        }
        for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
            byte tmp = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = tmp;
        }
        String line = new String(reversed, StandardCharsets.UTF_8);
        if (line.trim().isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(MAPPER.readValue(line, EventEnvelope.class).seq());
        } catch (IOException error) {
            throw new ForgeException("failed to parse last event line in " + path, error);
        }
    }
}
