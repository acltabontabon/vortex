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
    void aValidThroughputResponseIsNormalized() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120, 130, 90, 150], "dt.entity.service": "SERVICE-1"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class,
                normalized -> assertThat(normalized.telemetry().samples())
                        .containsExactlyInAnyOrder(120.0, 130.0, 90.0, 150.0));
    }

    @Test
    void aMismatchedUnitIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120], "unit": "percent"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.UnitUnrecognized.class));
    }

    @Test
    void aWindowEntirelyOutsideTheRequestedOneIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120],
                  "timeframe": {"start": "2025-01-01T00:00:00Z", "end": "2025-01-02T00:00:00Z"}}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.WindowMismatch.class));
    }

    @Test
    void aWindowOverlappingTheRequestedOneIsAccepted() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120],
                  "timeframe": {"start": "2026-07-27T00:00:00Z", "end": "2026-08-26T00:00:00Z"}}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOf(TelemetryNormalizer.Normalized.class);
    }

    @Test
    void anEntityThatDoesNotMatchIsRejected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120], "dt.entity.service": "SERVICE-OTHER"}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.EntityMismatch.class));
    }

    @Test
    void noEntityEchoInTheResponsePassesThroughRatherThanFailingClosed() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [120]}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, "SERVICE-1");
        assertThat(outcome).isInstanceOf(TelemetryNormalizer.Normalized.class);
    }

    @Test
    void allZeroSamplesAreRejectedAsEmptyResultNotAsAFailure() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"requests": [0, 0, 0]}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Rejected.class,
                rejected -> assertThat(rejected.reason()).isInstanceOf(NormalizationFailure.EmptyResult.class));
    }

    @Test
    void samplesFoundAtAnyNestingDepthAreCollected() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"result": {"data": [{"series": {"requests": [55, 65]}}]}}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.THROUGHPUT_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class,
                normalized -> assertThat(normalized.telemetry().samples()).containsExactlyInAnyOrder(55.0, 65.0));
    }

    @Test
    void latencyDefinitionCollectsAllThreePercentileFields() throws Exception {
        JsonNode payload = JSON.readTree("""
                {"records": [{"p50": [10], "p95": [40], "p99": [80]}]}""");
        var result = new DynatraceTelemetryResult(payload, true);
        var outcome = normalizer.normalize(DynatraceQueries.REQUEST_LATENCY_V1, result, WINDOW, null);
        assertThat(outcome).isInstanceOfSatisfying(TelemetryNormalizer.Normalized.class,
                normalized -> assertThat(normalized.telemetry().samples())
                        .containsExactlyInAnyOrder(10.0, 40.0, 80.0));
    }
}
