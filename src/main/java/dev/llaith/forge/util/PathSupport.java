package dev.llaith.forge.util;

import java.nio.file.Path;

public final class PathSupport {
    private PathSupport() {
    }

    public static Path absolute(Path path) {
        if (path.isAbsolute()) {
            return path;
        }
        return Path.of("").toAbsolutePath().resolve(path);
    }

    public static Path normalize(Path path) {
        return path.normalize();
    }

    public static Path resolveRunPath(Path runDir, String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return runDir.resolve(path);
    }

    public static Path resolveRecordedArtifactPath(Path runDir, String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        Path cursor = path;
        while (cursor != null && cursor.getNameCount() > 0) {
            if (runDir.endsWith(cursor)) {
                return runDir.resolve(cursor.relativize(path));
            }
            cursor = cursor.getParent();
        }
        return runDir.resolve(path);
    }

    public static Path canonicalArtifactPath(Path runDir, String artifactName, String declaredPath) {
        String fileName = TextSupport.sanitizeArtifactName(artifactName);
        Path declared = Path.of(declaredPath);
        Path declaredName = declared.getFileName();
        if (declaredName != null) {
            String name = declaredName.toString();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                fileName = fileName + name.substring(dot);
            }
        }
        return runDir.resolve("artifacts").resolve("__canonical").resolve(fileName);
    }

    public static Path projectedArtifactPath(Path runDir, String declaredPath) {
        return resolveRunPath(runDir, declaredPath);
    }
}
