package dev.llaith.forge.runtime.run;

import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RunSlugTest {
    @Test
    void acceptsSimpleSlugs() {
        for (String slug : new String[]{"my-run", "run_001", "a", "ABC-123_xyz"}) {
            assertThat(RunSlug.parse(slug).asString()).isEqualTo(slug);
            assertThat(RunSlug.parse(slug).toString()).isEqualTo(slug);
        }
    }

    @Test
    void rejectsEmptySlug() {
        assertThatThrownBy(() -> RunSlug.parse(""))
                .isInstanceOf(ForgeException.class)
                .hasMessage("error: invalid slug: slug must not be empty");
    }

    @Test
    void rejectsInvalidCharactersAndPathTraversal() {
        for (String slug : new String[]{"foo bar", "a.b", ".staging", "../etc", "./run", "foo/bar"}) {
            assertThatThrownBy(() -> RunSlug.parse(slug))
                    .isInstanceOf(ForgeException.class)
                    .hasMessageContaining("error: invalid slug");
        }
    }
}
