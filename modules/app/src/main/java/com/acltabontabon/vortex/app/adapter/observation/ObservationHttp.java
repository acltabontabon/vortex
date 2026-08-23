package com.acltabontabon.vortex.app.adapter.observation;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP behaviour both observation adapters share: timeouts, credentials, and honest failures.
 *
 * <p>Extracted rather than duplicated because the interesting part is not the request — it is the
 * classification of what came back. Two adapters that each invent their own wording for "your token
 * was rejected" will eventually disagree about what the engineer should do next, and the remedy is
 * the part of an error message that earns its place.
 */
final class ObservationHttp {

    /**
     * Generous relative to a live telemetry sample, because this is a different kind of request.
     *
     * <p>A thirty-day range query against a busy Prometheus is real work, and it is issued once,
     * interactively, while somebody waits. Timing it out at the five seconds that suit a five-second
     * sampling loop would fail the common case.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private ObservationHttp() {
    }

    static RestClient client(RestClient.Builder builder) {
        return builder.requestFactory(timeoutFactory()).build();
    }

    /** One mapper for the adapters, so neither depends on which converters were injected. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Reads a response body as text and parses it here.
     *
     * <p>Deliberately not {@code .body(JsonNode.class)}. That relies on a Jackson message converter
     * being registered on the injected builder, which is not something an adapter can assume — and
     * when it is missing, every query fails as "the response could not be understood", pointing the
     * reader at their monitoring system for a problem that is entirely Vortex's. The Actuator
     * provider learned the same lesson against a different content type.
     */
    static com.fasterxml.jackson.databind.JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("response was not readable JSON", e);
        }
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * Applies the configured headers, resolving {@code ${NAME}} references from the environment.
     *
     * <p>Resolution happens here and nowhere earlier. The reference is what is configured, committed
     * and displayed; the value exists only for the duration of one request and is never written back
     * into an observation, a provenance record, a log line or a prompt.
     *
     * <p>A reference naming a variable that is not set is left as written rather than replaced with
     * an empty string. The request will then fail as unauthorised, which is a far more legible
     * outcome than a silently anonymous call.
     */
    static UnaryOperator<HttpHeaders> headers(ObservationSource source) {
        return headers -> {
            for (Map.Entry<String, String> header : source.headers().entrySet()) {
                headers.set(header.getKey(), resolve(header.getValue()));
            }
            return headers;
        };
    }

    private static String resolve(String value) {
        String resolved = value;
        for (String name : SecretReferences.referencedNames(value)) {
            String fromEnvironment = System.getenv(name);
            if (fromEnvironment != null) {
                resolved = resolved.replace("${" + name + "}", fromEnvironment);
            }
        }
        return resolved;
    }

    /** Whether any header refers to an environment variable that is not set. */
    static String missingSecret(ObservationSource source) {
        for (String name : source.referencedSecretNames()) {
            if (System.getenv(name) == null) {
                return name;
            }
        }
        return null;
    }

    /**
     * Turns whatever went wrong into something the engineer can act on.
     *
     * <p>The distinctions matter because the remedies differ completely: an unreachable endpoint is
     * a network or a typo, a rejected token is a permission, and an unparseable body usually means
     * the endpoint is not the thing it was assumed to be. Collapsing them into "could not fetch"
     * would leave all three looking identical.
     */
    static NotRetrieved classify(ObservationSource source, RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        String system = source.kind().label();

        // A figure the domain refused is not a malformed response, and telling the reader to check
        // their endpoint sends them to look at the one thing that demonstrably worked. The queries
        // succeeded; the numbers that came back disagree with each other.
        if (failure instanceof IllegalArgumentException) {
            return new NotRetrieved(
                    "Could not use the traffic " + system + " reported",
                    message + ".",
                    "The queries succeeded, so this is about the figures rather than the "
                            + "connection. Check the metric Vortex asked for is the one that counts "
                            + "this service's requests, and that it is not being summed across more "
                            + "instances than the peak query saw.");
        }

        if (failure instanceof org.springframework.web.client.HttpClientErrorException.Unauthorized
                || failure instanceof org.springframework.web.client.HttpClientErrorException.Forbidden) {
            return new NotRetrieved(
                    "Could not read production traffic from " + system,
                    system + " rejected the credentials Vortex presented (" + message + ").",
                    "Check the token referenced by observation.headers is set in this shell and has "
                            + "permission to read metrics.");
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException.NotFound) {
            return new NotRetrieved(
                    "Could not read production traffic from " + system,
                    "the endpoint " + source.endpoint() + " returned 404.",
                    "Check observation.endpoint points at the API root, not at a dashboard URL.");
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException client) {
            return new NotRetrieved(
                    "Could not read production traffic from " + system,
                    system + " rejected the query: " + client.getStatusText() + ".",
                    "The query Vortex issued is shown above; try it in " + system + " directly to "
                            + "see what it objects to.");
        }
        if (failure instanceof org.springframework.web.client.HttpServerErrorException server) {
            return new NotRetrieved(
                    "Could not read production traffic from " + system,
                    system + " failed while evaluating the query (" + server.getStatusText() + ").",
                    "A long observation window can exceed a server's evaluation limits. Try a "
                            + "shorter observation.window.");
        }
        if (failure instanceof org.springframework.web.client.ResourceAccessException) {
            return new NotRetrieved(
                    "Could not read production traffic from " + system,
                    source.endpoint() + " could not be reached: " + message,
                    "Check the endpoint is correct and reachable from this machine.");
        }
        return new NotRetrieved(
                "Could not read production traffic from " + system,
                "the response could not be understood: " + message,
                "Check observation.endpoint points at a " + system + " API root.");
    }
}
