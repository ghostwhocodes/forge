package dev.llaith.forge.template;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TemplateManifest(
        String id,
        int version,
        @JsonProperty("display_name") String displayName,
        String description,
        String workflow,
        @JsonProperty("supports_intake") List<TemplateIntakeMode> supportsIntake,
        List<String> tags,
        @JsonProperty("recommended_hooks") List<String> recommendedHooks,
        @JsonProperty("recommended_outputs") RecommendedOutputs recommendedOutputs,
        @JsonProperty("prompt_files") List<String> promptFiles,
        @JsonProperty("hook_files") List<String> hookFiles,
        @JsonProperty("script_files") List<String> scriptFiles
) {
    public TemplateManifest {
        supportsIntake = supportsIntake == null ? List.of() : List.copyOf(supportsIntake);
        tags = tags == null ? List.of() : List.copyOf(tags);
        recommendedHooks = recommendedHooks == null ? List.of() : List.copyOf(recommendedHooks);
        promptFiles = promptFiles == null ? List.of() : List.copyOf(promptFiles);
        hookFiles = hookFiles == null ? List.of() : List.copyOf(hookFiles);
        scriptFiles = scriptFiles == null ? List.of() : List.copyOf(scriptFiles);
    }

    public List<TemplateIntakeMode> intakeModes() {
        return supportsIntake;
    }

    public List<String> tagList() {
        return tags;
    }

    public List<String> promptFileList() {
        return promptFiles;
    }

    public List<String> hookFileList() {
        return hookFiles;
    }

    public List<String> scriptFileList() {
        return scriptFiles;
    }

    public record RecommendedOutputs(@JsonProperty("scaffold_dir") String scaffoldDir) {
    }
}
