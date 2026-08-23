package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place Vortex launches a subprocess: normal completion, cooperative cancellation, and the
 * forced-kill fallback when a process ignores it. These run real {@code sh} processes rather than
 * mocking {@link Process} — the behaviour under test is what the OS actually does with signals.
 */
class ProcessExecutionTest {

    private static final Cancellation NEVER = () -> false;
    private static final Cancellation IMMEDIATELY = () -> true;

    @TempDir
    Path workingDir;

    @Test
    void capturesTheExitCodeOfANormallyFinishedProcess() {
        var outcome = ProcessExecution.run(List.of("sh", "-c", "exit 3"), workingDir, Map.of(),
                line -> { }, line -> { }, NEVER, process -> { });

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.cancelled()).isFalse();
    }

    @Test
    void cancellationStopsAProcessWellBeforeItWouldFinishOnItsOwn() {
        Instant start = Instant.now();

        var outcome = ProcessExecution.run(List.of("sh", "-c", "sleep 30"), workingDir, Map.of(),
                line -> { }, line -> { }, IMMEDIATELY, process -> { });

        assertThat(outcome.cancelled()).isTrue();
        // sh responds to the default TERM signal immediately, so this should complete in well under
        // a second — asserting "under 30s" would also pass if cancellation silently did nothing and
        // this test just got lucky on timing, which is exactly the failure mode worth ruling out.
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aProcessThatIgnoresTerminationIsForciblyKilledAfterTheGracePeriod() {
        Instant start = Instant.now();

        // Ignores SIGTERM outright, so only destroyForcibly() (SIGKILL) can end it. A 1-second grace
        // proves the forced-kill path fires — without it, this would hang for the real 30s sleep.
        var outcome = ProcessExecution.run(List.of("sh", "-c", "trap '' TERM; sleep 30"), workingDir,
                Map.of(), line -> { }, line -> { }, IMMEDIATELY, process -> { }, 1L);

        assertThat(outcome.cancelled()).isTrue();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void aTokenLookingArgumentIsMaskedButOrdinaryOnesSurvive() {
        String masked = ProcessExecution.mask(
                List.of("k6", "run", "--tag", "env=staging", "--header", "Authorization: secret-value"));

        assertThat(masked)
                .contains("k6 run --tag env=staging")
                .doesNotContain("secret-value");
    }
}
