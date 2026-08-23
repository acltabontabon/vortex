package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The level at which a typed resource reached the limit it was measured against.
 *
 * <h2>CPU is not privileged</h2>
 * The resource limit is whichever declared signal reached its limit first — not whichever one Vortex
 * happened to be able to see. A run with CPU telemetry and nothing else may not conclude that CPU was
 * the constraint merely because CPU is the only thing it observed, and the statuses below exist so
 * that "nothing reached a limit" and "nothing was measured" cannot be reported as the same sentence.
 *
 * @param kind      what sort of resource, where one was reached
 * @param signalId  the measurement behind it, so a reader can resolve it
 * @param observed  what was measured but did not reach a limit, named so the sentence is useful
 */
public record ResourceLimitFinding(Status status, LoadLevel level, ResourceKind kind,
                                   String signalId, String display, EvidenceStrength strength,
                                   List<String> observed) {

    public enum Status {

        /** A signal scoped to the service reached its declared limit. */
        REACHED("Reached"),

        /** No provider classified anything as a typed resource. Nobody looked. */
        NO_TYPED_RESOURCE_TELEMETRY("No typed resource telemetry"),

        /**
         * Resources were classified, and none of them published a limit to compare against.
         *
         * <p>Distinct from the case below: a heap measured in bytes with no maximum is not a heap
         * that stayed clear of its maximum.
         */
        NO_LIMITS_PUBLISHED("No limits published"),

        /** Resources were classified, limits were known, and none was reached. */
        NONE_REACHED_ITS_LIMIT("None reached its limit");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ResourceLimitFinding {
        Objects.requireNonNull(status, "status");
        strength = strength == null ? EvidenceStrength.INSUFFICIENT : strength;
        observed = observed == null ? List.of() : List.copyOf(observed);
    }

    public static ResourceLimitFinding notObserved(Status status, List<String> observed) {
        return new ResourceLimitFinding(status, null, null, null, null,
                EvidenceStrength.INSUFFICIENT, observed);
    }

    public boolean wasReached() {
        return status == Status.REACHED;
    }

    public Optional<LoadLevel> levelIfPresent() {
        return wasReached() ? Optional.ofNullable(level) : Optional.empty();
    }

    /**
     * The sentence a report prints, including when nothing was found.
     *
     * <p>The negative form names what <em>was</em> observed and what was not collected, because that
     * doubles as instructions for the next run. "No resource limit was identified" on its own tells
     * an engineer nothing they can act on.
     */
    public String describe() {
        if (wasReached()) {
            return kind.label() + " reached its declared limit at " + level.displayWithUnit()
                    + " (" + display + ").";
        }
        String seen = observed.isEmpty()
                ? "no resource signals were classified"
                : String.join(", ", observed) + " " + (observed.size() == 1 ? "was" : "were")
                        + " observed and neither reached a declared limit";
        return "No resource limit can be identified from the available telemetry: " + seen + ".";
    }
}
