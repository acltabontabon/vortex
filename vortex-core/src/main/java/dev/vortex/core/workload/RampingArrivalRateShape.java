package dev.vortex.core.workload;

import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An open workload that moves through a sequence of total arrival rates.
 *
 * <p>Each stage becomes an observation window, which is what lets Vortex say "the p95 objective was
 * first violated during the 120 requests/sec stage" rather than only reporting an aggregate that
 * hides where degradation began.
 */
public record RampingArrivalRateShape(RequestsPerSecond startRate, List<Stage> rampStages)
        implements LoadShape {

    public static final int MAX_STAGES = 40;

    public RampingArrivalRateShape {
        Objects.requireNonNull(startRate, "startRate");
        rampStages = rampStages == null ? List.of() : List.copyOf(rampStages);
        if (rampStages.isEmpty()) {
            throw new IllegalArgumentException(
                    "a ramping arrival-rate workload must declare at least one stage");
        }
        if (rampStages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("a ramping workload must declare at most " + MAX_STAGES
                    + " stages but had " + rampStages.size());
        }
        for (Stage stage : rampStages) {
            if (!(stage.target() instanceof RequestsPerSecond)) {
                throw new IllegalArgumentException(
                        "every stage of an arrival-rate workload must target a request rate, but one "
                                + "targets " + stage.target().displayWithUnit()
                                + ". A single workload controls one quantity throughout.");
            }
        }
    }

    @Override
    public WorkloadModel model() {
        return WorkloadModel.OPEN;
    }

    @Override
    public Duration totalDuration() {
        return rampStages.stream().map(Stage::duration).reduce(Duration.ZERO, Duration::plus);
    }

    @Override
    public RequestsPerSecond startLevel() {
        return startRate;
    }

    @Override
    public RequestsPerSecond peakLevel() {
        return rampStages.stream()
                .map(stage -> (RequestsPerSecond) stage.target())
                .max(Comparator.naturalOrder())
                .orElse(startRate);
    }

    @Override
    public List<Stage> stages() {
        return rampStages;
    }

    /** Trapezoidal integration over the ramp: each stage moves linearly from the previous level. */
    @Override
    public Optional<Long> estimatedRequests() {
        double total = 0;
        double previous = startRate.asDouble();
        for (Stage stage : rampStages) {
            double seconds = stage.duration().toMillis() / 1000.0;
            double end = stage.target().asDouble();
            total += (previous + end) / 2.0 * seconds;
            previous = end;
        }
        return Optional.of(Math.round(total));
    }
}
