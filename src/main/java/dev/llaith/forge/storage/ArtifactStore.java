package dev.llaith.forge.storage;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Hashing;
import dev.llaith.forge.util.PathSupport;
import dev.llaith.forge.workflow.state.ArtifactBinding;
import dev.llaith.forge.workflow.state.ArtifactMetadata;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ArtifactStore {
    private ArtifactStore() {
    }

    public static String blobIdForBytes(byte[] bytes) {
        return Hashing.sha256Hex(bytes);
    }

    public static String blobIdForText(String text) {
        return blobIdForBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String blobIdForFile(Path path) {
        return Hashing.sha256Hex(path);
    }

    public static Path resolveArtifactPath(Path runDir, DerivedRunState state, ArtifactRecord artifact) {
        ArtifactMetadata metadata = state.artifactMetadata().get(artifact.name());
        if (metadata != null && metadata.binding().isImportedChild()) {
            ArtifactBinding binding = metadata.binding();
            String childRunDir = binding.childRunDir();
            String childArtifact = binding.childArtifact();
            if (childRunDir == null || childArtifact == null) {
                throw new ForgeException("imported child artifact binding is incomplete for '" + artifact.name() + "'");
            }
            Path childRun = PathSupport.resolveRecordedArtifactPath(runDir, childRunDir);
            DerivedRunState childState = WorkflowReducer.deriveState(EventLog.readEvents(RunPaths.eventsPath(childRun)));
            ArtifactRecord childRecord = childState.artifactIndex().get(childArtifact);
            if (childRecord == null) {
                throw new ForgeException(
                        "child run '" + childRun + "' is missing imported artifact '" + childArtifact + "'");
            }
            return PathSupport.resolveRecordedArtifactPath(childRun, childRecord.path());
        }
        return PathSupport.resolveRecordedArtifactPath(runDir, artifact.path());
    }

    public static String readArtifactText(Path runDir, DerivedRunState state, ArtifactRecord artifact) {
        Path path = resolveArtifactPath(runDir, state, artifact);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new ForgeException("failed to read artifact '" + artifact.name() + "' at " + path, error);
        }
    }

    public static Optional<ArtifactRecord> optionalRequestArtifact(DerivedRunState state) {
        var requestArtifacts = state.artifactMetadata().entrySet().stream()
                .filter(entry -> entry.getValue().role() == dev.llaith.forge.workflow.state.ArtifactRole.REQUEST)
                .map(entry -> state.artifactIndex().get(entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (requestArtifacts.size() > 1) {
            throw new ForgeException("multiple artifacts are marked as the request input");
        }
        if (requestArtifacts.size() == 1) {
            return Optional.of(requestArtifacts.getFirst());
        }
        return Optional.ofNullable(state.artifactIndex().get("request"));
    }

    public static ArtifactRecord requestArtifact(DerivedRunState state) {
        return optionalRequestArtifact(state)
                .orElseThrow(() -> new ForgeException("run does not have a request input artifact"));
    }
}
