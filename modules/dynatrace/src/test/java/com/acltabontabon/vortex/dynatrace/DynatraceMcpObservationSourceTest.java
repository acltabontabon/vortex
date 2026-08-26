package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservationRequest;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieved;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynatraceMcpObservationSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW.minus(Duration.ofDays(30)), NOW);

    private static final Map<String, Object> EXECUTE_DQL_SCHEMA_WITH_ONE_ORGANIZATION = Map.of(
            "properties", Map.of("organization", Map.of("enum", List.of("my-org"))));

    private static final DynatraceTelemetryClient.ToolsListed TOOLS_LISTED_ONE_ORGANIZATION =
            new DynatraceTelemetryClient.ToolsListed(List.of(new DynatraceTelemetryClient.ToolInfo(
                    "execute_dql", EXECUTE_DQL_SCHEMA_WITH_ONE_ORGANIZATION)));

    /** A stub that answers every {@code call} with a fixed outcome, never touching the network. */
    private static final class FakeClient implements DynatraceTelemetryClient {
        private final TelemetryOutcome outcome;
        private final ToolsOutcome tools;

        FakeClient(TelemetryOutcome outcome) {
            this(outcome, TOOLS_LISTED_ONE_ORGANIZATION);
        }

        FakeClient(TelemetryOutcome outcome, ToolsOutcome tools) {
            this.outcome = outcome;
            this.tools = tools;
        }

        @Override
        public TelemetryOutcome call(DynatraceTelemetryQuery query, Duration timeout) {
            return outcome;
        }

        @Override
        public ToolsOutcome listTools(Duration timeout) {
            return tools;
        }

        @Override
        public void close() {
        }
    }

    private static DynatraceMcpClientFactory fakeFactory(DynatraceMcpSettings settings,
            DynatraceTelemetryClient.TelemetryOutcome outcome) {
        return new DynatraceMcpClientFactory(settings) {
            @Override
            public DynatraceTelemetryClient openIfConfigured() {
                return new FakeClient(outcome);
            }
        };
    }

    private static ObservationSource mcpSource(String entityId) {
        return new ObservationSource(ObservationSource.Kind.DYNATRACE, ObservationSource.Transport.MCP,
                "", entityId, Duration.ofDays(30), Map.of(), Map.of());
    }

    private static DynatraceMcpSettings enabledSettings() {
        return new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", null, null);
    }

    @Test
    void supportsOnlyDynatraceWithMcpTransport() {
        var source = new DynatraceMcpObservationSource(fakeFactory(enabledSettings(), null), enabledSettings());
        assertThat(source.supports(mcpSource("SERVICE-1"))).isTrue();
        assertThat(source.supports(new ObservationSource(ObservationSource.Kind.DYNATRACE,
                ObservationSource.Transport.REST, "https://dt.example.com", "SERVICE-1",
                Duration.ofDays(30), Map.of(), Map.of()))).isFalse();
        assertThat(source.supports(new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                "https://prom.example.com", "checkout", Duration.ofDays(30), Map.of(), Map.of())))
                .isFalse();
    }

    @Test
    void aValidThroughputAnswerBecomesAProductionObservation() throws Exception {
        var payload = JSON.readTree("""
                {"records": [{"requests": [60, 120, 90], "dt.entity.service": "SERVICE-1"}]}""");
        var outcome = new DynatraceTelemetryClient.Answered(new DynatraceTelemetryResult(payload, true));
        DynatraceMcpSettings settings = enabledSettings();
        var source = new DynatraceMcpObservationSource(fakeFactory(settings, outcome), settings);

        var request = new ObservationRequest(mcpSource("SERVICE-1"), WINDOW, Duration.ofMinutes(1), List.of());
        var retrieval = source.retrieve(request);

        assertThat(retrieval).isInstanceOfSatisfying(Retrieved.class, retrieved -> {
            var observation = retrieved.observation();
            assertThat(observation.peakRate().value().doubleValue()).isEqualTo(2.0); // 120 / 60s
            assertThat(observation.provenance()).isNotNull();
            assertThat(observation.provenance().providerId()).isEqualTo("dynatrace-mcp");
            assertThat(observation.provenance().query()).isEqualTo("dynatrace.throughput.v1");
        });
    }

    @Test
    void aToolFailureBecomesANotRetrievedWithARemedy() {
        var outcome = new DynatraceTelemetryClient.Failed(DynatraceMcpFailureCategory.AUTHENTICATION_FAILED,
                "token rejected");
        DynatraceMcpSettings settings = enabledSettings();
        var source = new DynatraceMcpObservationSource(fakeFactory(settings, outcome), settings);

        var request = new ObservationRequest(mcpSource("SERVICE-1"), WINDOW, Duration.ofMinutes(1), List.of());
        var retrieval = source.retrieve(request);

        assertThat(retrieval).isInstanceOfSatisfying(NotRetrieved.class,
                notRetrieved -> assertThat(notRetrieved.describe()).contains("credentials"));
    }

    @Test
    void disabledSettingsRefuseCleanlyWithoutOpeningAConnection() {
        DynatraceMcpSettings disabled = new DynatraceMcpSettings(false, "", null, null);
        var source = new DynatraceMcpObservationSource(fakeFactory(disabled, null), disabled);

        var retrieval = source.verify(mcpSource("SERVICE-1"), WINDOW, Duration.ofMinutes(1));

        assertThat(retrieval).isInstanceOfSatisfying(NotRetrieved.class,
                notRetrieved -> assertThat(notRetrieved.why()).contains("not enabled"));
    }

    @Test
    void anEmptyEndpointRefusesCleanlyEvenWhenEnabled() {
        DynatraceMcpSettings noEndpoint = new DynatraceMcpSettings(true, "", null, null);
        var source = new DynatraceMcpObservationSource(fakeFactory(noEndpoint, null), noEndpoint);

        var retrieval = source.verify(mcpSource("SERVICE-1"), WINDOW, Duration.ofMinutes(1));

        assertThat(retrieval).isInstanceOfSatisfying(NotRetrieved.class,
                notRetrieved -> assertThat(notRetrieved.why()).contains("no Dynatrace MCP endpoint"));
    }

    @Test
    void anAmbiguousOrganizationRefusesCleanlyRatherThanGuessing() {
        var multiOrgSchema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of("org-a", "org-b"))));
        var toolsWithAmbiguousOrganization = new DynatraceTelemetryClient.ToolsListed(
                List.of(new DynatraceTelemetryClient.ToolInfo("execute_dql", multiOrgSchema)));
        DynatraceMcpSettings settings = enabledSettings();
        var factory = new DynatraceMcpClientFactory(settings) {
            @Override
            public DynatraceTelemetryClient openIfConfigured() {
                return new FakeClient(null, toolsWithAmbiguousOrganization);
            }
        };
        var source = new DynatraceMcpObservationSource(factory, settings);

        var retrieval = source.verify(mcpSource("SERVICE-1"), WINDOW, Duration.ofMinutes(1));

        assertThat(retrieval).isInstanceOfSatisfying(NotRetrieved.class,
                notRetrieved -> assertThat(notRetrieved.why()).contains("cannot choose one automatically"));
    }
}
