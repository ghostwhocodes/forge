package dev.llaith.forge.template;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.llaith.forge.spec.WorkflowSpec;

import java.nio.file.Path;
import java.util.List;

public record TemplateBundle(
        TemplateManifest manifest,
        WorkflowSpec workflow,
        @JsonProperty("root_dir") Path rootDir,
        @JsonProperty("workflow_path") Path workflowPath,
        @JsonProperty("referenced_files") List<ReferencedFile> referencedFiles
) {
    public TemplateBundle {
        referencedFiles = referencedFiles == null ? List.of() : List.copyOf(referencedFiles);
    }
}
