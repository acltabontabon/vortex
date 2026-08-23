package dev.vortex.core.evidence;

import dev.vortex.core.metrics.TelemetryAvailability;
import java.util.List;
import java.util.Objects;

/**
 * Which providers were consulted during a run, and what each could supply.
 *
 * <p>Two runs with different observability coverage are not equally informative, and today they are
 * compared as though they were. Recording what was asked — including of providers that answered
 * nothing — is what lets a comparison say "these differ in what was measured" rather than presenting
 * that difference as a regression.
 *
 * <p>A provider that failed is listed, not omitted. Omitting it would leave a reader believing
 * nobody had looked there.
 */
public record TelemetryCoverage(List<ProviderCoverage> providers) {

    /**
     * One provider's contribution.
     *
     * @param metricsRequested how many measurements Vortex asked for
     * @param metricsReturned  how many it got. Fewer is a fact about the service's instrumentation,
     *                         not about Vortex, and the gap is what makes coverage comparable
     */
    public record ProviderCoverage(String providerId, TelemetryAvailability availability,
                                   int metricsRequested, int metricsReturned) {

        public ProviderCoverage {
            Objects.requireNonNull(availability, "availability");
            providerId = providerId == null ? "" : providerId;
            if (metricsRequested < 0 || metricsReturned < 0) {
                throw new IllegalArgumentException("a metric count cannot be negative");
            }
        }

        public boolean answered() {
            return availability.isAvailable() && metricsReturned > 0;
        }
    }

    public TelemetryCoverage {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /** Nobody was consulted, or nobody recorded who was. */
    public static TelemetryCoverage none() {
        return new TelemetryCoverage(List.of());
    }

    /** Whether every provider asked supplied everything it was asked for. */
    public boolean isComplete() {
        return !providers.isEmpty()
                && providers.stream().allMatch(p -> p.metricsReturned() >= p.metricsRequested());
    }

    /**
     * A stable description of what was observed, for comparing two runs' coverage.
     *
     * <p>Providers that answered, sorted, so the same coverage produces the same string regardless
     * of the order they happened to be consulted in.
     */
    public String signature() {
        return providers.stream()
                .filter(ProviderCoverage::answered)
                .map(ProviderCoverage::providerId)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public List<String> providerIds() {
        return providers.stream().map(ProviderCoverage::providerId).sorted().toList();
    }
}
