package dev.llaith.forge.storage;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ProjectionBacklog {
    private ProjectionBacklog() {
    }

    static List<ProjectionBacklogEntry> inspect(Path runDir, long durableLastEventSeq) {
        return load(runDir, durableLastEventSeq).stream()
                .map(PendingProjectionBacklogEntry::entry)
                .toList();
    }

    static List<ProjectionBacklogEntry> recover(Path runDir, long durableLastEventSeq) {
        List<PendingProjectionBacklogEntry> pending = load(runDir, durableLastEventSeq);
        for (PendingProjectionBacklogEntry record : pending) {
            publishProjectionFile(
                    Path.of(record.entry().canonicalPath()),
                    Path.of(record.entry().projectionPath()));
            try {
                Files.delete(record.recordPath());
            } catch (IOException error) {
                throw new ForgeException(
                        "failed to remove satisfied projection backlog record " + record.recordPath(),
                        error);
            }
        }
        return pending.stream().map(PendingProjectionBacklogEntry::entry).toList();
    }

    private static List<PendingProjectionBacklogEntry> load(Path runDir, long durableLastEventSeq) {
        validateNoUnsupportedCommitStaging(runDir);
        Path backlogDir = RunPaths.projectionBacklogDir(runDir);
        if (!Files.exists(backlogDir)) {
            return List.of();
        }
        List<Path> recordPaths;
        try (var stream = Files.list(backlogDir)) {
            recordPaths = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException error) {
            throw new ForgeException("failed to read projection backlog directory " + backlogDir, error);
        }

        List<PendingProjectionBacklogEntry> backlog = new ArrayList<>();
        for (Path recordPath : recordPaths) {
            ProjectionBacklogEntry entry = Json.read(recordPath, ProjectionBacklogEntry.class);
            if (entry.firstEventSeq() > durableLastEventSeq) {
                throw new ForgeException("projection backlog record '" + recordPath
                        + "' references uncommitted event seq " + entry.firstEventSeq()
                        + " while durable state is " + durableLastEventSeq);
            }
            if (!Files.isRegularFile(Path.of(entry.canonicalPath()))) {
                throw new ForgeException("projection backlog record '" + recordPath
                        + "' points at missing canonical artifact " + entry.canonicalPath());
            }
            backlog.add(new PendingProjectionBacklogEntry(recordPath, entry));
        }
        return List.copyOf(backlog);
    }

    private static void publishProjectionFile(Path source, Path target) {
        validateTargetPath(target);
        Path parent = target.getParent();
        if (parent == null) {
            throw new ForgeException("artifact target '" + target + "' has no parent directory");
        }
        try {
            Files.createDirectories(parent);
            Path tempPath = parent.resolve(".forge-write-"
                    + ProcessHandle.current().pid()
                    + "-"
                    + System.nanoTime());
            try {
                Files.copy(source, tempPath, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException error) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // Preserve the publication failure as the primary error.
                }
                throw new ForgeException(
                        "failed to republish projection from " + source + " to " + target,
                        error);
            }
        } catch (IOException error) {
            throw new ForgeException("failed to create projection directory " + parent, error);
        }
    }

    private static void validateTargetPath(Path path) {
        if (Files.exists(path) && Files.isDirectory(path)) {
            throw new ForgeException("artifact target '" + path + "' is an existing directory");
        }

        Path current = path.getParent();
        while (current != null) {
            if (Files.exists(current)) {
                if (!Files.isDirectory(current)) {
                    throw new ForgeException("artifact target parent '" + current + "' is not a directory");
                }
                return;
            }
            current = current.getParent();
        }
    }

    private static void validateNoUnsupportedCommitStaging(Path runDir) {
        Path unsupportedDir = runDir.resolve(".commit-staging");
        if (Files.exists(unsupportedDir)) {
            throw new ForgeException("run contains unsupported prerelease commit staging at " + unsupportedDir
                    + "; manual cleanup is required before continuing");
        }
    }

    private record PendingProjectionBacklogEntry(Path recordPath, ProjectionBacklogEntry entry) {
    }
}
