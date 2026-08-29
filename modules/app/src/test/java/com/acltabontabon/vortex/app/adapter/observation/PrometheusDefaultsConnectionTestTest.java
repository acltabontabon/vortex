package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link PrometheusDefaultsConnectionTest} at the HTTP/status-code level, the same {@link
 * MockRestServiceServer} approach {@code RestClientPrometheusClientTest} already uses.
 */
class PrometheusDefaultsConnectionTestTest {

    private static final String ENDPOINT = "http://prometheus.test";

    private MockRestServiceServer server;
    private PrometheusDefaultsConnectionTest connectionTest;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connectionTest = new PrometheusDefaultsConnectionTest(builder.build());
    }

    @Test
    void aSuccessfulVectorQueryIsConnected() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1700000000,"1"]}
                        ]}}""", MediaType.APPLICATION_JSON));

        var result = connectionTest.test(ENDPOINT, Map.of());

        assertThat(result).isInstanceOf(PrometheusDefaultsConnectionTest.Connected.class);
    }

    @Test
    void aMissingSecretIsReportedBeforeAnyRequest() {
        setUp();
        var result = connectionTest.test(ENDPOINT, Map.of("Authorization", "Bearer ${NOT_SET_ENV_VAR}"));

        assertThat(result).isInstanceOf(PrometheusDefaultsConnectionTest.Failed.class);
        var failed = (PrometheusDefaultsConnectionTest.Failed) result;
        assertThat(failed.kind()).isEqualTo(NotRetrieved.Kind.AUTHENTICATION_FAILED);
        server.verify(); // no request was issued
    }

    @Test
    void aRejectedCredentialIsAuthenticationFailed() {
        setUp();
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var result = connectionTest.test(ENDPOINT, Map.of());

        var failed = (PrometheusDefaultsConnectionTest.Failed) result;
        assertThat(failed.kind()).isEqualTo(NotRetrieved.Kind.AUTHENTICATION_FAILED);
    }

    @Test
    void aConnectionFailureIsUnreachable() {
        setUp();
        // The client's own bounded retry (3 attempts) fires for a connection failure — register one
        // failure per attempt, matching RestClientPrometheusClientTest's identical pattern.
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        server.expect(method(HttpMethod.GET)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        var result = connectionTest.test(ENDPOINT, Map.of());

        var failed = (PrometheusDefaultsConnectionTest.Failed) result;
        assertThat(failed.kind()).isEqualTo(NotRetrieved.Kind.UNREACHABLE);
    }

    @Test
    void aPrometheusSideEvaluationErrorIsInvalidResponse() {
        setUp();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"error","errorType":"bad_data","error":"parse error"}""",
                        MediaType.APPLICATION_JSON));

        var result = connectionTest.test(ENDPOINT, Map.of());

        var failed = (PrometheusDefaultsConnectionTest.Failed) result;
        assertThat(failed.kind()).isEqualTo(NotRetrieved.Kind.INVALID_RESPONSE);
        assertThat(failed.message()).contains("bad_data").contains("parse error");
    }
}
