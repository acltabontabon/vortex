package com.acltabontabon.vortex.core.recommendation;

import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.calibration.WorkloadSuggestion;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.safety.SafetyLimits;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.SpikeShapes;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recommends a workload for a test type — a default, never a restriction. Reuses
 * {@link CalibrationPolicy} verbatim for every number it already computes from a
 * {@link ProductionObservation} (Average Load; the source rate for Stress/Breakpoint); supplies its
 * own policy only for what {@code CalibrationPolicy} has no opinion about — Smoke, Soak, Spike, and
 * every no-observation fallback — so the two policies never disagree about a number both compute.
 */
public final class WorkloadRecommender {

    // No-observation fallbacks — a manual starting point has to be *some* number, and leaving one
    // unnamed is how a "recommendation" quietly becomes a UI constant. Same convention as
    // CalibrationPolicy's own named multipliers.
    private static final RequestsPerSecond SMOKE_RATE = RequestsPerSecond.of(1);
    private static final Concurrency SMOKE_VUS = Concurrency.of(1);
    private static final Duration SMOKE_DURATION = Duration.ofSeconds(30);

    private static final RequestsPerSecond MANUAL_BASELINE_RATE = RequestsPerSecond.of(10);
    private static final Concurrency MANUAL_BASELINE_VUS = Concurrency.of(10);

    private static final Duration SOAK_DURATION = Duration.ofMinutes(60);

    private static final BigDecimal SPIKE_MULTIPLIER = BigDecimal.valueOf(5);
    private static final Duration SPIKE_HOLD_BEFORE = Duration.ofSeconds(30);
    private static final Duration SPIKE_HOLD_AT_PEAK = Duration.ofMinutes(1);

    private static final int STRESS_STAGES = 3;
    private static final Duration STRESS_STAGE_DURATION = Duration.ofMinutes(5);
    private static final BigDecimal STRESS_MANUAL_MULTIPLIER = BigDecimal.valueOf(3);

    private final CalibrationPolicy calibration;

    public WorkloadRecommender(CalibrationPolicy calibration) {
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    public static WorkloadRecommender defaults() {
        return new WorkloadRecommender(new CalibrationPolicy());
    }

    /**
     * @param type            the test type to recommend a workload for
     * @param model           the load model the caller wants — never chosen by this method;
     *                        production telemetry reports throughput, not VUs, so a CLOSED
     *                        recommendation is always {@link WorkloadSource#manual()}
     * @param observation     observed production traffic, when known; null when not configured
     * @param limits          the safety envelope to bound Breakpoint (and derive Stress's fallback) against
     * @param environmentType the environment class the ceiling applies to
     */
    public WorkloadRecommendation recommend(TestType type, WorkloadModel model,
            ProductionObservation observation, SafetyLimits limits, EnvironmentType environmentType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(environmentType, "environmentType");
        return switch (type) {
            case SMOKE -> smoke(model);
            case AVERAGE_LOAD -> averageLoad(model, observation);
            case STRESS -> stress(model, observation);
            case SPIKE -> spike(model, observation);
            case SOAK -> soak(model, observation);
            case BREAKPOINT -> breakpoint(model, observation, limits, environmentType);
        };
    }

    private WorkloadRecommendation smoke(WorkloadModel model) {
        LoadShape shape = model == WorkloadModel.CLOSED
                ? new ConstantConcurrencyShape(SMOKE_VUS, SMOKE_DURATION)
                : new ConstantArrivalRateShape(SMOKE_RATE, SMOKE_DURATION);
        return new WorkloadRecommendation(TestType.SMOKE, ShapeKind.STEADY, shape,
                "A very small, steady check that Vortex can reach the service and the workload "
                        + "itself is valid.",
                WorkloadSource.manual(), false);
    }

    private WorkloadRecommendation averageLoad(WorkloadModel model, ProductionObservation observation) {
        if (model == WorkloadModel.OPEN && observation != null) {
            WorkloadSuggestion suggestion = pick(calibration.propose(observation), "average-load");
            LoadShape shape = new ConstantArrivalRateShape(suggestion.rate(), suggestion.duration());
            return new WorkloadRecommendation(TestType.AVERAGE_LOAD, ShapeKind.STEADY, shape,
                    suggestion.description(), suggestion.source(), false);
        }
        LoadShape shape = model == WorkloadModel.CLOSED
                ? new ConstantConcurrencyShape(MANUAL_BASELINE_VUS, Duration.ofMinutes(10))
                : new ConstantArrivalRateShape(MANUAL_BASELINE_RATE, Duration.ofMinutes(10));
        return new WorkloadRecommendation(TestType.AVERAGE_LOAD, ShapeKind.STEADY, shape,
                "The traffic the service normally receives. Record an observed production rate to "
                        + "ground this in real numbers instead.",
                WorkloadSource.manual(), false);
    }

    private WorkloadRecommendation stress(WorkloadModel model, ProductionObservation observation) {
        if (model == WorkloadModel.CLOSED) {
            int baseline = MANUAL_BASELINE_VUS.vus();
            int peak = baseline * STRESS_MANUAL_MULTIPLIER.intValue();
            List<Stage> stages = evenRamp(baseline, peak, STRESS_STAGES, STRESS_STAGE_DURATION);
            LoadShape shape = new RampingConcurrencyShape(Concurrency.of(baseline), stages);
            return new WorkloadRecommendation(TestType.STRESS, ShapeKind.PROGRESSIVE_RAMP, shape,
                    "Traffic heavier than normal, ramped up in view so you can see where behaviour "
                            + "starts to change.", WorkloadSource.manual(), false);
        }
        RequestsPerSecond peak;
        WorkloadSource source;
        if (observation != null) {
            WorkloadSuggestion forecast = pick(calibration.propose(observation), "forecast");
            peak = forecast.rate();
            source = forecast.source();
        } else {
            peak = RequestsPerSecond.of(
                    MANUAL_BASELINE_RATE.value().multiply(STRESS_MANUAL_MULTIPLIER).doubleValue());
            source = WorkloadSource.manual();
        }
        List<Stage> stages = evenRamp(MANUAL_BASELINE_RATE.asDouble(), peak.asDouble(),
                STRESS_STAGES, STRESS_STAGE_DURATION);
        LoadShape shape = new RampingArrivalRateShape(
                RequestsPerSecond.of(stages.getFirst().target().asDouble()), stages);
        return new WorkloadRecommendation(TestType.STRESS, ShapeKind.PROGRESSIVE_RAMP, shape,
                "Traffic heavier than normal, ramped up in view so you can see where behaviour starts "
                        + "to change.", source, false);
    }

    private WorkloadRecommendation spike(WorkloadModel model, ProductionObservation observation) {
        if (model == WorkloadModel.CLOSED) {
            int baseline = MANUAL_BASELINE_VUS.vus();
            int peak = baseline * SPIKE_MULTIPLIER.intValue();
            LoadShape shape = SpikeShapes.concurrency(baseline, peak, SPIKE_HOLD_BEFORE, SPIKE_HOLD_AT_PEAK);
            return new WorkloadRecommendation(TestType.SPIKE, ShapeKind.SPIKE, shape,
                    "A sudden jump in concurrent clients, held briefly, then a return to normal.",
                    WorkloadSource.manual(), false);
        }
        double baseline = observation != null
                ? observation.representativeRate().asDouble() : MANUAL_BASELINE_RATE.asDouble();
        double peak = observation != null
                ? observation.peakRate().asDouble() : baseline * SPIKE_MULTIPLIER.doubleValue();
        WorkloadSource source = observation != null
                ? WorkloadSource.derived(observation.source(), observation.observation(),
                        "Jumps to your observed peak of " + observation.peakRate().display()
                                + " requests/sec from your typical " + observation.representativeRate().display()
                                + ", held briefly, then recovers.")
                : WorkloadSource.manual();
        LoadShape shape = SpikeShapes.arrivalRate(baseline, peak, SPIKE_HOLD_BEFORE, SPIKE_HOLD_AT_PEAK);
        return new WorkloadRecommendation(TestType.SPIKE, ShapeKind.SPIKE, shape,
                "A sudden jump in traffic, held briefly, then a return to normal — the shape a smooth "
                        + "ramp never reproduces.", source, false);
    }

    private WorkloadRecommendation soak(WorkloadModel model, ProductionObservation observation) {
        if (model == WorkloadModel.OPEN && observation != null) {
            RequestsPerSecond rate = observation.representativeRate();
            LoadShape shape = new ConstantArrivalRateShape(rate, SOAK_DURATION);
            return new WorkloadRecommendation(TestType.SOAK, ShapeKind.STEADY, shape,
                    "Representative traffic, held for a long time, so slow drift has room to show up.",
                    WorkloadSource.observed(observation.source(), observation.observation())
                            .withDerivation("Your typical rate of " + rate.display() + " requests/sec, held for "
                                    + Durations.display(SOAK_DURATION) + "."),
                    false);
        }
        LoadShape shape = model == WorkloadModel.CLOSED
                ? new ConstantConcurrencyShape(MANUAL_BASELINE_VUS, SOAK_DURATION)
                : new ConstantArrivalRateShape(MANUAL_BASELINE_RATE, SOAK_DURATION);
        return new WorkloadRecommendation(TestType.SOAK, ShapeKind.STEADY, shape,
                "Moderate load, held for a long time, so slow drift has room to show up.",
                WorkloadSource.manual(), false);
    }

    private WorkloadRecommendation breakpoint(WorkloadModel model, ProductionObservation observation,
            SafetyLimits limits, EnvironmentType environmentType) {
        if (model == WorkloadModel.CLOSED) {
            Concurrency ceiling = limits.concurrencyCeilingFor(environmentType);
            int start = Math.max(1, ceiling.vus() / 10);
            List<Stage> stages = evenRamp(start, ceiling.vus(), 4, Duration.ofMinutes(5));
            LoadShape shape = new RampingConcurrencyShape(Concurrency.of(start), stages);
            return new WorkloadRecommendation(TestType.BREAKPOINT, ShapeKind.STAGED, shape,
                    "Load increases in stages until an objective is violated or the configured "
                            + "safety limit is reached.", WorkloadSource.manual(), true);
        }

        RequestsPerSecond ceiling = limits.ceilingFor(environmentType);
        boolean capped;
        List<Stage> stages;
        WorkloadSource source;
        if (observation != null) {
            WorkloadSuggestion proposal = pick(calibration.propose(observation), "capacity");
            capped = proposal.rate().compareTo(ceiling) > 0;
            RequestsPerSecond effectiveCeiling = capped ? ceiling : proposal.rate();
            stages = cappedRamp(proposal.stages(), effectiveCeiling, Duration.ofMinutes(5));
            source = proposal.source();
        } else {
            double start = Math.max(1, ceiling.asDouble() / 10);
            stages = evenRamp(start, ceiling.asDouble(), 4, Duration.ofMinutes(5));
            capped = true;
            source = WorkloadSource.manual();
        }
        LoadShape shape = new RampingArrivalRateShape(
                RequestsPerSecond.of(stages.getFirst().target().asDouble()), stages);
        return new WorkloadRecommendation(TestType.BREAKPOINT, ShapeKind.STAGED, shape,
                "Load increases in stages until an objective is violated or the configured safety "
                        + "limit is reached.", source, capped);
    }

    // -------------------------------------------------------------- shared stage-building helpers

    private static List<Stage> evenRamp(double start, double peak, int stageCount, Duration each) {
        List<Stage> stages = new ArrayList<>();
        for (int i = 1; i <= stageCount; i++) {
            double target = start + (peak - start) * i / stageCount;
            stages.add(new Stage(RequestsPerSecond.of(target), each));
        }
        return stages;
    }

    private static List<Stage> evenRamp(int start, int peak, int stageCount, Duration each) {
        List<Stage> stages = new ArrayList<>();
        for (int i = 1; i <= stageCount; i++) {
            int target = start + (peak - start) * i / stageCount;
            stages.add(new Stage(Concurrency.of(Math.max(1, target)), each));
        }
        return stages;
    }

    /**
     * Caps each proposed stage at the ceiling, collapsing consecutive stages the cap makes equal
     * into one longer stage rather than emitting flat repeated stages — a repeated identical stage is
     * not a second observation. May legally return a single stage if every proposed stage exceeds the
     * ceiling (rare: only when the safety ceiling sits below even the first ramp stage) —
     * {@code RampingArrivalRateShape} permits a one-stage ramp, so this stays valid; it simply reads
     * as "hold at the ceiling" rather than a visible ramp, which is acceptable given how narrow the
     * case is.
     */
    private static List<Stage> cappedRamp(List<RequestsPerSecond> proposedTargets, RequestsPerSecond ceiling,
            Duration each) {
        List<Stage> stages = new ArrayList<>();
        for (RequestsPerSecond target : proposedTargets) {
            RequestsPerSecond capped = target.compareTo(ceiling) > 0 ? ceiling : target;
            if (!stages.isEmpty() && stages.getLast().target().equals(capped)) {
                Stage last = stages.removeLast();
                stages.add(new Stage(capped, last.duration().plus(each)));
            } else {
                stages.add(new Stage(capped, each));
            }
        }
        return stages;
    }

    private static WorkloadSuggestion pick(List<WorkloadSuggestion> suggestions, String name) {
        return suggestions.stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "CalibrationPolicy stopped producing '" + name + "'"));
    }
}
