package dev.vortex.app.adapter.target.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerProcess} against ordinary shell commands rather than {@code docker} itself — what
 * is worth pinning down here is generic process plumbing (capturing stdout/stderr/exit code, and
 * enforcing the timeout), not anything Docker-specific, so a real Docker daemon is not needed.
 */
class DockerProcessTest {

    private final DockerProcess process = new DockerProcess();

    @Test
    @DisplayName("captures stdout, stderr and the exit code separately")
    void capturesStdoutStderrAndExitCode() {
        var result = process.run(
                List.of("sh", "-c", "echo out-line; echo err-line 1>&2; exit 3"),
                Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.stdout()).containsExactly("out-line");
        assertThat(result.stderr()).containsExactly("err-line");
    }

    @Test
    @DisplayName("a successful command with a single stdout line reports it as the first line")
    void firstStdoutLineIsReported() {
        var result = process.run(List.of("sh", "-c", "echo abc123"), Duration.ofSeconds(5));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.firstStdoutLine()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("a process that outlives its timeout is killed rather than left to hang")
    void killsAProcessThatOutlivesItsTimeout() {
        Instant start = Instant.now();

        var result = process.run(List.of("sh", "-c", "sleep 5"), Duration.ofMillis(300));

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(result.succeeded()).isFalse();
        // Well under the 5-second sleep: the process was killed, not waited out.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }
}
