package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record ArtifactBinding(
        String kind,
        @JsonProperty("child_run_dir") @Nullable String childRunDir,
        @JsonProperty("child_artifact") @Nullable String childArtifact
) {
    @JsonCreator
    public ArtifactBinding(
            @JsonProperty("kind") @Nullable String kind,
            @JsonProperty("child_run_dir") @Nullable String childRunDir,
            @JsonProperty("child_artifact") @Nullable String childArtifact) {
        this.kind = kind == null ? "local" : kind;
        this.childRunDir = childRunDir;
        this.childArtifact = childArtifact;
    }

    public static ArtifactBinding local() {
        return new ArtifactBinding("local", null, null);
    }

    public static ArtifactBinding importedChild(String childRunDir, String childArtifact) {
        return new ArtifactBinding("imported_child", childRunDir, childArtifact);
    }

    @JsonIgnore
    public boolean isImportedChild() {
        return "imported_child".equals(kind);
    }
}
