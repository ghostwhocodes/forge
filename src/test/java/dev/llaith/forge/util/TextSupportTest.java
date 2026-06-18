package dev.llaith.forge.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TextSupportTest {
    @Test
    void envSuffixMatchesForgeArtifactEnvNormalization() {
        assertThat(TextSupport.envSuffix("implementation_report")).isEqualTo("IMPLEMENTATION_REPORT");
        assertThat(TextSupport.envSuffix("finding-selection.status")).isEqualTo("FINDING_SELECTION_STATUS");
    }

    @Test
    void sanitizeArtifactNameMatchesCanonicalArtifactRules() {
        assertThat(TextSupport.sanitizeArtifactName("report/name")).isEqualTo("report_name");
        assertThat(TextSupport.sanitizeArtifactName("safe-name_1")).isEqualTo("safe-name_1");
        assertThat(TextSupport.sanitizeArtifactName("")).isEqualTo("artifact");
    }

    @Test
    void shellQuoteMatchesRustSingleQuoteEscaping() {
        assertThat(TextSupport.shellQuote("simple")).isEqualTo("'simple'");
        assertThat(TextSupport.shellQuote("it's")).isEqualTo("'it'\"'\"'s'");
    }
}
