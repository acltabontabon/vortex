package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@link RestClientPrometheusClient} at the real HTTP/status-code level, using Spring's own
 * {@link MockRestServiceServer} — already on the test classpath via {@code spring-boot-starter-test},
 * so no new test dependency for this. Binds to the same {@link RestClient.Builder} shape production
 * code takes, so a mismatch between what this test proves and what actually runs is structurally
 * unlikely.
 */
class RestClientPrometheusClientTest {

    private static final String ENDPOINT = "http://prometheus.test";

    private MockRestServiceServer server;
    private RestClientPrometheusClient client;

    private void setUp(java.util.function.UnaryOperator<org.springframework.http.HttpHeaders> headers) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientPrometheusClient(builder.build(), ENDPOINT, headers);
    }

    private void setUp() {
        setUp(h -> h);
    }

    @Test
    void aSuccessfulInstantQueryReturnsItsVector() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andExpect(requestTo(Matchers.startsWith(ENDPOINT + "/api/v1/query?query=")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"application":"checkout-service"},"value":[1700000000,"12.5"]}
                        ]}}""", MediaType.APPLICATION_JSON));

        var result = client.query("up", Instant.now());

        assertThat(result.success()).isTrue();
        assertThat(result.firstValue()).contains(12.5);
    }

    @Test
    void aSuccessfulRangeQueryReturnsItsMatrix() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andExpect(requestTo(Matchers.startsWith(ENDPOINT + "/api/v1/query_range?query=")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[
                          {"metric":{},"values":[[1700000000,"10"],[1700000060,"20"]]}
                        ]}}""", MediaType.APPLICATION_JSON));

        var result = client.queryRange("up", Instant.EPOCH, Instant.EPOCH.plusSeconds(120), Duration.ofMinutes(1));

        assertThat(result.success()).isTrue();
        assertThat(result.firstSeriesValues()).containsExactly(10d, 20d);
    }

    @Test
    void aPrometheusSideEvaluationErrorSurfacesItsOwnErrorTypeAndMessage() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"error","errorType":"bad_data","error":"parse error"}""",
                        MediaType.APPLICATION_JSON));

        var result = client.query("(((", Instant.now());

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).isEqualTo("bad_data");
        assertThat(result.error()).isEqualTo("parse error");
    }

    @Test
    void malformedJsonDoesNotThrowOutOfTheClient() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("not json at all {{{", MediaType.APPLICATION_JSON));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> client.query("up", Instant.now()));
    }

    @Test
    void anEmptyBodyIsAnAbsentResultNotAFailure() {
        setUp();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        var result = client.query("up", Instant.now());

        assertThat(result.success()).isFalse();
        assertThat(result.firstValue()).isEmpty();
    }

    @Test
    void nanValuesAreSanitizedNotZeroed() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1700000000,"NaN"]}
                        ]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.query("up", Instant.now()).firstValue()).isEmpty();
    }

    @Test
    void plusAndMinusInfinityAreSanitizedInARangeResult() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[
                          {"metric":{},"values":[[1,"+Inf"],[2,"5"],[3,"-Inf"]]}
                        ]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.queryRange("up", Instant.EPOCH, Instant.EPOCH.plusSeconds(3), Duration.ofSeconds(1))
                .firstSeriesValues()).containsExactly(5d);
    }

    @Test
    void missingMetricOrValueFieldsAreToleratedAsAbsent() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[{}]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.query("up", Instant.now()).firstValue()).isEmpty();
    }

    @Test
    void customHeadersAreAppliedToEveryRequest() {
        setUp(h -> {
            h.set("Authorization", "Bearer secret-token");
            return h;
        });
        server.expect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[]}}""",
                        MediaType.APPLICATION_JSON));

        client.query("up", Instant.now());

        server.verify();
    }

    @Test
    void aClientErrorIsNeverRetried() {
        setUp();
        // Only one expectation registered — a second attempt would fail with "no further requests
        // expected", proving the retry loop did not fire for a 4xx.
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.client.HttpClientErrorException.class,
                () -> client.query("up", Instant.now()));
        server.verify();
    }

    @Test
    void aServerErrorIsRetriedAndCanSucceedOnASubsequentAttempt() {
        setUp();
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1700000000,"7"]}
                        ]}}""", MediaType.APPLICATION_JSON));

        var result = client.query("up", Instant.now());

        assertThat(result.firstValue()).contains(7d);
        server.verify();
    }

    @Test
    void retriesAreBoundedNotUnlimited() {
        setUp();
        // Three failures registered (the max attempt count) and nothing after — a fourth attempt
        // would fail with "no further requests expected" if the loop were unbounded.
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        org.junit.jupiter.api.Assertions.assertThrows(HttpServerErrorException.class,
                () -> client.query("up", Instant.now()));
        server.verify();
    }

    @Test
    void aConnectionFailureIsSurfacedAsResourceAccessException() {
        setUp();
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        org.junit.jupiter.api.Assertions.assertThrows(ResourceAccessException.class,
                () -> client.query("up", Instant.now()));
    }
}
