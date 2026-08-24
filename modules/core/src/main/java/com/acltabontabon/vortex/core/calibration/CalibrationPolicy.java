package com.acltabontabon.vortex.core.calibration;

import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns observed production traffic into proposed workloads.
 *
 * <p>This is the arithmetic half of production-informed testing, and it is deliberately ordinary
 * code. Given an observed peak of 120 requests/sec and a forecast policy of 1.5×, the forecast
 * workload is 180 requests/sec, rounded by a stated rule. A language model is not asked to perform
 * that multiplication — not because it could not, but because a number that might come out
 * differently on a second attempt has no place in capacity planning.
 *
 * <p>What the assistant contributes is the sentence afterwards: "the forecast workload gives roughly
 * 1.5× headroom over your observed peak, which is a common target for services with seasonal
 * traffic". That is interpretation, and interpretation is what a model is good at.
 *
 * <h2>What is proposed</h2>
 * <ul>
 *   <li><strong>Average load</strong> — the observed p95 rate, or the average when no p95 is known.
 *       Representative traffic, not the busiest minute of the year.</li>
 *   <li><strong>Peak</strong> — the observed peak, unchanged, as a stress test. It is already above
 *       what the service normally handles, which is what a stress test asks about.</li>
 *   <li><strong>Forecast</strong> — {@value #FORECAST_MULTIPLIER}× the observed peak, giving room for
 *       growth between now and the next time anyone measures.</li>
 *   <li><strong>Breakpoint</strong> — a ramp to {@value #STRESS_MULTIPLIER}× the observed peak, high
 *       enough that a healthy service is pushed past its objectives and the limit lands inside the
 *       tested range rather than beyond it.</li>
 * </ul>
 *
 * <p>Every suggestion carries a {@link WorkloadSource} recording that it came from an observation and
 * which one, so a derived number is never later mistaken for a measured one.
 *
 * <h2>Rounding</h2>
 * Suggestions are rounded to a readable step — whole numbers below 10/sec, multiples of 5 below
 * 100/sec, multiples of 10 above — because a proposal of "180.75 requests/sec" invites false
 * precision about a figure that is an estimate to begin with.
 */
public final class CalibrationPolicy {

    public static final BigDecimal FORECAST_MULTIPLIER = BigDecimal.valueOf(1.5);
    public static final BigDecimal STRESS_MULTIPLIER = BigDecimal.valueOf(3);

    public static final Duration AVERAGE_LOAD_DURATION = Duration.ofMinutes(10);
    public static final Duration PEAK_DURATION = Duration.ofMinutes(15);
    public static final Duration BREAKPOINT_STAGE_DURATION = Duration.ofMinutes(5);
    public static final int BREAKPOINT_STAGE_COUNT = 4;

    public List<WorkloadSuggestion> propose(ProductionObservation observation) {
        Objects.requireNonNull(observation, "observation");

        RequestsPerSecond peak = observation.peakRate();
        RequestsPerSecond representativeSource = observation.representativeRate();

        RequestsPerSecond averageLoad = round(representativeSource.value());
        RequestsPerSecond peakSuggestion = round(peak.value());
        RequestsPerSecond forecast = round(peak.value().multiply(FORECAST_MULTIPLIER));
        RequestsPerSecond ceiling = round(peak.value().multiply(STRESS_MULTIPLIER));

        String where = observation.source();
        var when = observation.observation();

        // "p95" alone would be read as a latency percentile by anyone who has just been looking at
        // this project's objectives. It is a rate here, and the derivation has to say so.
        String representativeLabel = observation.p95ObservedRateIfPresent().isPresent()
                ? "95th-percentile request rate"
                : "average request rate";

        String caveat = coverageCaveat(observation);

        List<WorkloadSuggestion> suggestions = new ArrayList<>();

        suggestions.add(new WorkloadSuggestion(TestType.AVERAGE_LOAD, "average-load",
                "The traffic the service receives on an ordinary day.",
                averageLoad, List.of(), AVERAGE_LOAD_DURATION,
                WorkloadSource.observed(where, when).withDerivation(
                        "Your observed " + representativeLabel + " of "
                                + representativeSource.display() + " requests/sec, rounded to "
                                + averageLoad.display() + "." + caveat)));

        suggestions.add(new WorkloadSuggestion(TestType.STRESS, "production-peak",
                "The busiest traffic the service has actually been seen to receive.",
                peakSuggestion, List.of(), PEAK_DURATION,
                WorkloadSource.observed(where, when).withDerivation(
                        "Your observed peak of " + peak.display() + " requests/sec, unchanged. Above "
                                + "normal traffic by definition, which is the question a stress test "
                                + "asks." + caveat)));

        suggestions.add(new WorkloadSuggestion(TestType.STRESS, "forecast",
                "Headroom above today's peak, held steady, for growth you have not seen yet.",
                forecast, List.of(), PEAK_DURATION,
                WorkloadSource.derived(where, when,
                        "Your observed peak of " + peak.display() + " × "
                                + FORECAST_MULTIPLIER.toPlainString() + " = "
                                + peak.value().multiply(FORECAST_MULTIPLIER)
                                .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                                + ", rounded to " + forecast.display() + ", held steady rather than "
                                + "ramped, giving room for growth."
                                + caveat)));

        List<RequestsPerSecond> stages = new ArrayList<>();
        for (int stage = 1; stage <= BREAKPOINT_STAGE_COUNT; stage++) {
            BigDecimal fraction = BigDecimal.valueOf(stage)
                    .divide(BigDecimal.valueOf(BREAKPOINT_STAGE_COUNT), 4, RoundingMode.HALF_UP);
            stages.add(round(ceiling.value().multiply(fraction)));
        }
        suggestions.add(new WorkloadSuggestion(TestType.BREAKPOINT, "capacity",
                "Where the service stops meeting its objectives.",
                ceiling, stages, BREAKPOINT_STAGE_DURATION.multipliedBy(BREAKPOINT_STAGE_COUNT),
                WorkloadSource.derived(where, when,
                        "Ramps to " + STRESS_MULTIPLIER.toPlainString() + "× your observed peak ("
                                + ceiling.display() + " requests/sec) in " + BREAKPOINT_STAGE_COUNT
                                + " stages, so the point where objectives stop being met falls inside "
                                + "the range tested rather than beyond it." + caveat)));

        return suggestions;
    }

    /**
     * The sentence appended to every derivation when the mix describes less than all of production.
     *
     * <p>Attached to the derivation rather than shown once beside the proposals, because the
     * derivation is what survives onto the workload and into the run. A caveat that lives only on
     * the review screen stops existing the moment somebody clicks past it.
     */
    private String coverageCaveat(ProductionObservation observation) {
        return observation.mixCoverageIfPresent()
                .filter(coverage -> !coverage.isComplete())
                .map(coverage -> " Note that the observed mix accounts for " + coverage.display()
                        + "% of production traffic, so this describes part of what the service "
                        + "receives rather than all of it.")
                .orElse("");
    }

    /**
     * Rounds a proposed rate to a readable step.
     *
     * <p>Never rounds down to zero: a suggestion of 0 requests/sec is not a suggestion.
     */
    RequestsPerSecond round(BigDecimal rate) {
        double value = rate.doubleValue();
        BigDecimal step;
        if (value < 10) {
            step = BigDecimal.ONE;
        } else if (value < 100) {
            step = BigDecimal.valueOf(5);
        } else if (value < 1000) {
            step = BigDecimal.TEN;
        } else {
            step = BigDecimal.valueOf(50);
        }
        BigDecimal rounded = rate.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        if (rounded.signum() <= 0) {
            rounded = step;
        }
        return new RequestsPerSecond(rounded);
    }
}
