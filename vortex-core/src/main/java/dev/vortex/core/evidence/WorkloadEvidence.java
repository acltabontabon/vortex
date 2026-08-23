package dev.vortex.core.evidence;

import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.workload.WorkloadSource;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What was asked for, beside what was actually delivered.
 *
 * <p>This record exists for one reason: to make it impossible to render the offered workload without
 * the achieved one. A test that did not generate the traffic it intended must not be able to look
 * like a test that did, and the surest way to guarantee that is to carry the pair together rather
 * than trusting each renderer to remember to show both.
 *
 * <p>Nothing here is calculated that {@code EffectiveTestPlan} and {@code MeasuredResults} do not
 * already calculate. The record pairs them; it does not reinterpret them.
 *
 * @param configuredPeak    the level the workload aimed for, in whichever quantity it controlled
 * @param achievedRate      requests per second actually observed; null when none were
 * @param deliveredFraction how much of the offered load was accepted, as a fraction of one; null
 *                          under a closed workload, where throughput is an outcome and not a target
 * @param deliveredCaveat   why {@code deliveredFraction} is absent, when it is
 * @param requestHeaders    the headers every request carried, sanitised. Names are kept in all
 *                          cases: that a run sent an {@code Authorization} header is part of how
 *                          it was carried out, while what the header contained is not
 */
public record WorkloadEvidence(
        WorkloadModel model,
        LoadLevel configuredPeak,
        RequestsPerSecond achievedRate,
        Double deliveredFraction,
        String deliveredCaveat,
        WorkloadSource source,
        Duration configuredDuration,
        Duration actualDuration,
        List<Stage> stages,
        List<String> operationMix,
        Long estimatedRequests,
        String requestEstimateCaveat,
        long requests,
        long failures,
        ScriptSource scriptSource,
        Map<String, String> engineOptions,
        Map<String, String> requestHeaders) {

    /**
     * How much of the offered rate a run must deliver before it counts as having run the test that
     * was asked for.
     */
    public static final double SUSTAINED = 0.98;

    /** Below this, the workload is different enough from the one configured to warrant a warning. */
    public static final double SHORTFALL = 0.95;

    public WorkloadEvidence {
        Objects.requireNonNull(model, "model");
        stages = stages == null ? List.of() : List.copyOf(stages);
        operationMix = operationMix == null ? List.of() : List.copyOf(operationMix);
        engineOptions = engineOptions == null ? Map.of() : Map.copyOf(engineOptions);
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        deliveredCaveat = deliveredCaveat == null ? "" : deliveredCaveat;
        requestEstimateCaveat = requestEstimateCaveat == null ? "" : requestEstimateCaveat;
    }

    public Optional<RequestsPerSecond> achievedRateIfPresent() {
        return Optional.ofNullable(achievedRate);
    }

    public Optional<Double> deliveredFractionIfPresent() {
        return Optional.ofNullable(deliveredFraction);
    }

    public Optional<Long> estimatedRequestsIfPresent() {
        return Optional.ofNullable(estimatedRequests);
    }

    public boolean isOpen() {
        return model.isOpen();
    }

    /** Whether the run delivered essentially all of the traffic it offered. */
    public boolean sustainedTheTarget() {
        return deliveredFraction != null && deliveredFraction >= SUSTAINED;
    }

    /** Whether the run fell far enough short that the result describes a different experiment. */
    public boolean fellShort() {
        return deliveredFraction != null && deliveredFraction < SHORTFALL;
    }

    /** The delivered fraction as a percentage for display, e.g. {@code 91%}. */
    public Optional<String> deliveredPercent() {
        return deliveredFractionIfPresent()
                .map(fraction -> Math.round(fraction * 100) + "%");
    }

    public boolean hasRequestHeaders() {
        return !requestHeaders.isEmpty();
    }

    public boolean hasStages() {
        return stages.size() > 1;
    }
}
