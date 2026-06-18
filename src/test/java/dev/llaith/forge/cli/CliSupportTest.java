package dev.llaith.forge.cli;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CliSupportTest {
    @Test
    void parseKeyValuePreservesValueSeparators() {
        CliSupport.KeyValue parsed = CliSupport.parseKeyValue("decision=accept=maybe", "decision");

        assertThat(parsed.key()).isEqualTo("decision");
        assertThat(parsed.value()).isEqualTo("accept=maybe");
    }

    @Test
    void parseKeyValueRejectsMissingKeyOrValue() {
        for (String value : new String[]{"missing", "=value", "key="}) {
            assertThatThrownBy(() -> CliSupport.parseKeyValue(value, "artifact"))
                    .isInstanceOf(ForgeException.class)
                    .hasMessage("error: invalid artifact, expected KEY=VALUE");
        }
    }

    @Test
    void parseBooleanFlagMatchesRustAcceptedValues() {
        assertThat(CliSupport.parseBooleanFlag("true", "retryable")).isTrue();
        assertThat(CliSupport.parseBooleanFlag("false", "retryable")).isFalse();

        assertThatThrownBy(() -> CliSupport.parseBooleanFlag("yes", "retryable"))
                .isInstanceOf(ForgeException.class)
                .hasMessage("error: invalid retryable, expected true or false");
    }

    @Test
    void cliErrorHelpersUseForgeErrorPrefixes() {
        assertThat(CliSupport.missingOption("--runs=DIR")).hasMessage("error: missing --runs=DIR");
        assertThat(CliSupport.unknownOption("--mystery")).hasMessage("error: unknown option: --mystery");
    }
}
