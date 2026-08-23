package com.acltabontabon.vortex.core.comparison;

import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.validity.ValidityReason;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a run may be compared against, and on what.
 *
 * <p>A baseline is judged against repeatedly, so a bad one costs more than a bad run. But refusing
 * every degraded baseline would refuse almost all of them, since most services have partial
 * telemetry. Eligibility is therefore scoped to what the degradation actually undermines: an invalid
 * run is not offered at all, a valid one is unrestricted, and a degraded one stays available with
 * the specific deltas its reason codes cannot support withheld.
 *
 * <p>The table below is ADR-038's, and it lives here rather than on {@code ValidityReason} because
 * it is a statement about comparison — putting it on the enum would make the validity model depend
 * on the comparison model in order to describe itself.
 *
 * @param refusedDeltas what may not be concluded, even though the baseline is usable
 */
public record BaselineEligibility(boolean offeredAsBaseline, Set<DeltaKind> refusedDeltas,
                                  String reason) {

    public BaselineEligibility {
        refusedDeltas = refusedDeltas == null || refusedDeltas.isEmpty()
                ? Set.of() : Set.copyOf(refusedDeltas);
        reason = reason == null ? "" : reason;
    }

    public static BaselineEligibility of(RunQualityAssessment quality) {
        if (quality == null) {
            return new BaselineEligibility(true, Set.of(), "");
        }
        if (quality.isInvalid()) {
            return new BaselineEligibility(false, EnumSet.allOf(DeltaKind.class),
                    "This run did not measure what it claims to, so it is not offered as a baseline: "
                            + String.join(" ", quality.qualifications()));
        }

        Set<DeltaKind> refused = EnumSet.noneOf(DeltaKind.class);
        List<String> because = new ArrayList<>();

        for (ValidityReason reason : quality.reasons()) {
            switch (reason) {
                // Latency, throughput and reliability were measured perfectly well; only what the
                // service was doing underneath them is missing.
                case TELEMETRY_INCOMPLETE -> {
                    refused.add(DeltaKind.RESOURCE);
                    refused.add(DeltaKind.EFFICIENCY);
                    because.add("its telemetry was incomplete, so resource and efficiency "
                            + "comparisons have nothing to rest on");
                }
                // The thin stage was not a usable boundary edge, so where the boundary sat is not
                // something this run established.
                case INSUFFICIENT_SAMPLES -> {
                    refused.add(DeltaKind.CAPACITY);
                    refused.add(DeltaKind.BREAKPOINT_MOVEMENT);
                    because.add("a stage carried too few samples to be a boundary edge, so capacity "
                            + "and breakpoint movement cannot be compared against it");
                }
                case RUN_TOO_SHORT, WARM_UP_NOT_COMPLETED -> {
                    refused.add(DeltaKind.CAPACITY);
                    because.add("it did not run long enough for its capacity to be comparable");
                }
                // Correlation in time is what makes telemetry evidence at all.
                case WINDOW_MISALIGNED -> {
                    refused.add(DeltaKind.RESOURCE);
                    refused.add(DeltaKind.EFFICIENCY);
                    because.add("its telemetry did not cover the run, so nothing resting on "
                            + "correlation can be compared");
                }
                // Handled above by the INVALID branch; listed so this switch stays exhaustive and a
                // new reason code cannot slip through without a decision.
                case OFFERED_LOAD_NOT_GENERATED, GENERATOR_SATURATED, EXECUTION_INTERRUPTED,
                     TARGET_UNAVAILABLE_DURING_RUN -> {
                    refused.add(DeltaKind.CAPACITY);
                    refused.add(DeltaKind.BREAKPOINT_MOVEMENT);
                    because.add("its capacity evidence is withheld");
                }
            }
        }
        return new BaselineEligibility(true, refused,
                because.isEmpty() ? "" : "Compared with qualifications: " + String.join("; ", because)
                        + ".");
    }

    public boolean permits(DeltaKind kind) {
        return offeredAsBaseline && !refusedDeltas.contains(kind);
    }

    public boolean isUnrestricted() {
        return offeredAsBaseline && refusedDeltas.isEmpty();
    }
}
