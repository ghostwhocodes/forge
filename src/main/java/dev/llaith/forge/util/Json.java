package dev.llaith.forge.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.llaith.forge.ForgeException;

import java.io.IOException;
import java.nio.file.Path;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ForgeException("failed to serialize JSON", error);
        }
    }

    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (JsonProcessingException error) {
            throw new ForgeException("failed to serialize JSON", error);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException error) {
            throw new ForgeException("failed to parse JSON", error);
        }
    }

    public static <T> T read(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(path.toFile(), type);
        } catch (IOException error) {
            throw new ForgeException("failed to read JSON from " + path, error);
        }
    }

    public static void writePretty(Path path, Object value) {
        Filesystem.writeUtf8(path, toPrettyJson(value));
    }
}
