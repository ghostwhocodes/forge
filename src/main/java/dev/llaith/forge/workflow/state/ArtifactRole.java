package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.llaith.forge.ForgeException;

import java.util.Locale;

public enum ArtifactRole {
    STANDARD("standard"),
    REQUEST("request");

    private final String jsonValue;

    ArtifactRole(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonCreator
    public static ArtifactRole fromJson(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (ArtifactRole role : values()) {
            if (role.jsonValue.equals(normalized)) {
                return role;
            }
        }
        throw new ForgeException("unknown artifact role: " + value);
    }

    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }
}
