package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DynatraceMcpAvailabilityTest {

    @Test
    void bridgeModeNeverOpensAClientFromThePassivePageLoadProbe() {
        AtomicInteger opens = new AtomicInteger();
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", Map.of(),
                null, null, DynatraceMcpSettings.AuthMode.HEADER, "", "", "", "",
                DynatraceMcpSettings.ConnectionMode.LOCAL_NPX_BRIDGE);
        DynatraceMcpClientFactory factory = new DynatraceMcpClientFactory(settings) {
            @Override
            public DynatraceTelemetryClient openIfConfigured() {
                opens.incrementAndGet();
                throw new AssertionError("bridge mode must never open a client from a passive probe");
            }
        };
        var availability = new DynatraceMcpAvailability(settings, factory);

        var result = availability.check();

        assertThat(opens.get()).isZero();
        assertThat(result.available()).isFalse();
        assertThat(result.problem()).contains("Local bridge mode");
    }

    @Test
    void directHttpsModeStillProbesNormally() {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", Map.of(),
                null, null, DynatraceMcpSettings.AuthMode.HEADER, "", "", "", "",
                DynatraceMcpSettings.ConnectionMode.DIRECT_HTTPS);
        DynatraceMcpClientFactory factory = new DynatraceMcpClientFactory(settings) {
            @Override
            public DynatraceTelemetryClient openIfConfigured() {
                return new DynatraceTelemetryClient() {
                    @Override
                    public TelemetryOutcome call(DynatraceTelemetryQuery query, Duration timeout) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ToolsOutcome listTools(Duration timeout) {
                        return new ToolsListed(java.util.List.of());
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        var availability = new DynatraceMcpAvailability(settings, factory);

        var result = availability.check();

        assertThat(result.available()).isTrue();
    }
}
