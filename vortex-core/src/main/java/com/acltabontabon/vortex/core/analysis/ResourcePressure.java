package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;

/**
 * When a measured resource is close enough to its limit to be worth a reader's attention.
 *
 * <h2>Why this is one rule</h2>
 * Two places need it: the finding detector, which reports it in a run's narrative, and the capacity
 * observation, which records constraint candidates alongside the boundary. If they each held their
 * own threshold they would eventually disagree, and a report would name a candidate the stored
 * evidence did not — or worse, the other way round.
 *
 * <h2>What changed, and why the old rule had to go</h2>
 * This used to decide that a signal was near its limit when its unit was {@code PERCENT} and its
 * value was at least ninety. That was a rule about a unit, not about a resource: an error rate of
 * 92%, a cache hit ratio of 95% and a percentile expressed as a percentage all satisfied it, while a
 * heap at 3.9 GB of 4 GB did not, however obviously exhausted. It now compares a measurement against
 * a limit somebody declared, which is what the heuristic was approximating all along.
 *
 * <p>There is deliberately no overload taking a plain {@code MetricObservation}. An unclassified
 * measurement is still collected, still aligned to stages, still cited, exported and rendered — it
 * simply cannot become a limiting-resource statement, and the way to guarantee that is to make it
 * impossible to ask. Nothing gets promoted by looking like a resource.
 *
 * <h2>What it is not</h2>
 * A resource near its limit at the same load as a degradation has been <em>correlated</em> with it.
 * Nothing here establishes cause, and no caller may phrase it as though it did: a connection pool at
 * 98% might be the constraint, might be a symptom of a slow dependency downstream of it, or might be
 * unrelated to the latency that happened alongside it. Distinguishing those needs context a load
 * test does not contain.
 */
public final class ResourcePressure {

    /**
     * Fraction of a declared limit at or above which a resource counts as under pressure.
     *
     * <p>Ninety percent is a judgement rather than a law, and it is stated here so it can be argued
     * with rather than discovered inside a conditional. What changed is what it is ninety percent
     * <em>of</em>: the limit the provider published, rather than the number one hundred.
     */
    public static final double NEAR_LIMIT_FRACTION = 0.90;

    private ResourcePressure() {
    }

    /** Whether this signal is close enough to its declared limit to be reported as pressure. */
    public static boolean isUnderPressure(ResourceSignal signal) {
        return signal != null && signal.isNearItsLimit(NEAR_LIMIT_FRACTION);
    }

    /**
     * Whether this signal may be named as a constraint on the <em>service</em>.
     *
     * <p>Stricter than {@link #isUnderPressure}: a load generator pinned at its own CPU limit is
     * under pressure and is emphatically not a statement about the service under test.
     */
    public static boolean constrainsTheServiceUnderTest(ResourceSignal signal) {
        return isUnderPressure(signal) && signal.canEstablishAServiceLimit();
    }

    /**
     * How strongly the evidence supports treating a pressured signal as a constraint candidate.
     *
     * <p>Three inputs, and one of them is about Vortex rather than about the service. A signal whose
     * movement across the run was captured says more than a bare peak; a signal that crossed at the
     * same level of load as a degradation says more than one that merely crossed. But a stage
     * alignment Vortex <em>computed</em> from planned durations is its own arithmetic, not
     * independent corroboration — so it never raises the answer. Letting it would manufacture
     * confidence out of timestamps Vortex generated itself.
     *
     * @param hasTrace          whether the signal's movement across the window was captured
     * @param crossedAtTheLimit whether it crossed in the same stage that first violated an objective
     * @param basis             how that stage's boundaries were established
     */
    public static EvidenceStrength strength(boolean hasTrace, boolean crossedAtTheLimit,
            StageWindowBasis basis) {

        if (crossedAtTheLimit && hasTrace && basis != null && basis.canStrengthenAFinding()) {
            return EvidenceStrength.HIGH;
        }
        return hasTrace ? EvidenceStrength.MEDIUM : EvidenceStrength.LOW;
    }
}
