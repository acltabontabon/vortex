package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynatraceEntityLookupTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> EXECUTE_DQL_SCHEMA_WITH_ONE_ORGANIZATION = Map.of(
            "properties", Map.of("organization", Map.of("enum", List.of("my-org"))));

    private static final DynatraceTelemetryClient.ToolsListed TOOLS_LISTED_ONE_ORGANIZATION =
            new DynatraceTelemetryClient.ToolsListed(List.of(new DynatraceTelemetryClient.ToolInfo(
                    "execute_dql", EXECUTE_DQL_SCHEMA_WITH_ONE_ORGANIZATION)));

    /** A stub that answers every {@code call} with a fixed outcome, never touching the network. */
    private static final class FakeClient implements DynatraceTelemetryClient {
        private final TelemetryOutcome outcome;
        private final ToolsOutcome tools;

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
        return fakeFactory(settings, outcome, TOOLS_LISTED_ONE_ORGANIZATION);
    }

    private static DynatraceMcpClientFactory fakeFactory(DynatraceMcpSettings settings,
            DynatraceTelemetryClient.TelemetryOutcome outcome, DynatraceTelemetryClient.ToolsOutcome tools) {
        return new DynatraceMcpClientFactory(settings) {
            @Override
            public DynatraceTelemetryClient openIfConfigured() {
                return new FakeClient(outcome, tools);
            }
        };
    }

    private static DynatraceMcpSettings enabledSettings() {
        return new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", null, null);
    }

    @Test
    void disabledSettingsRefuseCleanlyWithoutOpeningAConnection() {
        DynatraceMcpSettings disabled = new DynatraceMcpSettings(false, "", null, null);
        var lookup = new DynatraceEntityLookup(fakeFactory(disabled, null), disabled);

        var result = lookup.lookup("checkout");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Failed.class,
                failed -> assertThat(failed.problem()).contains("not enabled"));
    }

    @Test
    void anEmptyEndpointRefusesCleanlyEvenWhenEnabled() {
        DynatraceMcpSettings noEndpoint = new DynatraceMcpSettings(true, "", null, null);
        var lookup = new DynatraceEntityLookup(fakeFactory(noEndpoint, null), noEndpoint);

        var result = lookup.lookup("checkout");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Failed.class,
                failed -> assertThat(failed.problem()).contains("No Dynatrace MCP endpoint"));
    }

    @Test
    void anUnconfiguredAmbiguousOrganizationRefusesCleanlyAndPointsAtSettings() {
        var multiOrgSchema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of("org-a", "org-b"))));
        var toolsWithAmbiguousOrganization = new DynatraceTelemetryClient.ToolsListed(
                List.of(new DynatraceTelemetryClient.ToolInfo("execute_dql", multiOrgSchema)));
        DynatraceMcpSettings settings = enabledSettings();
        var lookup = new DynatraceEntityLookup(
                fakeFactory(settings, null, toolsWithAmbiguousOrganization), settings);

        var result = lookup.lookup("checkout");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Failed.class, failed -> {
            assertThat(failed.problem()).contains("2 organizations");
            assertThat(failed.remedy()).contains("Settings");
        });
    }

    @Test
    void aClassifiedToolFailureBecomesAFailedWithARemedy() {
        var outcome = new DynatraceTelemetryClient.Failed(DynatraceMcpFailureCategory.AUTHENTICATION_FAILED,
                "token rejected");
        DynatraceMcpSettings settings = enabledSettings();
        var lookup = new DynatraceEntityLookup(fakeFactory(settings, outcome), settings);

        var result = lookup.lookup("checkout");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Failed.class,
                failed -> assertThat(failed.problem()).contains("credentials"));
    }

    @Test
    void aSuccessfulSearchReturnsParsedCandidates() throws Exception {
        var payload = JSON.readTree("""
                [{"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"}]""");
        var outcome = new DynatraceTelemetryClient.Answered(new DynatraceTelemetryResult(payload, true));
        DynatraceMcpSettings settings = enabledSettings();
        var lookup = new DynatraceEntityLookup(fakeFactory(settings, outcome), settings);

        var result = lookup.lookup("checkout");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Found.class,
                found -> assertThat(found.candidates()).containsExactly(
                        new com.acltabontabon.vortex.dynatrace.query.DynatraceEntitySearch.Candidate(
                                "SERVICE-1A2B3C4D5E6F7890", "checkout-service")));
    }

    @Test
    void aSearchWithNoMatchesReturnsAnEmptyFound() throws Exception {
        var payload = JSON.readTree("[]");
        var outcome = new DynatraceTelemetryClient.Answered(new DynatraceTelemetryResult(payload, true));
        DynatraceMcpSettings settings = enabledSettings();
        var lookup = new DynatraceEntityLookup(fakeFactory(settings, outcome), settings);

        var result = lookup.lookup("no-such-service");

        assertThat(result).isInstanceOfSatisfying(DynatraceEntityLookup.Found.class,
                found -> assertThat(found.candidates()).isEmpty());
    }
}
