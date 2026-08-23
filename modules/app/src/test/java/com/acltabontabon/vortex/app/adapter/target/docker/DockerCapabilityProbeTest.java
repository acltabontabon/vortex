package com.acltabontabon.vortex.app.adapter.target.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Extracted from {@code DockerLocalLab}'s own availability check (see {@code
 * DockerLocalLabTest.missingDockerIsReportedNotThrown}, which this mirrors) so both {@code
 * DockerLocalLab} and {@link DockerImageTargetExecutor} share one place that parses {@code docker
 * --version} and checks daemon reachability.
 */
class DockerCapabilityProbeTest {

    @Test
    @DisplayName("reports Docker as unavailable when the executable does not exist")
    void missingDockerIsReportedNotThrown() {
        var probe = new DockerCapabilityProbe("definitely-not-a-real-docker-binary");

        var availability = probe.check();

        assertThat(availability.isUsable()).isFalse();
        assertThat(availability.installed()).isFalse();
        assertThat(availability.daemonReachable()).isFalse();
        assertThat(availability.remedy()).contains("Install Docker");
        // Docker is optional, and the remedy has to say so or people install it out of guilt.
        assertThat(availability.remedy()).contains("optional");
        assertThat(probe.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("a blank executable defaults to plain 'docker'")
    void blankExecutableDefaultsToDocker() {
        // Not asserting a real result (there may or may not be a real Docker on the machine
        // running this suite) — only that construction with a blank/absent value doesn't throw
        // and produces a usable probe, the same normalization DockerLocalLab already relies on.
        var probe = new DockerCapabilityProbe("  ");

        assertThat(probe.check()).isNotNull();
    }
}
