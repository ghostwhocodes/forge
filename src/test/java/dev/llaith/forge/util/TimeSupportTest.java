package dev.llaith.forge.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

final class TimeSupportTest {
    @Test
    void nowFormatsLikeRustLocalTimestampShape() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-23T01:02:03Z"), ZoneOffset.UTC);

        assertThat(TimeSupport.now(clock)).isEqualTo("2026-05-23T01:02:03+0000");
    }
}
