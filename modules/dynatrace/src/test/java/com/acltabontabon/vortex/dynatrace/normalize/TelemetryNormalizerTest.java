package com.acltabontabon.vortex.dynatrace.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.dynatrace.DynatraceTelemetryResult;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueries;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TelemetryNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TimeWindow WINDOW =
            new TimeWindow(Instant.parse("2026-07-27T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"));

    private final TelemetryNormalizer normalizer = new TelemetryNormalizer();

    @Test
    void proseInsteadOfStructuredDataIsRejected() {
        var result = new DynatraceTelemetryResult(JSON.getNodeFactory().textNode("about 120 req/s"), false);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.SchemaInvalid.class));
    }

    @Test
    void theRejectionIncludesASnippetOfWhatWasActuallyReturned() {
        var result = new DynatraceTelemetryResult(JSON.getNodeFactory().textNode("about 120 req/s"), false);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason().detail()).contains("about 120 req/s"));
    }

    @Test
    void aResponseWithNoMatchingFieldIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"somethingElse": 42}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.SchemaInvalid.class));
    }

    @Test
    void aPartiallyPresentFieldSetIsRejectedNamingWhatIsMissing() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": [12.5], "average": [9.0]}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason().detail()).contains("p95"));
    }

    @Test
    void aValidThroughputResponseIsNormalized() throws Exception {
        // Dynatrace's own `summarize` pipeline (see Dql#throughput) reduces the whole window to one
        // row per statistic — each field resolves to exactly one value, not a raw sample series.
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 15.0, "average": 12.0, "p95": 14.0, "dt.entity.service": "SERVICE-1"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class, normalized -> {
            assertThat(normalized.telemetry().valuesByField().get("peak")).containsExactly(15.0);
            assertThat(normalized.telemetry().valuesByField().get("average")).containsExactly(12.0);
            assertThat(normalized.telemetry().valuesByField().get("p95")).containsExactly(14.0);
        });
    }

    @Test
    void aMismatchedUnitIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 120, "average": 100, "p95": 110, "unit": "percent"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.UnitUnrecognized.class));
    }

    @Test
    void aWindowEntirelyOutsideTheRequestedOneIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 120, "average": 100, "p95": 110,
                  "timeframe": {"start": "2025-01-01T00:00:00Z", "end": "2025-01-02T00:00:00Z"}}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.WindowMismatch.class));
    }

    @Test
    void aWindowOverlappingTheRequestedOneIsAccepted() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 120, "average": 100, "p95": 110,
                  "timeframe": {"start": "2026-07-27T00:00:00Z", "end": "2026-08-26T00:00:00Z"}}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOf(TelemetryNormalizer.Normalized.class);
    }

    @Test
    void anEntityThatDoesNotMatchIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 120, "average": 100, "p95": 110, "dt.entity.service": "SERVICE-OTHER"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.EntityMismatch.class));
    }

    @Test
    void noEntityEchoInTheResponsePassesThroughRatherThanFailingClosed() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 120, "average": 100, "p95": 110}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOf(TelemetryNormalizer.Normalized.class);
    }

    @Test
    void allZeroValuesAreRejectedAsEmptyResultNotAsAFailure() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"peak": 0, "average": 0, "p95": 0}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.EmptyResult.class));
    }

    @Test
    void valuesFoundAtAnyNestingDepthAreCollected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"result": {"data": [{"series": {"peak": 55, "average": 50, "p95": 52}}]}}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class,
                normalized -> assertThat(normalized.telemetry().valuesByField().get("peak")).containsExactly(55.0));
    }

    @Test
    void latencyDefinitionCollectsAllThreePercentileFieldsAsRawPerBucketSamples() throws Exception {
        // dynatrace.request-latency.v1 hasn't been migrated to a `summarize` pipeline (it isn't wired
        // into a live retrieval), so it still expects raw per-bucket arrays, one field per percentile.
        JsonNode payload = JSON.readTree("""
                {"records": [{"p50": [10], "p95": [40], "p99": [80]}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.REQUEST_LATENCY_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class, normalized -> {
            assertThat(normalized.telemetry().valuesByField().get("p50")).containsExactly(10.0);
            assertThat(normalized.telemetry().valuesByField().get("p95")).containsExactly(40.0);
            assertThat(normalized.telemetry().valuesByField().get("p99")).containsExactly(80.0);
        });
    }
}
