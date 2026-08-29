package com.acltabontabon.vortex.core.discovery;

import java.util.Objects;

/**
 * A field where a service's already-saved configuration disagrees with what Discovery found.
 *
 * <p>The proposal keeps its discovered value regardless — a conflict is something to show the
 * reviewer, not a reason to withhold the option. The default choice on review is always "keep
 * existing"; see {@code docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc}.
 */
public record DiscoveryConflict(ConflictField field, String existingDescription,
        String discoveredDescription) {

    public DiscoveryConflict {
        Objects.requireNonNull(field, "field");
        existingDescription = existingDescription == null ? "" : existingDescription;
        discoveredDescription = discoveredDescription == null ? "" : discoveredDescription;
    }
}
