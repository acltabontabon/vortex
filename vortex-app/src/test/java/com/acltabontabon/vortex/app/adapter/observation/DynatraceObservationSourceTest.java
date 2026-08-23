package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.OperationMixCoverage;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservedOperation;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.workload.OperationMix;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What Vortex makes of a body Dynatrace wrote.
 *
 * <p>Recorded fixtures, for the same reason as the Prometheus adapter's tests — with one extra
 * caveat worth stating: no live tenant is exercised anywhere in this build, so these fixtures are
 * the whole of the evidence that the mapping is right.
 */
class DynatraceObservationSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObservationSource SOURCE = new ObservationSource(
            ObservationSource.Kind.DYNATRACE, "https://abc12345.live.dynatrace.com",
            "SERVICE-1A2B3C4D5E6F7890", Duration.ofDays(30), Map.of(), Map.of());

    private static final List<ObservedOperation> CATALOG = List.of(
            new ObservedOperation(OperationId.of("getOrder"), "GET", "/orders/{id}"),
            new ObservedOperation(OperationId.of("createOrder"), "POST", "/orders"));

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = DynatraceObservationSourceTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    @Nested
    @DisplayName("reading a single figure")
    class Scalars {

        @Test
        void theHighestBucketIsTaken() throws IOException {
            assertThat(DynatraceObservationSource.scalarFrom(fixture("dynatrace-peak.json")))
                    .contains(656_640d);
        }

        @Test
        void aNullBucketIsAGapRatherThanAZero() throws IOException {
            // The fixture's first bucket is null. Treating it as zero would not change a maximum,
            // but it would quietly drag an average down — so gaps are skipped, not defaulted.
            assertThat(DynatraceObservationSource.scalarFrom(fixture("dynatrace-peak.json")))
                    .hasValueSatisfying(value -> assertThat(value).isPositive());
        }

        @Test
        void anEmptyResultIsAbsentRatherThanZero() throws IOException {
            assertThat(DynatraceObservationSource.scalarFrom(fixture("dynatrace-empty.json")))
                    .isEmpty();
        }

        @Test
        void aMissingBodyIsAbsent() {
            assertThat(DynatraceObservationSource.scalarFrom(null)).isEmpty();
        }

        @Test
        void aMalformedBodyIsAbsentRatherThanThrowing() throws IOException {
            assertThat(DynatraceObservationSource.scalarFrom(JSON.readTree("{\"error\":\"nope\"}")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("attributing traffic to operations")
    class Attribution {

        @Test
        void serviceMethodsAreMatchedByTheirDisplayName() throws IOException {
            var mix = DynatraceObservationSource.attribute(fixture("dynatrace-mix.json"), CATALOG);

            assertThat(mix.entries().stream().map(e -> e.operationId().value()))
                    .containsExactly("getOrder", "createOrder");
        }

        @Test
        void anUnmatchedMethodCountsTowardsTheTotalButNeverBecomesAnOperation() throws IOException {
            var mix = DynatraceObservationSource.attribute(fixture("dynatrace-mix.json"), CATALOG);

            assertThat(mix.matched()).isEqualTo(80_000d);
            assertThat(mix.total()).isEqualTo(100_000d);
            assertThat(mix.entries()).hasSize(2);
        }

        @Test
        void coverageIsStatedRatherThanNormalisedAway() throws IOException {
            var mix = DynatraceObservationSource.attribute(fixture("dynatrace-mix.json"), CATALOG);

            OperationMix shape = OperationMix.of(mix.entries());
            assertThat(shape.sharePercent(OperationId.of("getOrder"))).isEqualTo("62.5");

            var coverage = new OperationMixCoverage(Math.round(mix.total()), Math.round(mix.matched()));
            assertThat(coverage.coverage()).isEqualTo(0.8);
            assertThat(coverage.isComplete()).isFalse();
        }

        @Test
        void anEmptyResultAttributesNothingAndClaimsNoCoverage() throws IOException {
            var mix = DynatraceObservationSource.attribute(fixture("dynatrace-empty.json"), CATALOG);

            assertThat(mix.entries()).isEmpty();
            // Total of zero is what makes the adapter leave coverage absent rather than assume it
            // is complete. An unknown share is not a full share.
            assertThat(mix.total()).isZero();
        }
    }

    @Nested
    @DisplayName("identifying itself")
    class Identity {

        @Test
        void itAnswersOnlyForDynatrace() {
            var adapter = new DynatraceObservationSource(
                    org.springframework.web.client.RestClient.builder());

            assertThat(adapter.supports(SOURCE)).isTrue();
            assertThat(adapter.supports(new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                    "http://prometheus:9090", "checkout", Duration.ofDays(1), Map.of(), Map.of())))
                    .isFalse();
            assertThat(adapter.id()).isEqualTo("dynatrace");
        }

        @Test
        void prometheusLabelMappingIsIrrelevantHereAndIsSimplyNotRead() {
            // The two systems genuinely differ, and the abstraction is not flattened to hide it.
            var withLabels = new ObservationSource(ObservationSource.Kind.DYNATRACE,
                    "https://abc12345.live.dynatrace.com", "SERVICE-1", Duration.ofDays(30),
                    Map.of(), Map.of("route", "something-prometheus-specific"));

            assertThat(withLabels.kind()).isEqualTo(ObservationSource.Kind.DYNATRACE);
        }
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        void aReferenceToAnUnsetVariableIsReportedBeforeAnythingIsSent() {
            var withSecret = new ObservationSource(ObservationSource.Kind.DYNATRACE,
                    "https://abc12345.live.dynatrace.com", "SERVICE-1", Duration.ofDays(30),
                    Map.of("Authorization", "Api-Token ${A_TOKEN_NOBODY_HAS_SET}"), Map.of());

            assertThat(ObservationHttp.missingSecret(withSecret)).isEqualTo("A_TOKEN_NOBODY_HAS_SET");
        }
    }
}
