package dev.llaith.forge.util;

public final class TextSupport {
    private TextSupport() {
    }

    public static String envSuffix(String value) {
        StringBuilder key = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isAsciiAlphanumeric(ch)) {
                key.append(Character.toUpperCase(ch));
            } else {
                key.append('_');
            }
        }
        return key.toString();
    }

    public static String sanitizeArtifactName(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isAsciiAlphanumeric(ch) || ch == '-' || ch == '_') {
                out.append(ch);
            } else {
                out.append('_');
            }
        }
        return out.isEmpty() ? "artifact" : out.toString();
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean isAsciiAlphanumeric(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9');
    }
}
