package com.acltabontabon.vortex.app.adapter.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of the Docker adapter that hold without a Docker daemon.
 *
 * <p>Nothing here mocks {@code ProcessBuilder}. What is worth pinning down is that every failure
 * comes back as a {@code LabResult} a page can render — an adapter that threw would take the
 * settings page down with it — and that the remedies name a next action.
 */
class DockerLocalLabTest {

    @Test
    @DisplayName("reports Docker as unavailable when the executable does not exist")
    void missingDockerIsReportedNotThrown() {
        var lab = new DockerLocalLab("definitely-not-a-real-docker-binary");

        var status = lab.status();

        assertThat(status.isUsable()).isFalse();
        assertThat(status.dockerAvailable()).isFalse();
        assertThat(status.remedy()).contains("Install Docker");
        // Docker is optional, and the remedy has to say so or people install it out of guilt.
        assertThat(status.remedy()).contains("optional");
    }

    @Test
    @DisplayName("refuses a compose path that is not a regular file")
    void aMissingComposeFileIsRefusedBeforeAnythingRuns(@TempDir Path directory) {
        var lab = new DockerLocalLab("definitely-not-a-real-docker-binary");

        var result = lab.up(directory.resolve("compose.yaml").toString());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No Compose file was found at");
        assertThat(result.output()).isEmpty();
    }

    @Test
    @DisplayName("refuses to run a directory as a compose file")
    void aDirectoryIsNotAComposeFile(@TempDir Path directory) {
        var lab = new DockerLocalLab("definitely-not-a-real-docker-binary");

        var result = lab.down(directory.toString());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No Compose file was found at");
    }

    @Test
    @DisplayName("output past the line cap is drained rather than left to block the process")
    void aProcessEmittingMoreThanTheLineCapDoesNotHang() {
        var lab = new DockerLocalLab("docker");
        List<String> command = List.of("sh", "-c",
                "i=1; while [ $i -le 500 ]; do echo \"line-$i\"; i=$((i + 1)); done");

        // A bounded read that stopped at 200 lines before waiting for the process would leave the
        // OS pipe buffer full and the child blocked on its own stdout write — this asserts that
        // does not happen by giving the test itself a tight ceiling well under the command's own
        // 10-second timeout.
        var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> lab.run(command, null, 10));

        assertThat(result.exitCode()).isZero();
        // Bounded to the most recent lines: the tail is what a real diagnosis needs, not whatever
        // happened to be printed first.
        assertThat(result.output()).hasSize(200);
        assertThat(result.output().getFirst()).isEqualTo("line-301");
        assertThat(result.output().getLast()).isEqualTo("line-500");
    }
}
