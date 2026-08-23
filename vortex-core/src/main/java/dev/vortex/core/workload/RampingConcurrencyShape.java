package dev.vortex.core.workload;

import dev.vortex.core.shared.Concurrency;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A closed workload that moves through a sequence of virtual-user counts.
 *
 * <p>Useful when the question is about a growing client population rather than growing traffic —
 * "what happens as we add worker instances?" — and for the classic ramp-up-and-hold shape teams
 * already have in existing k6 scripts.
 *
 * <p>As with any closed workload, the level reached is a concurrency and not a throughput, and
 * Vortex labels it that way everywhere it appears.
 */
public record RampingConcurrencyShape(Concurrency startVus, List<Stage> rampStages)
        implements LoadShape {

    public static final int MAX_STAGES = 40;

    public RampingConcurrencyShape {
        Objects.requireNonNull(startVus, "startVus");
        rampStages = rampStages == null ? List.of() : List.copyOf(rampStages);
        if (rampStages.isEmpty()) {
            throw new IllegalArgumentException(
                    "a ramping concurrency workload must declare at least one stage");
        }
        if (rampStages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("a ramping workload must declare at most " + MAX_STAGES
                    + " stages but had " + rampStages.size());
        }
        for (Stage stage : rampStages) {
            if (!(stage.target() instanceof Concurrency)) {
                throw new IllegalArgumentException(
                        "every stage of a concurrency workload must target a virtual-user count, but "
                                + "one targets " + stage.target().displayWithUnit()
                                + ". A single workload controls one quantity throughout.");
            }
        }
    }

    @Override
    public WorkloadModel model() {
        return WorkloadModel.CLOSED;
    }

    @Override
    public Duration totalDuration() {
        return rampStages.stream().map(Stage::duration).reduce(Duration.ZERO, Duration::plus);
    }

    @Override
    public Concurrency startLevel() {
        return startVus;
    }

    @Override
    public Concurrency peakLevel() {
        return rampStages.stream()
                .map(stage -> (Concurrency) stage.target())
                .max(Comparator.naturalOrder())
                .orElse(startVus);
    }

    @Override
    public List<Stage> stages() {
        return rampStages;
    }

    @Override
    public Optional<Long> estimatedRequests() {
        return Optional.empty();
    }
}
