package com.acltabontabon.vortex.app.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.port.ObservabilityProvider.CorrelationCapability;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Markers are enrichment. Losing them must never cost a measurement.
 *
 * <p>Reading Dynatrace metrics and ingesting Dynatrace events need different permissions, and a
 * production tenant may well grant the first and refuse the second. A run that collected real
 * telemetry and could not write a marker has collected real telemetry; reporting it as though the
 * observability integration had failed would discard evidence over a permission that has nothing to
 * do with it.
 */
class DynatraceMarkerFixturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = DynatraceMarkerFixturesTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    @Nested
    @DisplayName("metrics and markers both available")
    class BothAvailable {

        @Test
        void theHighestValueIsTakenAndTheDependencyIsNamed() throws IOException {
            var reading = DynatraceObservabilityProvider.highest(fixture("dynatrace-signals.json"));

            assertThat(reading).isNotNull();
            assertThat(reading.value()).isEqualTo(4120.5);
            // The topology Prometheus cannot supply: a report can say which dependency got slower
            // rather than only that a downstream call did.
            assertThat(reading.dimensions()).containsEntry("dependency", "payments-service");
        }

        @Test
        void theCapabilityIsTheStrongerOne() {
            assertThat(CorrelationCapability.EVENT_MARKERS.label())
                    .contains("timeline");
        }
    }

    @Nested
    @DisplayName("metrics available, marker authorization rejected")
    class MarkerRejected {

        private final TelemetryGap gap = new TelemetryGap("dynatrace", "run markers",
                TelemetryAvailability.UNAUTHORIZED,
                "Dynatrace telemetry was collected, but run markers were not created because the "
                        + "configured credentials do not permit event ingestion. The run is still "
                        + "findable by its time window.");

        @Test
        void theMeasurementsAreStillRead() throws IOException {
            // The whole point: the marker failure is on a different endpoint with a different scope,
            // and the metric query is entirely unaffected by it.
            var reading = DynatraceObservabilityProvider.highest(fixture("dynatrace-signals.json"));

            assertThat(reading).isNotNull();
            assertThat(reading.value()).isEqualTo(4120.5);
        }

        @Test
        void theGapExplainsWhatWasLostAndWhatWasNot() {
            assertThat(gap.describe())
                    .contains("telemetry was collected")
                    .contains("do not permit event ingestion")
                    .contains("still findable by its time window");
        }

        @Test
        void andNoMeasurementIsDowngradedToMissing() {
            assertThat(gap.metricName())
                    .as("the gap is about the markers, never about a signal")
                    .isEqualTo("run markers");
        }
    }

    @Nested
    @DisplayName("metrics available, marker endpoint unavailable")
    class MarkerEndpointDown {

        @Test
        void degradesToQueryOnlyRatherThanFailing() {
            var gap = new TelemetryGap("dynatrace", "run markers",
                    TelemetryAvailability.UNREACHABLE, "connect timed out");

            assertThat(gap.availability()).isEqualTo(TelemetryAvailability.UNREACHABLE);
            assertThat(CorrelationCapability.QUERY_ONLY.label()).contains("window");
        }

        @Test
        void theMeasurementsAreStillRead() throws IOException {
            assertThat(DynatraceObservabilityProvider.highest(fixture("dynatrace-signals.json")))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("reading a signal")
    class Reading {

        @Test
        void anEmptyResultIsAbsentRatherThanZero() throws IOException {
            assertThat(DynatraceObservabilityProvider.highest(fixture("dynatrace-signals-empty.json")))
                    .isNull();
        }

        @Test
        void nullBucketsAreGapsAndAreSkipped() throws IOException {
            // A bucket with no sample has not observed 0% utilisation. Reading it as zero would
            // report a pool as idle at exactly the moment nobody was watching it.
            var reading =
                    DynatraceObservabilityProvider.highest(fixture("dynatrace-signals-gaps.json"));

            assertThat(reading).isNotNull();
            assertThat(reading.value()).isEqualTo(97.5);
        }

        @Test
        void aMissingBodyIsAbsent() {
            assertThat(DynatraceObservabilityProvider.highest(null)).isNull();
        }

        @Test
        void aMalformedBodyIsAbsentRatherThanThrowing() throws IOException {
            assertThat(DynatraceObservabilityProvider.highest(JSON.readTree("{\"error\":\"nope\"}")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("what Prometheus can and cannot offer")
    class PrometheusCapability {

        @Test
        @DisplayName("query-only is a fact about Prometheus, not a shortfall")
        void isPermanentlyQueryOnly() {
            var provider = new PrometheusObservabilityProvider(
                    org.springframework.web.client.RestClient.builder(),
                    "http://prometheus.internal:9090");

            assertThat(provider.correlationCapability()).isEqualTo(CorrelationCapability.QUERY_ONLY);
        }

        @Test
        void andItsSignalsAreChosenToSeparateExplanations() {
            // Saturation, queueing and dependency health. Adding more would make a longer report,
            // not a more answerable one.
            assertThat(PrometheusObservabilityProvider.SIGNALS.keySet())
                    .contains("jvm.memory.utilization")
                    .contains("pool.connections.pending")
                    .contains("dependency.latency.p95");
        }
    }
}
