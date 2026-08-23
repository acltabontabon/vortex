package com.acltabontabon.vortex.core.catalog;

/**
 * Where a request payload came from, and therefore how much it can be trusted.
 *
 * <p>This distinction matters more than it first appears. An OpenAPI document tells Vortex that
 * {@code POST /orders} accepts {@code {"accountId": string, "amount": number}}. It does not tell
 * Vortex which account ids exist, which amounts are within limits, or whether the same id may be
 * reused across thousands of iterations. A payload can be perfectly schema-valid and completely
 * business-invalid.
 */
public enum PayloadProvenance {

    /** Generated from the specification's schema. Syntactically valid; business validity unknown. */
    SCHEMA_GENERATED("Schema-valid — not business-validated"),

    /** Written or corrected by a person who understands the service. */
    HUMAN_AUTHORED("Authored by a person"),

    /** Supplied by a dataset feeder (CSV/JSON) at execution time. */
    DATASET_SUPPLIED("Supplied from a dataset");

    private final String label;

    PayloadProvenance(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
