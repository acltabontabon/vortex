package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.environment.SecretReferences;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which monitoring system to ask about production traffic, and about which service.
 *
 * <p>Committed to {@code vortex.yaml} like the rest of a project's configuration, because a
 * calibration that can only be reproduced on the machine that first ran it is not reproducible.
 * Credentials are the exception: header values carry {@code ${NAME}} references and are resolved
 * only at the moment a request is issued, exactly as a target's authorisation header already works.
 *
 * <p>Two kinds, not a plugin framework. Prometheus and Dynatrace are systems teams actually run, and
 * two real adapters written against them are worth more than an extension point with two names
 * attached to it. A third becomes worth building when somebody has one and needs it.
 *
 * @param kind              which system answers
 * @param endpoint          its base URL
 * @param serviceIdentifier what the system calls this service — a label value for Prometheus, an
 *                          entity id for Dynatrace. Its meaning is the adapter's business
 * @param window            how far back to look
 * @param headers           request headers, whose values may be {@code ${NAME}} references
 * @param labels            Prometheus label names for service, route and method. Ignored by
 *                          Dynatrace, whose entity model needs no such mapping — the abstraction is
 *                          allowed to be asymmetric where the systems genuinely are
 */
public record ObservationSource(
        Kind kind,
        String endpoint,
        String serviceIdentifier,
        Duration window,
        Map<String, String> headers,
        Map<String, String> labels) {

    /** Which monitoring system answers. */
    public enum Kind {
        PROMETHEUS("Prometheus"),
        DYNATRACE("Dynatrace");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Label names a Micrometer-instrumented Spring service publishes by default. */
    public static final Map<String, String> DEFAULT_LABELS =
            Map.of("service", "application", "route", "uri", "method", "method");

    public ObservationSource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(window, "window");
        endpoint = endpoint == null ? "" : endpoint.trim();
        serviceIdentifier = serviceIdentifier == null ? "" : serviceIdentifier.trim();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        labels = labels == null || labels.isEmpty() ? DEFAULT_LABELS : Map.copyOf(labels);

        if (endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "an observation source needs an endpoint — the base URL of the " + kind.label()
                            + " that can answer questions about this service");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "observation source endpoint must be an absolute http or https URL but was: "
                            + endpoint);
        }
        if (serviceIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "an observation source needs to know which service to ask about. For "
                            + kind.label() + " that is "
                            + (kind == Kind.PROMETHEUS
                            ? "the value of the service label, e.g. 'checkout-service'"
                            : "the entity id, e.g. 'SERVICE-1A2B3C4D5E6F7890'"));
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "an observation window of " + window + " covers no traffic. Give a period the "
                            + "service was actually receiving requests over, e.g. 30d");
        }
    }

    /** The label name to use for one of {@code service}, {@code route} or {@code method}. */
    public String label(String role) {
        return labels.getOrDefault(role, DEFAULT_LABELS.getOrDefault(role, role));
    }

    /**
     * Every environment variable this source's headers refer to.
     *
     * <p>Names only. Resolving them is the adapter's job, at the moment it issues a request, and the
     * resolved value never comes back here.
     */
    public Set<String> referencedSecretNames() {
        Set<String> names = new TreeSet<>();
        for (String value : headers.values()) {
            names.addAll(SecretReferences.referencedNames(value));
        }
        return names;
    }

    /** Headers with any embedded secret masked, for display and for anything persisted. */
    public Map<String, String> maskedHeaders() {
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((name, value) -> masked.put(name, SecretReferences.mask(value)));
        return masked;
    }

    public String describe() {
        return kind.label() + " at " + endpoint + ", over the last "
                + com.acltabontabon.vortex.core.threshold.Durations.display(window);
    }
}
