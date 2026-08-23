package dev.vortex.core.workload;

import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An open workload holding one steady total arrival rate for a fixed duration.
 *
 * <p>The shape behind most questions of the form "does the service meet its objectives at
 * <em>this</em> level of traffic?" — smoke, average load, a sustained peak, a soak.
 */
public record ConstantArrivalRateShape(RequestsPerSecond rate, Duration duration)
        implements LoadShape {

    public ConstantArrivalRateShape {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(duration, "duration");
        if (!rate.isPositive()) {
            throw new IllegalArgumentException(
                    "an arrival-rate workload must request more than 0 requests/sec. A workload that "
                            + "generates no traffic produces no evidence.");
        }
        LoadShapeDurations.require(duration);
    }

    public static ConstantArrivalRateShape of(double rate, Duration duration) {
        return new ConstantArrivalRateShape(RequestsPerSecond.of(rate), duration);
    }

    @Override
    public WorkloadModel model() {
        return WorkloadModel.OPEN;
    }

    @Override
    public Duration totalDuration() {
        return duration;
    }

    @Override
    public RequestsPerSecond startLevel() {
        return rate;
    }

    @Override
    public RequestsPerSecond peakLevel() {
        return rate;
    }

    @Override
    public List<Stage> stages() {
        return List.of(new Stage(rate, duration));
    }

    @Override
    public Optional<Long> estimatedRequests() {
        return Optional.of(Math.round(rate.asDouble() * (duration.toMillis() / 1000.0)));
    }
}
