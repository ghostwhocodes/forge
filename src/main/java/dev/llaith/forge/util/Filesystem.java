package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Filesystem {
    private Filesystem() {
    }

    public static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new ForgeException("failed to create directory " + path, error);
        }
    }

    public static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ForgeException("failed to read " + path, error);
        }
    }

    public static void writeUtf8(Path path, String content) {
        Path parent = path.getParent();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ForgeException("failed to write " + path, error);
        }
    }

    public static Path requireExistingDirectory(Path path, String label) {
        Path absolute;
        try {
            absolute = path.toRealPath();
        } catch (IOException error) {
            throw new ForgeException("failed to resolve " + label + " at " + path, error);
        }
        if (!Files.isDirectory(absolute)) {
            throw new ForgeException("error: " + label + " is not a directory: " + absolute);
        }
        return absolute;
    }
}
