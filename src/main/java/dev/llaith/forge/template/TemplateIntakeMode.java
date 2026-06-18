package dev.llaith.forge.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.llaith.forge.ForgeException;

public enum TemplateIntakeMode {
    SIMPLE("simple"),
    SPEC("spec"),
    ISSUE("issue"),
    REVIEW("review"),
    REVIEW_FIX("review_fix");

    private final String jsonName;

    TemplateIntakeMode(String jsonName) {
        this.jsonName = jsonName;
    }

    @JsonCreator
    public static TemplateIntakeMode fromJson(String value) {
        for (TemplateIntakeMode mode : values()) {
            if (mode.jsonName.equals(value)) {
                return mode;
            }
        }
        throw new ForgeException("error: unknown template intake mode '" + value + "'");
    }

    @JsonValue
    public String jsonName() {
        return jsonName;
    }
}
