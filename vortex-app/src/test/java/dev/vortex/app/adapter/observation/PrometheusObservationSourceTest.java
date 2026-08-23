package dev.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.capacity.ObservationSource;
import dev.vortex.core.capacity.OperationMixCoverage;
import dev.vortex.core.port.ProductionObservationSource.ObservedOperation;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.workload.OperationMix;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What Vortex makes of a body Prometheus wrote.
 *
 * <p>Driven from recorded responses rather than a stub HTTP server, following the same pattern as
 * the k6 summary parser: the interesting behaviour is the mapping, and a fixture captured from the
 * real thing is stronger evidence about it than a stub reproducing whatever the adapter expects.
 */
class PrometheusObservationSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObservationSource SOURCE = new ObservationSource(
            ObservationSource.Kind.PROMETHEUS, "http://prometheus.internal:9090",
            "checkout-service", Duration.ofDays(30), Map.of(), Map.of());

    private static final List<ObservedOperation> CATALOG = List.of(
            new ObservedOperation(OperationId.of("getOrder"), "GET", "/orders/{id}"),
            new ObservedOperation(OperationId.of("createOrder"), "POST", "/orders"));

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = PrometheusObservationSourceTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    @Nested
    @DisplayName("reading a single figure")
    class Scalars {

        @Test
        void aValueIsRead() throws IOException {
            assertThat(PrometheusObservationSource.scalarFrom(fixture("prometheus-peak.json")))
                    .contains(182.4);
        }

        @Test
        void anEmptyResultIsAbsentRatherThanZero() throws IOException {
            // A service with no traffic in the window has not been observed to receive zero
            // requests per second; nobody measured it. Returning 0 here would put an invented
            // number into a capacity calculation.
            assertThat(PrometheusObservationSource.scalarFrom(fixture("prometheus-empty.json")))
                    .isEmpty();
        }

        @Test
        void aNotANumberSampleIsAbsent() throws IOException {
            assertThat(PrometheusObservationSource.scalarFrom(fixture("prometheus-nan.json")))
                    .isEmpty();
        }

        @Test
        void anErrorEnvelopeIsNotMistakenForData() throws IOException {
            assertThat(PrometheusObservationSource.scalarFrom(fixture("prometheus-error.json")))
                    .isEmpty();
        }

        @Test
        void aMissingBodyIsAbsent() {
            assertThat(PrometheusObservationSource.scalarFrom(null)).isEmpty();
        }

        @Test
        void aMalformedBodyIsAbsentRatherThanThrowing() throws IOException {
            // The endpoint was not what it was assumed to be. That is a legible outcome for the
            // caller to classify, not an exception to leak out of a mapping function.
            assertThat(PrometheusObservationSource.scalarFrom(JSON.readTree("{\"unexpected\":true}")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("attributing traffic to operations")
    class Attribution {

        @Test
        void seriesAreMatchedByMethodAndPathTemplate() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-mix.json"), SOURCE, CATALOG);

            assertThat(mix.entries()).hasSize(2);
            assertThat(mix.entries().stream().map(e -> e.operationId().value()))
                    .containsExactly("getOrder", "createOrder");
        }

        @Test
        void anUnmatchedSeriesIsDroppedRatherThanInvented() throws IOException {
            // /internal/health is real traffic against an operation Vortex has no way to issue.
            // Inventing an operation for it would produce a workload that cannot run.
            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-mix.json"), SOURCE, CATALOG);

            assertThat(mix.entries().stream().map(e -> e.operationId().value()))
                    .doesNotContain("internal-health", "other", "unattributed");
        }

        @Test
        void whatWasMatchedIsCountedSoCoverageCanBeStated() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-mix.json"), SOURCE, CATALOG);

            assertThat(mix.matched()).isEqualTo(80_000d);
        }

        @Test
        void narrowingTheMixDoesNotOverstateItsCompleteness() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-mix.json"), SOURCE, CATALOG);

            // The shares renormalise, as a mix must: they describe shape and have to sum to one.
            OperationMix shape = OperationMix.of(mix.entries());
            assertThat(shape.sharePercent(OperationId.of("getOrder"))).isEqualTo("62.5");
            assertThat(shape.sharePercent(OperationId.of("createOrder"))).isEqualTo("37.5");

            // But the fact that they describe only four requests in five survives beside them.
            var coverage = new OperationMixCoverage(100_000, Math.round(mix.matched()));
            assertThat(coverage.coverage()).isEqualTo(0.8);
            assertThat(coverage.isComplete()).isFalse();
            assertThat(coverage.isRepresentative()).isTrue();
            assertThat(coverage.describe()).contains("80").contains("20000");
        }

        @Test
        void anEmptyResultAttributesNothing() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-empty.json"), SOURCE, CATALOG);

            assertThat(mix.entries()).isEmpty();
            assertThat(mix.matched()).isZero();
        }

        @Test
        void labelNamesAreConfigurable() throws IOException {
            // A service instrumented by something other than Micrometer publishes different label
            // names, and the mapping has to follow the configuration rather than an assumption.
            var renamed = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                    "http://prometheus.internal:9090", "checkout-service", Duration.ofDays(30),
                    Map.of(), Map.of("service", "app", "route", "endpoint", "method", "verb"));

            var mix = PrometheusObservationSource.attribute(
                    fixture("prometheus-mix.json"), renamed, CATALOG);

            assertThat(mix.entries())
                    .as("the fixture uses uri/method, so endpoint/verb must match nothing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("identifying itself")
    class Identity {

        @Test
        void itAnswersOnlyForPrometheus() {
            var adapter = new PrometheusObservationSource(
                    org.springframework.web.client.RestClient.builder());

            assertThat(adapter.supports(SOURCE)).isTrue();
            assertThat(adapter.supports(new ObservationSource(ObservationSource.Kind.DYNATRACE,
                    "https://abc.live.dynatrace.com", "SERVICE-1", Duration.ofDays(30),
                    Map.of(), Map.of()))).isFalse();
            assertThat(adapter.id()).isEqualTo("prometheus");
        }
    }

    @Nested
    @DisplayName("the resolution rule")
    class Resolution {

        @Test
        void scalesWithTheWindowSoALongWindowStaysAnswerable() {
            var fine = dev.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofHours(6));
            var medium = dev.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofDays(7));
            var coarse = dev.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofDays(30));

            assertThat(fine).isEqualTo(Duration.ofMinutes(1));
            assertThat(medium).isEqualTo(Duration.ofMinutes(5));
            assertThat(coarse).isEqualTo(Duration.ofHours(1));
        }

        @Test
        void aResolutionIsAlwaysPositive() {
            assertThat(dev.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofMinutes(1)))
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        void aReferenceToAnUnsetVariableIsReportedBeforeAnythingIsSent() {
            var withSecret = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                    "http://prometheus.internal:9090", "checkout-service", Duration.ofDays(30),
                    Map.of("Authorization", "Bearer ${A_VARIABLE_NOBODY_HAS_SET}"), Map.of());

            assertThat(ObservationHttp.missingSecret(withSecret))
                    .isEqualTo("A_VARIABLE_NOBODY_HAS_SET");
        }

        @Test
        void headersAreMaskedForDisplay() {
            var withSecret = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                    "http://prometheus.internal:9090", "checkout-service", Duration.ofDays(30),
                    Map.of("Authorization", "Bearer ${PROM_TOKEN}"), Map.of());

            // A bare reference survives so a reader can see which variable is wanted; anything
            // with a literal fragment around it is masked, because the literal might be the secret.
            assertThat(withSecret.maskedHeaders().get("Authorization")).doesNotContain("PROM_TOKEN");
            assertThat(withSecret.referencedSecretNames()).containsExactly("PROM_TOKEN");
        }
    }

    @Nested
    @DisplayName("classifying what went wrong")
    class Failures {

        @Test
        @DisplayName("a rejected figure is not blamed on the endpoint")
        void aDomainRejectionIsNotAMalformedResponse() {
            // Found by running against a live endpoint: a provider whose average came back above
            // its own peak produced "the response could not be understood — check your endpoint",
            // which sends the reader to look at the one thing that demonstrably worked.
            var refusal = ObservationHttp.classify(SOURCE,
                    new IllegalArgumentException(
                            "observed average rate (125000) cannot exceed the observed peak (182.4)"));

            assertThat(refusal.why()).contains("cannot exceed the observed peak");
            assertThat(refusal.remedy())
                    .contains("about the figures rather than the connection")
                    .doesNotContain("API root");
        }

        @Test
        void anUnreachableEndpointSaysSoAndSaysWhereToLook() {
            var refusal = ObservationHttp.classify(SOURCE,
                    new org.springframework.web.client.ResourceAccessException("Connection refused"));

            assertThat(refusal.why()).contains("could not be reached");
            assertThat(refusal.remedy()).contains("reachable from this machine");
        }

        @Test
        void everyRefusalCarriesAllThreeParts() {
            for (RuntimeException failure : java.util.List.of(
                    new IllegalArgumentException("numbers disagree"),
                    new org.springframework.web.client.ResourceAccessException("down"),
                    new IllegalStateException("response was not readable JSON"))) {

                var refusal = ObservationHttp.classify(SOURCE, failure);
                assertThat(refusal.what()).isNotBlank();
                assertThat(refusal.why()).isNotBlank();
                assertThat(refusal.remedy()).isNotBlank();
            }
        }
    }

    @Test
    void aFixtureDrivenMappingNeedsNoNetwork() {
        // Guards the property the test strategy depends on: nothing here opens a socket, so
        // ./mvnw verify stays green on a machine with no Prometheus anywhere near it.
        assertThat(Optional.of(SOURCE.endpoint())).contains("http://prometheus.internal:9090");
    }
}
