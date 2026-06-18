package dev.llaith.forge.template;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReferencedFile(
        String kind,
        @JsonProperty("relative_path") String relativePath,
        String path
) {
}
