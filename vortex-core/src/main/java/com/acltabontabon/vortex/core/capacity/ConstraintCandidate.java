package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import java.util.Objects;
import java.util.Optional;

/**
 * A resource that was close to its limit where the service stopped meeting its objectives.
 *
 * <h2>A candidate, and never a cause</h2>
 * The name is the whole point. A connection pool at 98% at the level where latency breached its
 * objective has been <em>correlated</em> with that breach. It might be the constraint. It might be a
 * symptom of a slow dependency sitting behind it, holding connections open. It might have nothing to
 * do with the latency at all and merely be busy at the same time. Separating those needs context a
 * load test does not contain — the dependency's own view, the query plans, what else shares the host.
 *
 * <p>So nothing on this type may be phrased as a cause, and {@link #describe()} exists partly so that
 * every renderer says the same careful thing rather than each inventing its own sentence.
 *
 * <h2>Why it is stored rather than recomputed</h2>
 * These used to exist only as findings, derived from a report at the moment somebody looked at it. A
 * capacity observation is history — it is compared against later runs and quoted months afterwards —
 * and evidence that is recomputed from whatever the current code believes is not history. Storing it
 * with the observation also means the report and the stored record cannot disagree.
 *
 * @param signalId       the observation it came from, so a reader can find the measurement
 * @param name           the resource, in words
 * @param display        its value where the boundary was found, e.g. {@code 98%}
 * @param strength       how strongly the evidence supports treating it as a candidate
 * @param alignmentBasis how the stage it was observed in was aligned. A computed alignment cannot
 *                       raise the strength — see {@link StageWindowBasis}
 * @param basis          what was observed, stated as correlation
 * @param kind           what sort of resource it is, where a provider classified it. Absent on
 *                       observations recorded before signals were typed — <em>unknown</em>, which is
 *                       what those rows honestly are, rather than a kind chosen retrospectively
 * @param scope          whose resource it is. Also absent on older rows; a candidate recorded now is
 *                       always scoped to the system under test, because nothing else may be one
 */
public record ConstraintCandidate(String signalId, String name, String display,
        EvidenceStrength strength, StageWindowBasis alignmentBasis, String basis,
        ResourceKind kind, ResourceScope scope) {

    public ConstraintCandidate {
        Objects.requireNonNull(strength, "strength");
        signalId = signalId == null ? "" : signalId.trim();
        name = name == null ? "" : name.trim();
        display = display == null ? "" : display.trim();
        alignmentBasis = alignmentBasis == null ? StageWindowBasis.DERIVED_FROM_PLAN : alignmentBasis;
        basis = basis == null ? "" : basis.trim();
        if (signalId.isBlank()) {
            throw new IllegalArgumentException(
                    "a constraint candidate must cite the observation it came from; a resource "
                            + "named without a measurement behind it is a guess");
        }
        if (scope != null && !scope.describesTheServiceUnderTest()) {
            throw new IllegalArgumentException(
                    "a constraint candidate describes the system under test; " + signalId
                            + " is scoped to " + scope.label() + ", and reporting it as a constraint "
                            + "on the service would attribute another system's limit to it");
        }
    }

    /** A candidate from a measurement no provider classified. */
    public ConstraintCandidate(String signalId, String name, String display,
            EvidenceStrength strength, StageWindowBasis alignmentBasis, String basis) {
        this(signalId, name, display, strength, alignmentBasis, basis, null, null);
    }

    public Optional<ResourceKind> kindIfPresent() {
        return Optional.ofNullable(kind);
    }

    public Optional<ResourceScope> scopeIfPresent() {
        return Optional.ofNullable(scope);
    }

    /**
     * The candidate as a reader should see it.
     *
     * <p>One sentence, and the second half of it is not optional. A list of resource names next to a
     * capacity figure reads as a diagnosis however carefully the surrounding prose is worded.
     */
    public String describe() {
        return name + " was at " + display + " where objectives stopped being met. "
                + "This run establishes that they coincided, not that this resource produced the "
                + "degradation.";
    }
}
