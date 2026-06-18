package dev.llaith.forge.workflow.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.util.Json;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

public final class EventEnvelope {
    private final long seq;
    private final String timestamp;
    private final String type;
    private final Map<String, JsonNode> fields;

    @JsonCreator
    public EventEnvelope(
            @JsonProperty(value = "seq", required = true) long seq,
            @JsonProperty(value = "timestamp", required = true) String timestamp,
            @JsonProperty(value = "type", required = true) String type) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.type = type;
        this.fields = new TreeMap<>();
    }

    private EventEnvelope(long seq, String timestamp, String type, Map<String, JsonNode> fields) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.type = type;
        this.fields = new TreeMap<>(fields);
    }

    public static EventEnvelope of(long seq, String timestamp, String type, Map<String, ?> fields) {
        Map<String, JsonNode> nodes = new TreeMap<>();
        fields.forEach((key, value) -> nodes.put(key, Json.mapper().valueToTree(value)));
        return new EventEnvelope(seq, timestamp, type, nodes);
    }

    @JsonProperty("seq")
    public long seq() {
        return seq;
    }

    @JsonProperty("timestamp")
    public String timestamp() {
        return timestamp;
    }

    @JsonProperty("type")
    public String type() {
        return type;
    }

    @JsonAnyGetter
    public Map<String, JsonNode> fields() {
        return Map.copyOf(fields);
    }

    @JsonAnySetter
    public void put(String name, @Nullable JsonNode value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    public @Nullable JsonNode field(String name) {
        return fields.get(name);
    }

    public @Nullable String textField(String name) {
        JsonNode value = fields.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}
