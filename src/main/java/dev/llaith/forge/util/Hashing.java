package dev.llaith.forge.util;

import dev.llaith.forge.ForgeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new ForgeException("SHA-256 is not available", error);
        }
    }

    public static String sha256Hex(Path path) {
        try {
            return sha256Hex(Files.readAllBytes(path));
        } catch (IOException error) {
            throw new ForgeException("failed to read bytes for hashing from " + path, error);
        }
    }
}
