package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.Concurrency;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A closed workload holding a fixed number of virtual users for a fixed duration.
 *
 * <p>Answers "how does the service behave with this many clients working through it as fast as it
 * lets them?" — the honest model when the real caller is a bounded population, such as a connection
 * pool or a fixed worker fleet.
 *
 * <p>Throughput is an outcome here, not an input. If the service slows down, these virtual users
 * issue fewer requests, so the offered load falls exactly when a capacity test would want it held.
 * That is a property of the model rather than a defect, and it is why
 * {@link WorkloadModel#OPEN} remains the default for capacity work.
 */
public record ConstantConcurrencyShape(Concurrency vus, Duration duration) implements LoadShape {

    public ConstantConcurrencyShape {
        Objects.requireNonNull(vus, "vus");
        LoadShapeDurations.require(duration);
    }

    public static ConstantConcurrencyShape of(int vus, Duration duration) {
        return new ConstantConcurrencyShape(Concurrency.of(vus), duration);
    }

    @Override
    public WorkloadModel model() {
        return WorkloadModel.CLOSED;
    }

    @Override
    public Duration totalDuration() {
        return duration;
    }

    @Override
    public Concurrency startLevel() {
        return vus;
    }

    @Override
    public Concurrency peakLevel() {
        return vus;
    }

    @Override
    public List<Stage> stages() {
        return List.of(new Stage(vus, duration));
    }

    @Override
    public Optional<Long> estimatedRequests() {
        return Optional.empty();
    }
}
