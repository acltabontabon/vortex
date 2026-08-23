package dev.vortex.core.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * What the load generator managed to do, as distinct from what the service did.
 *
 * <p>Every other measurement in this package describes the system under test. This one describes
 * Vortex's own side of the experiment, and it is carried separately for that reason: a run that
 * asked for 1,000 requests/sec and produced 720 has measured the generator's ceiling, not the
 * service's, and nothing downstream can tell the difference without this.
 *
 * <h2>Why every field is boxed</h2>
 * {@code droppedIterations == 0} means the generator kept up. An absent value means nobody looked —
 * an execution recorded before this was collected, or an imported script whose engine never reported
 * it. Collapsing those two into a primitive zero would make "the generator was fine" the default
 * answer to a question that was never asked, which is the exact fabrication a capacity claim must
 * not rest on.
 *
 * <p>Transport-neutral on purpose: a message publisher that cannot keep up with a declared arrival
 * rate reports the same two counters, and only the word for a unit of work changes.
 *
 * @param iterationsStarted units of work the generator began
 * @param iterationsDropped units of work it could not begin on time — the direct evidence that the
 *                          offered load was never actually offered
 * @param iterationRate     units of work begun per second across the run
 */
public record LoadGeneration(Long iterationsStarted, Long iterationsDropped, Double iterationRate) {

    public LoadGeneration {
        requireNotNegative(iterationsStarted, "iterationsStarted");
        requireNotNegative(iterationsDropped, "iterationsDropped");
        if (iterationRate != null && (iterationRate < 0 || !Double.isFinite(iterationRate))) {
            throw new IllegalArgumentException("iterationRate must be a finite, non-negative number");
        }
    }

    /** Nothing was reported. Distinct from "nothing was dropped" — see the class comment. */
    public static LoadGeneration notReported() {
        return new LoadGeneration(null, null, null);
    }

    public Optional<Long> iterationsStartedIfPresent() {
        return Optional.ofNullable(iterationsStarted);
    }

    public Optional<Long> iterationsDroppedIfPresent() {
        return Optional.ofNullable(iterationsDropped);
    }

    public Optional<Double> iterationRateIfPresent() {
        return Optional.ofNullable(iterationRate);
    }

    /** Whether the engine reported anything at all about its own throughput. */
    public boolean wasReported() {
        return iterationsStarted != null || iterationsDropped != null || iterationRate != null;
    }

    /** Whether the generator is known to have failed to start work it was asked to start. */
    public boolean droppedWork() {
        return iterationsDropped != null && iterationsDropped > 0;
    }

    /**
     * The share of offered work the generator never started, when both counters are known.
     *
     * <p>Absent rather than zero when either is missing: a fraction computed against a denominator
     * nobody measured is arithmetic, not evidence.
     */
    public Optional<BigDecimal> droppedFraction() {
        if (iterationsStarted == null || iterationsDropped == null) {
            return Optional.empty();
        }
        long offered = iterationsStarted + iterationsDropped;
        if (offered == 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(iterationsDropped)
                .divide(BigDecimal.valueOf(offered), MathContext.DECIMAL64));
    }

    private static void requireNotNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
