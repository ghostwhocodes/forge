package dev.llaith.forge.util;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class TimeSupport {
    private static final DateTimeFormatter FORGE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private TimeSupport() {
    }

    public static String now(Clock clock) {
        return FORGE_TIMESTAMP.format(ZonedDateTime.now(clock));
    }
}
