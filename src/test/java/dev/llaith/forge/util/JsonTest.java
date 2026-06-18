package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JsonTest {
    @TempDir
    private Path tempDir;

    @Test
    void prettyJsonUsesStableMapOrderingAndTrailingNewline() {
        String json = Json.toPrettyJson(Map.of("b", 2, "a", 1));

        assertThat(json).isEqualTo("{\n  \"a\" : 1,\n  \"b\" : 2\n}\n");
    }

    @Test
    void jsonRoundTripsRecordsAndRejectsUnknownProperties() {
        Sample sample = Json.fromJson("{\"name\":\"forge\",\"count\":2}", Sample.class);

        assertThat(sample).isEqualTo(new Sample("forge", 2));
        assertThat(Json.toJson(sample)).contains("\"name\":\"forge\"");
        assertThatThrownBy(() -> Json.fromJson("{\"name\":\"forge\",\"count\":2,\"extra\":true}", Sample.class))
                .isInstanceOf(ForgeException.class)
                .hasMessage("failed to parse JSON");
    }

    @Test
    void jsonFileHelpersRoundTripPrettyPayloads() {
        Path path = tempDir.resolve("payload.json");

        Json.writePretty(path, new Sample("forge", 3));

        assertThat(Filesystem.readUtf8(path)).endsWith("\n");
        assertThat(Json.read(path, Sample.class)).isEqualTo(new Sample("forge", 3));
    }

    @Test
    void jsonFileHelpersWrapReadFailures() {
        Path missing = tempDir.resolve("missing.json");

        assertThatThrownBy(() -> Json.read(missing, Sample.class))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("failed to read JSON from " + missing);
    }

    @Test
    void jsonSerializationWrapsJacksonFailures() {
        SelfReferential value = new SelfReferential();

        assertThatThrownBy(() -> Json.toJson(value))
                .isInstanceOf(ForgeException.class)
                .hasMessage("failed to serialize JSON");
        assertThatThrownBy(() -> Json.toPrettyJson(value))
                .isInstanceOf(ForgeException.class)
                .hasMessage("failed to serialize JSON");
    }

    @Test
    void mapperUsesIsoInstantsInsteadOfTimestamps() throws Exception {
        String json = Json.mapper().writeValueAsString(Map.of("at", Instant.parse("2026-05-23T01:02:03Z")));

        assertThat(json).contains("2026-05-23T01:02:03Z");
    }

    private record Sample(String name, int count) {
    }

    private static final class SelfReferential {
        @SuppressWarnings({"UnusedMethod", "EffectivelyPrivate"})
        public SelfReferential getSelf() {
            return this;
        }
    }
}
