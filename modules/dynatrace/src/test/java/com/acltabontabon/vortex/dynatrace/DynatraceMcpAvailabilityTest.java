package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DynatraceMcpAvailabilityTest {

    @Test
    void unenabledReportsWhyWithoutConsultingAnyRecordedTestResult() {
        var settings = new DynatraceMcpSettings(false, "https://dynatrace-mcp.internal/mcp", null, null);
        var availability = new DynatraceMcpAvailability(settings);

        var result = availability.check();

        assertThat(result.available()).isFalse();
        assertThat(result.problem()).isEqualTo("Dynatrace MCP is not enabled.");
    }

    @Test
    void neverTestedReportsNotCheckedYet() {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", null, null);
        var availability = new DynatraceMcpAvailability(settings);

        var result = availability.check();

        assertThat(result.available()).isFalse();
        assertThat(result.problem()).isEqualTo("Not checked yet.");
        assertThat(result.remedy()).contains("Test Connection");
    }

    @Test
    void aSuccessfulTestConnectionIsReflectedByTheBadgeUntilInvalidated() {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", null, null);
        var availability = new DynatraceMcpAvailability(settings);

        availability.recordTestResult(true, "", "");
        assertThat(availability.check().available()).isTrue();

        availability.invalidate();
        assertThat(availability.check().available()).isFalse();
        assertThat(availability.check().problem()).isEqualTo("Not checked yet.");
    }

    @Test
    void aFailedTestConnectionReportsItsOwnProblemAndRemedy() {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", null,
                Duration.ofSeconds(5));
        var availability = new DynatraceMcpAvailability(settings);

        availability.recordTestResult(false, "the server did not advertise 'execute_dql'.",
                "The Dynatrace MCP server does not expose execute_dql yet.");

        var result = availability.check();
        assertThat(result.available()).isFalse();
        assertThat(result.problem()).isEqualTo("the server did not advertise 'execute_dql'.");
        assertThat(result.remedy()).isEqualTo("The Dynatrace MCP server does not expose execute_dql yet.");
    }
}
