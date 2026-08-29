package com.acltabontabon.vortex.app.adapter.observation;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * The smallest real check that a Prometheus defaults endpoint is reachable and authenticates —
 * issues {@code vector(1)} (always returns exactly one sample; depends on no label, service or metric
 * existing) and classifies failure the same way every other Prometheus request already does.
 *
 * <p>Never asks about a service — Settings-level defaults name no service, which is exactly what
 * distinguishes this from {@link PrometheusObservationSource#verify}. A single-stage check is
 * deliberate too: unlike Dynatrace MCP's connection test, which has three independently-failing
 * stages (a local bridge process, tool discovery, organization resolution), a Prometheus HTTP
 * endpoint has exactly one thing that can be wrong at this level.
 */
public final class PrometheusDefaultsConnectionTest {

    public sealed interface Result permits Connected, Failed {
    }

    public record Connected() implements Result {
    }

    public record Failed(NotRetrieved.Kind kind, String message) implements Result {
    }

    private final RestClient restClient;

    /** Builds the {@link RestClient} once, here — not per {@link #test}, which would call {@link
     *  ObservationHttp#client} (and so {@code builder.requestFactory(...)}) on every request and
     *  clobber whatever the builder was already configured with, exactly the trap {@link
     *  PrometheusObservationSource}'s own constructor avoids the same way. */
    public PrometheusDefaultsConnectionTest(RestClient.Builder builder) {
        this(ObservationHttp.client(builder));
    }

    /** Test seam — takes an already-built {@link RestClient} directly, bypassing {@link
     *  ObservationHttp#client}'s own {@code requestFactory(...)} call so a {@code
     *  MockRestServiceServer} bound to the same builder is not overwritten by it. */
    PrometheusDefaultsConnectionTest(RestClient restClient) {
        this.restClient = restClient;
    }

    public Result test(String endpoint, Map<String, String> headers) {
        // A throwaway ObservationSource purely to reuse ObservationHttp's header-resolution and
        // failure classification — serviceIdentifier is required by that record but never queried
        // against here.
        ObservationSource probe = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                ObservationSource.Transport.REST, endpoint, "vortex-settings-test",
                Duration.ofDays(30), headers, Map.of());

        String missing = ObservationHttp.missingSecret(probe);
        if (missing != null) {
            return new Failed(NotRetrieved.Kind.AUTHENTICATION_FAILED,
                    "the environment variable " + missing + " is not set in this shell.");
        }

        PrometheusClient client = new RestClientPrometheusClient(restClient, probe.endpoint(),
                h -> ObservationHttp.headers(probe).apply(h));
        try {
            PrometheusQueryResult result = client.query("vector(1)", Instant.now());
            if (!result.success()) {
                return new Failed(NotRetrieved.Kind.INVALID_RESPONSE,
                        result.errorType().isBlank() ? result.error()
                                : result.errorType() + ": " + result.error());
            }
            return new Connected();
        } catch (RuntimeException e) {
            NotRetrieved refusal = ObservationHttp.classify(probe, e);
            return new Failed(refusal.kind(), refusal.describe());
        }
    }
}
