package com.acltabontabon.vortex.core.threshold;

/**
 * A performance objective the service is expected to meet.
 *
 * <p>Thresholds are the only thing that turns measurements into a pass or a fail. They are
 * evaluated by deterministic code — never by the AI assistant — because a verdict that could vary
 * between runs of the same data would make every downstream conclusion worthless.
 */
public sealed interface Threshold permits LatencyThreshold, ErrorRateThreshold {

    /**
     * A stable identifier for this threshold, used in reports, artifacts and evidence references.
     * Derived from the threshold's content so it survives reordering: {@code latency.p95},
     * {@code errorRate}, {@code latency.p95.createOrder}.
     */
    String id();

    /** Whether this objective applies to the whole run or to a single operation. */
    ThresholdScope scope();

    /** Plain-language statement of the objective, e.g. {@code p95 latency below 500 ms}. */
    String describe();
}
