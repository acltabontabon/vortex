package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Docker, actually enforcing (and actually exceeding) a load generator resource budget.
 *
 * <p>Everything {@code DockerK6RunnerTest} can prove with a fake {@code docker} executable, this
 * proves against the real daemon instead: that {@code --cpus}/{@code --memory} land on the container
 * Docker actually creates, that Vortex's own re-inspection agrees with what it requested, that the
 * container is never left behind (no {@code --rm}, explicit removal instead), and — the case this
 * feature exists for — that a generator killed for exceeding its enforced memory budget is reported
 * as exactly that, not an opaque non-zero exit.
 */
@EnabledIf("dockerIsAvailable")
class DockerK6RunnerResourceEnforcementIntegrationTest {

    static boolean dockerIsAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            boolean exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private DockerK6Runner runner() {
        return new DockerK6Runner("docker", DockerK6Runner.DEFAULT_IMAGE);
    }

    private void writeTrivialScript(Path workingDir) {
        allowContainerToTraverse(workingDir);
        try {
            Files.writeString(workingDir.resolve("script.js"), """
                    export const options = { vus: 1, iterations: 1 };
                    export default function () {}
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code @TempDir} creates its directory {@code rwx------}, which the official k6 image's
     *  non-root container user cannot traverse once bind-mounted on a native Linux Docker host —
     *  {@code stat script.js} then fails with a permission error that k6 reports as the file being
     *  entirely absent. Vortex's own execution directories are unaffected: they are created with
     *  {@code Files.createDirectories}, which follows the umask instead of JUnit's deliberately
     *  private default. This is a test-fixture correction, not a change to runner behaviour. */
    private void allowContainerToTraverse(Path workingDir) {
        try {
            Files.setPosixFilePermissions(workingDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // No POSIX permissions to set (e.g. native Windows) — Docker there runs Linux containers
            // inside its own VM, where bind-mounted paths are translated by Docker Desktop rather
            // than inheriting host ACLs, so there is nothing for this test to adjust.
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A script that allocates far more than a very small memory budget the instant a VU starts,
     *  before a single iteration runs — so a container capped well below it is killed for exceeding
     *  the limit rather than merely running slowly. */
    private void writeMemoryHungryScript(Path workingDir) {
        allowContainerToTraverse(workingDir);
        try {
            Files.writeString(workingDir.resolve("script.js"), """
                    // Allocated in the init context, once per VU, before any iteration — a single
                    // 300 MB string is far more than the container's own --memory budget in this test.
                    const oversized = 'x'.repeat(300 * 1024 * 1024);

                    export const options = { vus: 1, iterations: 1 };
                    export default function () {
                        if (oversized.length === 0) {
                            throw new Error('unreachable');
                        }
                    }
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> runArgs() {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("--quiet");
        args.add("--no-color");
        args.add("script.js");
        return args;
    }

    private String containerNameFor(Path workingDir) {
        return "vortex-k6-" + workingDir.getFileName();
    }

    private void assertContainerDoesNotExist(String containerName) throws IOException, InterruptedException {
        Process inspect = new ProcessBuilder("docker", "inspect", containerName)
                .redirectErrorStream(true).start();
        assertThat(inspect.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // A non-zero exit is "No such object" — exactly what "removed, not left behind" means.
        assertThat(inspect.exitValue()).isNotZero();
    }

    @Test
    @DisplayName("a requested CPU/memory budget is applied and re-confirmed against the real container")
    void appliesAndConfirmsTheRequestedBudget(@TempDir Path workingDir) throws Exception {
        writeTrivialScript(workingDir);
        ResourceEnvelopeRequest resources = new ResourceEnvelopeRequest(
                CpuAllocation.ofMillicores(500), MemoryAllocation.ofMebibytes(256));

        var outcome = runner().run(runArgs(), workingDir, Map.of(), resources,
                _ -> { }, _ -> { }, Cancellation.never());

        assertThat(outcome.producedAResult()).isTrue();
        assertThat(outcome.effectiveResources()).isNotNull();
        assertThat(outcome.effectiveResources().cpuIfPresent())
                .contains(CpuAllocation.ofMillicores(500));
        assertThat(outcome.effectiveResources().memoryIfPresent())
                .contains(MemoryAllocation.ofMebibytes(256));
        assertThat(outcome.generatorOomKilled()).isFalse();

        assertContainerDoesNotExist(containerNameFor(workingDir));
    }

    @Test
    @DisplayName("no budget requested means no --cpus/--memory applied, and nothing to confirm")
    void unconstrainedRunAppliesNothing(@TempDir Path workingDir) throws Exception {
        writeTrivialScript(workingDir);

        var outcome = runner().run(runArgs(), workingDir, Map.of(), ResourceEnvelopeRequest.none(),
                _ -> { }, _ -> { }, Cancellation.never());

        assertThat(outcome.producedAResult()).isTrue();
        assertThat(outcome.effectiveResources()).isNull();
        assertThat(outcome.generatorOomKilled()).isFalse();

        assertContainerDoesNotExist(containerNameFor(workingDir));
    }

    @Test
    @DisplayName("a generator that exceeds its enforced memory budget is reported as OOM-killed, "
            + "not an opaque failure")
    void exceedingTheEnforcedMemoryBudgetIsReportedAsOom(@TempDir Path workingDir) throws Exception {
        writeMemoryHungryScript(workingDir);
        // Deliberately far below what the script needs (300 MB) — CPU is left unconstrained so the
        // only thing this run can possibly fail on is memory.
        ResourceEnvelopeRequest resources = new ResourceEnvelopeRequest(
                CpuAllocation.ofMillicores(1000), MemoryAllocation.ofMebibytes(48));

        var outcome = runner().run(runArgs(), workingDir, Map.of(), resources,
                _ -> { }, _ -> { }, Cancellation.never());

        assertThat(outcome.generatorOomKilled()).isTrue();
        assertThat(outcome.producedAResult()).isFalse();

        assertContainerDoesNotExist(containerNameFor(workingDir));
    }
}
