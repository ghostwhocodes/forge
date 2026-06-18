package dev.llaith.forge.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class PathSupportTest {
    @Test
    void resolveRunPathKeepsAbsolutePathsAndRehomesRelativePaths() {
        Path runDir = Path.of("/tmp/run");

        assertThat(PathSupport.resolveRunPath(runDir, "artifacts/report.md"))
                .isEqualTo(Path.of("/tmp/run/artifacts/report.md"));
        assertThat(PathSupport.resolveRunPath(runDir, "/var/report.md"))
                .isEqualTo(Path.of("/var/report.md"));
    }

    @Test
    void resolveRecordedArtifactPathTrimsDuplicatedRunSuffixes() {
        Path runDir = Path.of("/tmp/runs/parent");

        assertThat(PathSupport.resolveRecordedArtifactPath(runDir, "/var/artifacts/report.md"))
                .isEqualTo(Path.of("/var/artifacts/report.md"));
        assertThat(PathSupport.resolveRecordedArtifactPath(runDir, "parent/artifacts/report.md"))
                .isEqualTo(Path.of("/tmp/runs/parent/artifacts/report.md"));
        assertThat(PathSupport.resolveRecordedArtifactPath(runDir, "artifacts/report.md"))
                .isEqualTo(Path.of("/tmp/runs/parent/artifacts/report.md"));
    }

    @Test
    void canonicalArtifactPathMatchesRustShape() {
        Path runDir = Path.of("/tmp/run");

        assertThat(PathSupport.canonicalArtifactPath(runDir, "report/name", "artifacts/report.md"))
                .isEqualTo(Path.of("/tmp/run/artifacts/__canonical/report_name.md"));
        assertThat(PathSupport.canonicalArtifactPath(runDir, "", "/tmp/archive.tar.gz"))
                .isEqualTo(Path.of("/tmp/run/artifacts/__canonical/artifact.gz"));
    }

    @Test
    void projectedArtifactPathPreservesDeclaredShape() {
        Path runDir = Path.of("/tmp/run");

        assertThat(PathSupport.projectedArtifactPath(runDir, "artifacts/report.md"))
                .isEqualTo(Path.of("/tmp/run/artifacts/report.md"));
        assertThat(PathSupport.projectedArtifactPath(runDir, "/tmp/out/report.md"))
                .isEqualTo(Path.of("/tmp/out/report.md"));
    }

    @Test
    void absoluteAndNormalizeAreLexicalHelpers() {
        assertThat(PathSupport.absolute(Path.of("/tmp/absolute"))).isEqualTo(Path.of("/tmp/absolute"));
        assertThat(PathSupport.absolute(Path.of("relative")).isAbsolute()).isTrue();
        assertThat(PathSupport.normalize(Path.of("/tmp/one/../two"))).isEqualTo(Path.of("/tmp/two"));
    }
}
