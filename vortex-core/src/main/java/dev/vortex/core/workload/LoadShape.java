package dev.vortex.core.workload;

import dev.vortex.core.shared.LoadLevel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How much load to generate, in what shape, and for how long.
 *
 * <p>A workload carries a single <em>total</em> level. It never carries per-operation levels: for an
 * open workload the split across an operation mix is computed by {@link RateAllocator}, which is the
 * only way an {@link AllocatedRate} can come into existence.
 *
 * <p>Four shapes, from two independent choices — {@link WorkloadModel} (open or closed) and whether
 * the level is held or moved. Nothing about the kind of test being run constrains that choice: a
 * soak may be either model, and a breakpoint search may ramp either quantity. The test type states
 * the question; the workload states how the traffic is produced.
 *
 * <p>The workload deliberately carries no name and no test type. Those belong to the workload that
 * owns it, because they describe the experiment rather than the traffic.
 */
public sealed interface LoadShape permits ConstantArrivalRateShape, RampingArrivalRateShape,
        ConstantConcurrencyShape, RampingConcurrencyShape {

    WorkloadModel model();

    /** Total wall-clock duration, excluding graceful stop. */
    Duration totalDuration();

    /** The level the workload begins at. For a constant workload, its only level. */
    LoadLevel startLevel();

    /** The highest level this workload will attempt. */
    LoadLevel peakLevel();

    /**
     * The workload expressed as stages, so that every shape can be analysed uniformly.
     *
     * <p>A constant workload is a single stage; a ramping workload is its declared stages.
     */
    List<Stage> stages();

    default boolean isRamping() {
        return stages().size() > 1;
    }

    /**
     * Total requests this workload will attempt, when that is statically predictable.
     *
     * <p>Predictable for open workloads, because the arrival schedule depends only on the workload
     * itself. Not predictable for closed workloads: a virtual user issues its next request when the
     * previous one returns, so the total depends on the latency of the service being measured — which
     * is the thing the test exists to find out.
     */
    Optional<Long> estimatedRequests();

    /** Why a request estimate is unavailable, for display next to the omission. */
    default String requestEstimateCaveat() {
        return model().isOpen()
                ? "Assumes the service keeps up. Requests the service is too slow to accept are "
                + "reported as a shortfall against this figure rather than removed from it."
                : "Not estimated: a concurrency workload issues its next request when the previous "
                + "one returns, so the total depends on the latency this run is measuring.";
    }
}
