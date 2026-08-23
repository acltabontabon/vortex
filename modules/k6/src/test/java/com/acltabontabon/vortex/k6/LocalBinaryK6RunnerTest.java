package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * A native process has no cross-platform mechanism Vortex can rely on to enforce a resource budget —
 * see {@code AutomaticLoadGeneratorAllocation}'s Javadoc. This pins the honest half of that: whatever
 * is requested, {@link LocalBinaryK6Runner} never claims to have applied it.
 */
class LocalBinaryK6RunnerTest {

    static boolean k6IsInstalled() {
        return new LocalBinaryK6Runner("k6").version().isPresent();
    }

    @Test
    @EnabledIf("k6IsInstalled")
    @DisplayName("a requested budget is never reported as applied, even when the process succeeds")
    void neverReportsAppliedResources(@TempDir Path workingDir) throws Exception {
        java.nio.file.Files.writeString(workingDir.resolve("script.js"), """
                export const options = { vus: 1, iterations: 1 };
                export default function () {}
                """);
        ResourceEnvelopeRequest resources = new ResourceEnvelopeRequest(
                CpuAllocation.ofMillicores(500), MemoryAllocation.ofMebibytes(256));

        var outcome = new LocalBinaryK6Runner("k6").run(
                java.util.List.of("run", "--quiet", "--no-color", "script.js"), workingDir, Map.of(),
                resources, _ -> { }, _ -> { }, Cancellation.never());

        assertThat(outcome.producedAResult()).isTrue();
        assertThat(outcome.effectiveResources()).isNull();
        assertThat(outcome.generatorOomKilled()).isFalse();
    }

    @Test
    @EnabledIf("k6IsInstalled")
    @DisplayName("no request means nothing to not-apply either, which is the same honest null")
    void unconstrainedRunAlsoReportsNoEffectiveResources(@TempDir Path workingDir) throws Exception {
        java.nio.file.Files.writeString(workingDir.resolve("script.js"), """
                export const options = { vus: 1, iterations: 1 };
                export default function () {}
                """);

        var outcome = new LocalBinaryK6Runner("k6").run(
                java.util.List.of("run", "--quiet", "--no-color", "script.js"), workingDir, Map.of(),
                ResourceEnvelopeRequest.none(), _ -> { }, _ -> { }, Cancellation.never());

        assertThat(outcome.producedAResult()).isTrue();
        assertThat(outcome.effectiveResources()).isNull();
    }
}
