package com.acltabontabon.vortex.core.workload;

/**
 * How Vortex knows when a workload was holding each level.
 *
 * <h2>Why this has to be recorded rather than assumed</h2>
 * Joining what the service said about itself to what the load generator was doing at the time is the
 * whole point of stage-aligned telemetry — it is what makes "what changed as load increased"
 * answerable at all. But the join is only as good as the boundaries it uses, and there are two very
 * different ways of arriving at them.
 *
 * <p>One is measurement. The other is arithmetic over what the plan asked for, anchored at the first
 * sample the run actually produced. That second one is usually right and occasionally not: a load
 * generator that starts slowly, a stage that overruns, a machine that stalls, and the computed
 * boundary drifts from the real one while looking exactly as authoritative.
 *
 * <p>So the basis travels with the evidence, and it has one consequence beyond display: a signal
 * aligned by {@link #DERIVED_FROM_PLAN} can still be reported as correlated with a breakpoint, but it
 * never <em>strengthens</em> a finding. Overlapping timestamps that Vortex itself computed from
 * planned durations are not independent corroboration, and letting them raise confidence would
 * manufacture certainty out of arithmetic.
 */
public enum StageWindowBasis {

    /**
     * Boundaries established from the load generator's own output.
     *
     * <p>Available for concurrency-model workloads, where k6 emits {@code vus} samples that track the
     * ramp it actually performed. Reading a metric k6 already publishes needs no custom tag and no
     * custom metric, which is what makes this compatible with ADR-026.
     */
    OBSERVED("measured"),

    /**
     * Planned stage durations, anchored at the first sample the run produced.
     *
     * <p>The honest answer for arrival-rate workloads: k6 does not emit the configured target rate,
     * so only the anchor is observed and the boundaries are computed.
     */
    DERIVED_FROM_PLAN("derived from the plan");

    private final String label;

    StageWindowBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isObserved() {
        return this == OBSERVED;
    }

    /**
     * Whether alignment on this basis may raise a finding's evidence strength.
     *
     * <p>Named as a question about evidence rather than a plain {@code isObserved()} check, because
     * that is what every caller is actually asking and the reason is worth being unable to miss.
     */
    public boolean canStrengthenAFinding() {
        return this == OBSERVED;
    }
}
