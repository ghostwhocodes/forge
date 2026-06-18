package dev.llaith.forge.runtime.run;

import dev.llaith.forge.ForgeException;

public record RunSlug(String value) {
    public RunSlug {
        validate(value);
    }

    public static RunSlug parse(String raw) {
        return new RunSlug(raw);
    }

    public String asString() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validate(String slug) {
        if (slug.isEmpty()) {
            throw new ForgeException("error: invalid slug: slug must not be empty");
        }
        if (!slug.chars().allMatch(RunSlug::isAllowedSlugCharacter)) {
            throw new ForgeException(
                    "error: invalid slug '" + slug
                            + "': must contain only ASCII alphanumeric characters, hyphens, or underscores"
            );
        }
    }

    private static boolean isAllowedSlugCharacter(int ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '-'
                || ch == '_';
    }
}
