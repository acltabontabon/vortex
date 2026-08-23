package dev.vortex.core.metrics;

import java.util.Objects;

/**
 * One measurement that was asked for and not obtained, and why.
 *
 * <p>Carried alongside the observations rather than instead of them: a provider that answered for
 * eleven metrics and failed on the twelfth has given Vortex eleven real measurements and one honest
 * gap, and neither should displace the other.
 *
 * @param providerId  which provider was asked, matching {@code ObservabilityProvider.id()}
 * @param metricName  the measurement, in that provider's own naming
 * @param availability why it is missing
 * @param detail      what the provider actually said, when it said anything useful
 */
public record TelemetryGap(String providerId, String metricName,
        TelemetryAvailability availability, String detail) {

    public TelemetryGap {
        Objects.requireNonNull(availability, "availability");
        providerId = providerId == null ? "" : providerId.trim();
        metricName = metricName == null ? "" : metricName.trim();
        detail = detail == null ? "" : detail.trim();
        if (availability.isAvailable()) {
            throw new IllegalArgumentException(
                    "a gap describes something that is missing; " + metricName
                            + " was reported as available");
        }
    }

    public static TelemetryGap of(String providerId, String metricName,
            TelemetryAvailability availability) {
        return new TelemetryGap(providerId, metricName, availability, "");
    }

    /**
     * The gap in one sentence, naming the metric and the cause.
     *
     * <p>Names the metric first because that is what the reader was looking for when they noticed it
     * was not there.
     */
    public String describe() {
        String base = (metricName.isBlank() ? "A measurement" : metricName)
                + " was not observed";
        String from = providerId.isBlank() ? "" : " from " + providerId;
        String because = availability.explanation().isBlank()
                ? ""
                : " " + availability.explanation();
        String said = detail.isBlank() ? "" : " (" + detail + ")";
        return base + from + ":" + because + said;
    }
}
