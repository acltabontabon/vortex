package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DynatraceMcpConnectionTestTest {

    @Test
    void runBridgeFailsAtLocalBridgeStartedForAnInvalidEndpointWithoutSpawningAnything() {
        // A non-https URL fails DynatraceMcpEndpoint's own validation before any process is
        // spawned — this is the one runBridge path exercisable without a real npx/mcp-remote/browser,
        // which the rest of this connection mode genuinely needs (see docs/adr/adr-051-...).
        var connectionTest = new DynatraceMcpConnectionTest();

        var report = connectionTest.runBridge("http://dynatrace-mcp.internal/mcp", Duration.ofSeconds(2));

        assertThat(report.succeeded()).isFalse();
        assertThat(report.stages()).hasSize(1);
        assertThat(report.stages().get(0).stage()).isEqualTo("Local bridge started");
        assertThat(report.stages().get(0).succeeded()).isFalse();
    }
}
