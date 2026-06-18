package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record ArtifactMetadata(
        @JsonProperty("blob_id") String blobId,
        ArtifactBinding binding,
        ArtifactRole role
) {
    @JsonCreator
    public ArtifactMetadata(
            @JsonProperty("blob_id") String blobId,
            @JsonProperty("binding") @Nullable ArtifactBinding binding,
            @JsonProperty("role") @Nullable ArtifactRole role) {
        this.blobId = blobId;
        this.binding = binding == null ? ArtifactBinding.local() : binding;
        this.role = role == null ? ArtifactRole.STANDARD : role;
    }
}
