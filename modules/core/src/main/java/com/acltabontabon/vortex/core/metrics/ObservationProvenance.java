package com.acltabontabon.vortex.core.metrics;

import java.util.Optional;

/**
 * How a provider was asked for a measurement, and where a reader can go to check it.
 *
 * <p>A number lifted out of an observability platform and pasted into a report is only as good as
 * the question that produced it. "Database connection utilisation reached 94%" invites an obvious
 * follow-up — <em>utilisation of what, measured how, over which window?</em> — and a report that
 * cannot answer it is asking to be taken on trust.
 *
 * <p>Provider-neutral, deliberately. A PromQL expression, a Dynatrace metric selector with its
 * entity, and an Actuator metric name are all just "the query that was issued" as far as a renderer
 * is concerned. Nothing here names a vendor, which is what lets the reporting layer stay ignorant of
 * where evidence came from while still being able to attribute it.
 *
 * @param providerId which provider answered, matching {@code ObservabilityProvider.id()}
 * @param query      the selector or expression as issued, verbatim
 * @param entityId   the entity, service or instance the query was scoped to; empty when not scoped
 * @param sourceUrl  a link a reader can follow to the source; empty when the provider offers none
 */
public record ObservationProvenance(
        String providerId,
        String query,
        String entityId,
        String sourceUrl) {

    public ObservationProvenance {
        providerId = providerId == null ? "" : providerId.trim();
        query = query == null ? "" : query.trim();
        entityId = entityId == null ? "" : entityId.trim();
        sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
    }

    public static ObservationProvenance of(String providerId, String query) {
        return new ObservationProvenance(providerId, query, "", "");
    }

    public static ObservationProvenance of(String providerId, String query, String sourceUrl) {
        return new ObservationProvenance(providerId, query, "", sourceUrl);
    }

    /** Whether this carries anything worth showing. */
    public boolean isEmpty() {
        return providerId.isEmpty() && query.isEmpty() && entityId.isEmpty() && sourceUrl.isEmpty();
    }

    public Optional<String> sourceUrlIfPresent() {
        return sourceUrl.isEmpty() ? Optional.empty() : Optional.of(sourceUrl);
    }

    public Optional<String> entityIdIfPresent() {
        return entityId.isEmpty() ? Optional.empty() : Optional.of(entityId);
    }
}
