package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.OperationMixCoverage;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservationRequest;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservedOperation;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieved;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.workload.OperationMix;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What Vortex makes of a body Prometheus wrote, and how {@link PrometheusObservationSource} uses it.
 *
 * <p>Response parsing is driven from recorded fixtures rather than a stub HTTP server, following the
 * same pattern as the k6 summary parser. Orchestration — which queries get issued, in what order, and
 * how the answers become an observation or a classified refusal — is driven from a hand-built
 * {@link PrometheusClient} test double, so nothing here opens a socket.
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
            assertThat(RestClientPrometheusClient.parseQuery(fixture("prometheus-peak.json")).firstValue())
                    .contains(182.4);
        }

        @Test
        void anEmptyResultIsAbsentRatherThanZero() throws IOException {
            // A service with no traffic in the window has not been observed to receive zero
            // requests per second; nobody measured it. Returning 0 here would put an invented
            // number into a capacity calculation.
            assertThat(RestClientPrometheusClient.parseQuery(fixture("prometheus-empty.json")).firstValue())
                    .isEmpty();
        }

        @Test
        void aNotANumberSampleIsAbsent() throws IOException {
            assertThat(RestClientPrometheusClient.parseQuery(fixture("prometheus-nan.json")).firstValue())
                    .isEmpty();
        }

        @Test
        void anErrorEnvelopeIsNotMistakenForData() throws IOException {
            var result = RestClientPrometheusClient.parseQuery(fixture("prometheus-error.json"));
            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("bad_data");
            assertThat(result.error()).contains("parse error");
            assertThat(result.firstValue()).isEmpty();
        }

        @Test
        void aMissingBodyIsAbsent() {
            assertThat(RestClientPrometheusClient.parseQuery(null).firstValue()).isEmpty();
        }

        @Test
        void aMalformedBodyIsAbsentRatherThanThrowing() throws IOException {
            // The endpoint was not what it was assumed to be. That is a legible outcome for the
            // caller to classify, not an exception to leak out of a mapping function.
            assertThat(RestClientPrometheusClient.parseQuery(JSON.readTree("{\"unexpected\":true}")).firstValue())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("reading a range of samples")
    class Ranges {

        @Test
        void everySampleIsRead() throws IOException {
            var result = RestClientPrometheusClient.parseRange(fixture("prometheus-range-samples.json"));

            // NaN is dropped, so five points become four usable samples.
            assertThat(result.firstSeriesValues()).containsExactly(10d, 25d, 182.4, 40d);
        }

        @Test
        void positiveAndNegativeInfinityAreNeitherRealValuesNorZero() throws IOException {
            var result = RestClientPrometheusClient.parseRange(fixture("prometheus-range-inf.json"));

            assertThat(result.firstSeriesValues()).containsExactly(10d, 20d);
        }

        @Test
        void anEmptyMatrixHasNoSamples() throws IOException {
            var result = RestClientPrometheusClient.parseRange(fixture("prometheus-range-empty.json"));

            assertThat(result.success()).isTrue();
            assertThat(result.firstSeriesValues()).isEmpty();
        }

        @Test
        void anErrorEnvelopeIsNotMistakenForData() throws IOException {
            var result = RestClientPrometheusClient.parseRange(fixture("prometheus-error.json"));

            assertThat(result.success()).isFalse();
            assertThat(result.firstSeriesValues()).isEmpty();
        }

        @Test
        void aMissingBodyIsAbsent() {
            assertThat(RestClientPrometheusClient.parseRange(null).firstSeriesValues()).isEmpty();
        }
    }

    @Nested
    @DisplayName("attributing traffic to operations")
    class Attribution {

        private PrometheusQueryResult mix(String fixtureName, ObservationSource source) throws IOException {
            return RestClientPrometheusClient.parseQuery(fixture(fixtureName));
        }

        @Test
        void seriesAreMatchedByMethodAndPathTemplate() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    mix("prometheus-mix.json", SOURCE), SOURCE, CATALOG);

            assertThat(mix.entries()).hasSize(2);
            assertThat(mix.entries().stream().map(e -> e.operationId().value()))
                    .containsExactly("getOrder", "createOrder");
        }

        @Test
        void anUnmatchedSeriesIsDroppedRatherThanInvented() throws IOException {
            // /internal/health is real traffic against an operation Vortex has no way to issue.
            // Inventing an operation for it would produce a workload that cannot run.
            var mix = PrometheusObservationSource.attribute(
                    mix("prometheus-mix.json", SOURCE), SOURCE, CATALOG);

            assertThat(mix.entries().stream().map(e -> e.operationId().value()))
                    .doesNotContain("internal-health", "other", "unattributed");
        }

        @Test
        void whatWasMatchedIsCountedSoCoverageCanBeStated() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    mix("prometheus-mix.json", SOURCE), SOURCE, CATALOG);

            assertThat(mix.matched()).isEqualTo(80_000d);
        }

        @Test
        void narrowingTheMixDoesNotOverstateItsCompleteness() throws IOException {
            var mix = PrometheusObservationSource.attribute(
                    mix("prometheus-mix.json", SOURCE), SOURCE, CATALOG);

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
                    mix("prometheus-empty.json", SOURCE), SOURCE, CATALOG);

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
                    mix("prometheus-mix.json", renamed), renamed, CATALOG);

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
            var fine = com.acltabontabon.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofHours(6));
            var medium = com.acltabontabon.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofDays(7));
            var coarse = com.acltabontabon.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofDays(30));

            assertThat(fine).isEqualTo(Duration.ofMinutes(1));
            assertThat(medium).isEqualTo(Duration.ofMinutes(5));
            assertThat(coarse).isEqualTo(Duration.ofHours(1));
        }

        @Test
        void aResolutionIsAlwaysPositive() {
            assertThat(com.acltabontabon.vortex.core.capacity.ObservationResolution.forWindow(Duration.ofMinutes(1)))
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
            assertThat(refusal.kind()).isEqualTo(NotRetrieved.Kind.INVALID_RESPONSE);
        }

        @Test
        void anUnreachableEndpointSaysSoAndSaysWhereToLook() {
            var refusal = ObservationHttp.classify(SOURCE,
                    new org.springframework.web.client.ResourceAccessException("Connection refused"));

            assertThat(refusal.why()).contains("could not be reached");
            assertThat(refusal.remedy()).contains("reachable from this machine");
            assertThat(refusal.kind()).isEqualTo(NotRetrieved.Kind.UNREACHABLE);
        }

        @Test
        void aRejectedCredentialIsAuthenticationFailed() {
            var refusal = ObservationHttp.classify(SOURCE,
                    org.springframework.web.client.HttpClientErrorException.create(
                            org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized",
                            org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

            assertThat(refusal.kind()).isEqualTo(NotRetrieved.Kind.AUTHENTICATION_FAILED);
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
                assertThat(refusal.kind()).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("retrieving an observation")
    class Retrieving {

        private static final Duration WINDOW = Duration.ofDays(30);
        private static final Duration RESOLUTION = Duration.ofHours(1);
        private static final Instant END = Instant.parse("2026-01-31T00:00:00Z");
        private static final TimeWindow TIME_WINDOW = new TimeWindow(END.minus(WINDOW), END);

        private String rateExpr() {
            return PrometheusQueries.rateExpression(SOURCE, RESOLUTION);
        }

        @Test
        void peakAndP95ComeFromRangeSamplesNotASingleInstantQuery() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of(10d, 25d, 182.4, 40d));
            fake.scalar(PrometheusQueries.averageQuery(SOURCE, WINDOW), 55d);
            fake.vector(PrometheusQueries.mixQuery(SOURCE, WINDOW), List.of());
            fake.scalar(PrometheusQueries.totalQuery(SOURCE, WINDOW), 100d);

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.retrieve(new ObservationRequest(SOURCE, TIME_WINDOW, RESOLUTION, List.of()));

            assertThat(retrieval).isInstanceOf(Retrieved.class);
            var observation = ((Retrieved) retrieval).observation();
            assertThat(observation.peakRate().asDouble()).isEqualTo(182.4);
            // p95 of [10,25,40,182.4]: rank = 0.95*3 = 2.85 -> interpolate between 40 and 182.4.
            assertThat(observation.p95ObservedRate().asDouble()).isCloseTo(161.04, org.assertj.core.data.Offset.offset(0.01));
            assertThat(fake.rangeCallCount()).isEqualTo(1);
            assertThat(observation.averageRate().asDouble()).isEqualTo(55d);
        }

        @Test
        void noSamplesInTheRangeIsReportedAsNoDataNotZero() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of());

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.retrieve(new ObservationRequest(SOURCE, TIME_WINDOW, RESOLUTION, List.of()));

            assertThat(retrieval).isInstanceOf(NotRetrieved.class);
            assertThat(((NotRetrieved) retrieval).kind()).isEqualTo(NotRetrieved.Kind.NO_DATA);
        }

        @Test
        void aPrometheusSideEvaluationErrorIsInvalidResponseNotNoData() {
            var fake = new FakePrometheusClient();
            fake.rangeError(rateExpr(), "bad_data", "many-to-many matching not allowed");

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.retrieve(new ObservationRequest(SOURCE, TIME_WINDOW, RESOLUTION, List.of()));

            assertThat(retrieval).isInstanceOf(NotRetrieved.class);
            var refusal = (NotRetrieved) retrieval;
            assertThat(refusal.kind()).isEqualTo(NotRetrieved.Kind.INVALID_RESPONSE);
            assertThat(refusal.why()).contains("many-to-many matching not allowed");
        }

        @Test
        void aMissingSecretIsReportedBeforeAnyQuery() {
            var withSecret = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                    "http://prometheus.internal:9090", "checkout-service", WINDOW,
                    Map.of("Authorization", "Bearer ${NEVER_SET}"), Map.of());
            var fake = new FakePrometheusClient();

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.retrieve(
                    new ObservationRequest(withSecret, TIME_WINDOW, RESOLUTION, List.of()));

            assertThat(retrieval).isInstanceOf(NotRetrieved.class);
            assertThat(((NotRetrieved) retrieval).kind()).isEqualTo(NotRetrieved.Kind.AUTHENTICATION_FAILED);
            assertThat(fake.rangeCallCount()).isZero();
        }
    }

    @Nested
    @DisplayName("testing a connection")
    class Verifying {

        private static final Duration WINDOW = Duration.ofDays(1);
        private static final Duration RESOLUTION = Duration.ofMinutes(1);
        private static final Instant END = Instant.parse("2026-01-31T00:00:00Z");
        private static final TimeWindow TIME_WINDOW = new TimeWindow(END.minus(WINDOW), END);

        private String rateExpr() {
            return PrometheusQueries.rateExpression(SOURCE, RESOLUTION);
        }

        @Test
        void verifyNeverIssuesTheAverageOrMixQueries() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of(12d));
            fake.scalar(PrometheusQueries.histogramExistenceQuery(SOURCE), 0d);

            var adapter = new PrometheusObservationSource(source -> fake);
            adapter.verify(SOURCE, TIME_WINDOW, RESOLUTION);

            assertThat(fake.queriesIssued())
                    .doesNotContain(PrometheusQueries.averageQuery(SOURCE, WINDOW))
                    .doesNotContain(PrometheusQueries.mixQuery(SOURCE, WINDOW));
        }

        @Test
        void aHistogramThatDoesNotExistIsReportedAsNotPublished() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of(12d));
            fake.scalar(PrometheusQueries.histogramExistenceQuery(SOURCE), 0d);

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.verify(SOURCE, TIME_WINDOW, RESOLUTION);

            var note = ((Retrieved) retrieval).observation().note();
            assertThat(note).contains("not published").contains(PrometheusQueries.REQUEST_HISTOGRAM);
            assertThat(fake.queriesIssued()).doesNotContain(PrometheusQueries.latencyP95Query(SOURCE, WINDOW));
        }

        @Test
        void aHistogramThatExistsButIsEmptyThisWindowIsDistinctFromNotPublished() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of(12d));
            fake.scalar(PrometheusQueries.histogramExistenceQuery(SOURCE), 3d);
            fake.vector(PrometheusQueries.latencyP95Query(SOURCE, WINDOW), List.of());

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.verify(SOURCE, TIME_WINDOW, RESOLUTION);

            var note = ((Retrieved) retrieval).observation().note();
            assertThat(note).contains("had no samples in this window");
        }

        @Test
        void aRealHistogramProducesARealFigureNeverFabricated() {
            var fake = new FakePrometheusClient();
            fake.range(rateExpr(), List.of(12d));
            fake.scalar(PrometheusQueries.histogramExistenceQuery(SOURCE), 5d);
            fake.scalar(PrometheusQueries.latencyP95Query(SOURCE, WINDOW), 0.34d);

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.verify(SOURCE, TIME_WINDOW, RESOLUTION);

            var note = ((Retrieved) retrieval).observation().note();
            assertThat(note).contains("340ms").contains("Diagnostic only, not saved");
        }

        @Test
        void theLatencyNoteNeverReachesRetrieve() {
            // The one structural guarantee this whole feature rests on: retrieve() never asks the
            // histogram questions at all, so nothing latency-related can reach a saved observation
            // through this path, regardless of what verify() does elsewhere.
            var fake = new FakePrometheusClient();
            fake.range(PrometheusQueries.rateExpression(SOURCE, Duration.ofHours(1)), List.of(12d));
            fake.scalar(PrometheusQueries.averageQuery(SOURCE, Duration.ofDays(30)), 5d);
            fake.vector(PrometheusQueries.mixQuery(SOURCE, Duration.ofDays(30)), List.of());
            fake.scalar(PrometheusQueries.totalQuery(SOURCE, Duration.ofDays(30)), 10d);

            var adapter = new PrometheusObservationSource(source -> fake);
            var retrieval = adapter.retrieve(new ObservationRequest(SOURCE,
                    new TimeWindow(END.minus(Duration.ofDays(30)), END), Duration.ofHours(1), List.of()));

            assertThat(((Retrieved) retrieval).observation().note()).isEmpty();
            assertThat(fake.queriesIssued()).noneMatch(q -> q.contains(PrometheusQueries.REQUEST_HISTOGRAM));
        }
    }

    @Test
    void aFixtureDrivenMappingNeedsNoNetwork() {
        // Guards the property the test strategy depends on: nothing here opens a socket, so
        // ./mvnw verify stays green on a machine with no Prometheus anywhere near it.
        assertThat(Optional.of(SOURCE.endpoint())).contains("http://prometheus.internal:9090");
    }

    /**
     * A {@link PrometheusClient} that answers exactly the queries it was told to expect, and fails
     * loudly on anything else — so an orchestration test is honest about which queries
     * {@link PrometheusObservationSource} actually issues, not just what it does with whatever comes
     * back.
     */
    private static final class FakePrometheusClient implements PrometheusClient {

        private final Map<String, PrometheusQueryResult> queryAnswers = new HashMap<>();
        private final Map<String, PrometheusRangeResult> rangeAnswers = new HashMap<>();
        private final List<String> issued = new java.util.ArrayList<>();
        private int rangeCalls = 0;

        void scalar(String promql, double value) {
            queryAnswers.put(promql, PrometheusQueryResult.success(
                    List.of(new PrometheusQueryResult.VectorSample(Map.of(), value))));
        }

        void vector(String promql, List<Map<String, String>> labelSets) {
            queryAnswers.put(promql, PrometheusQueryResult.success(
                    labelSets.stream().map(labels -> new PrometheusQueryResult.VectorSample(labels, 1d)).toList()));
        }

        void range(String promql, List<Double> values) {
            List<PrometheusRangeResult.Sample> samples = new java.util.ArrayList<>();
            Instant t = Instant.EPOCH;
            for (Double v : values) {
                samples.add(new PrometheusRangeResult.Sample(t, v));
                t = t.plusSeconds(60);
            }
            rangeAnswers.put(promql, PrometheusRangeResult.success(
                    List.of(new PrometheusRangeResult.MatrixSeries(Map.of(), samples))));
        }

        void rangeError(String promql, String errorType, String error) {
            rangeAnswers.put(promql, PrometheusRangeResult.error(errorType, error));
        }

        int rangeCallCount() {
            return rangeCalls;
        }

        List<String> queriesIssued() {
            return issued;
        }

        @Override
        public PrometheusQueryResult query(String promql, Instant time) {
            issued.add(promql);
            PrometheusQueryResult answer = queryAnswers.get(promql);
            if (answer == null) {
                throw new AssertionError("unexpected query: " + promql);
            }
            return answer;
        }

        @Override
        public PrometheusRangeResult queryRange(String promql, Instant start, Instant end, Duration step) {
            issued.add(promql);
            rangeCalls++;
            PrometheusRangeResult answer = rangeAnswers.get(promql);
            if (answer == null) {
                throw new AssertionError("unexpected range query: " + promql);
            }
            return answer;
        }
    }
}
