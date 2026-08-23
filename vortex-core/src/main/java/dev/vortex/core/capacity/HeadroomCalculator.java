package dev.vortex.core.capacity;

import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * Computes capacity headroom, and declines to when the inputs are not comparable.
 *
 * <p>Pure arithmetic, deliberately. The temptation with a number like headroom is to let a language
 * model produce it because the calculation is trivial; the reason not to is that a headroom figure
 * ends up in capacity planning decisions, and a figure that varies between runs of the same data is
 * not evidence.
 *
 * <p>The interesting logic is not the division — it is the refusal. Comparing a tested capacity
 * measured against simulated dependencies with a production peak measured against real ones would
 * produce a confident multiple that means nothing, so that case returns empty with a reason.
 */
public final class HeadroomCalculator {

    /** Why headroom could not be computed. Shown in place of the number. */
    public record NotComparable(String reason) {
    }

    /**
     * Computes headroom when the inputs support it.
     *
     * @param testedCapacity   highest tested level meeting every objective, from an execution
     * @param testedIntegrated whether that test ran against production-like dependencies
     * @param observation      the observed production traffic
     * @return the headroom, or the reason it cannot be stated
     */
    public Result calculateFromTestedCapacity(LoadLevel testedCapacity, boolean testedIntegrated,
            ProductionObservation observation) {
        return calculateFromTestedCapacity(testedCapacity, testedIntegrated,
                BoundaryStatus.ESTABLISHED, observation);
    }

    /**
     * Computes headroom from a run's sustainable capacity.
     *
     * <p>This is the numerator that matters. Headroom is the figure that ends up on a slide
     * justifying a deployment, and dividing production traffic into a level the service was never
     * demonstrated to <em>sustain</em> is precisely the arithmetic that makes such a slide wrong. All
     * four existing refusals stay, and this adds a fifth: no sustainable capacity was established,
     * and which of its five conditions failed.
     *
     * <p>Deliberately a compile break at every call site rather than a quiet delegation. ADR-039
     * calls the change a discontinuity worth announcing, and a delegating overload would keep the
     * old numerator alive under the new name — which is the failure the ADR names.
     */
    public Result calculate(SustainableCapacity sustainable, boolean testedIntegrated,
            BoundaryStatus boundaryStatus, ProductionObservation observation) {

        if (sustainable != null && !sustainable.isEstablished()
                && !sustainable.conditions().isEmpty()) {
            // Established as absent, rather than never computed. The distinction matters: a
            // historical observation that predates this calculation must not have its headroom
            // retroactively refused, which is why the caller passes notEvaluated() for those and
            // this branch does not fire.
            String unmet = sustainable.unmet().stream()
                    .map(condition -> condition.statement())
                    .findFirst()
                    .orElse("");
            return new Result(null, new NotComparable(
                    "This run did not establish a sustainable capacity, so there is no defensible "
                            + "figure to compare production traffic against. " + unmet
                            + (sustainable.highestLevelThatPassedIfPresent()
                                    .map(level -> " The highest level that passed was "
                                            + level.displayWithUnit()
                                            + ", which is not a capacity claim.")
                                    .orElse(""))));
        }
        LoadLevel numerator = sustainable == null ? null : sustainable.level();
        return calculateFromTestedCapacity(numerator, testedIntegrated, boundaryStatus, observation);
    }

    /**
     * Computes headroom from the highest level that passed, declining when that is not a boundary.
     *
     * <p>The historical numerator, and named so a caller has to ask for it. Two things still reach
     * here: an observation recorded before sustainable capacity was computed — whose figure must not
     * be retroactively refused — and the sustainable path above, once it has a level to divide.
     *
     * @param boundaryStatus whether the run established a boundary at all. A multiple divided out of
     *                       a level whose compliance did not move consistently with load would be a
     *                       confident number resting on noise — and headroom is exactly the figure
     *                       that ends up on a slide with none of its conditions attached
     */
    public Result calculateFromTestedCapacity(LoadLevel testedCapacity, boolean testedIntegrated,
            BoundaryStatus boundaryStatus, ProductionObservation observation) {
        if (boundaryStatus != null && !boundaryStatus.isQuotable()) {
            // The two non-quotable cases need different advice, and giving one the other's is
            // worse than giving none: telling somebody whose run had no objectives that their
            // environment is varying between stages sends them to look at the wrong thing.
            String remedy = switch (boundaryStatus) {
                case UNSTABLE -> "Compliance did not move consistently with load, so the highest "
                        + "passing level is not a capacity. Re-run the workload; if the result is "
                        + "the same, something in the environment is varying between stages.";
                case NOT_EVALUATED -> "This run's objectives were not evaluated, so no level was "
                        + "established as compliant. Add objectives to the workload, or re-run one "
                        + "that has them.";
                default -> "";
            };
            return new Result(null, new NotComparable(
                    "There is no tested capacity to compare production traffic against. " + remedy));
        }
        if (testedCapacity == null) {
            return new Result(null, new NotComparable(
                    "No tested capacity is available: this run never established a compliant level."));
        }
        if (observation == null) {
            return new Result(null, new NotComparable(
                    "No observed production traffic has been recorded for this service, so there is "
                            + "nothing to compare tested capacity against. Add an observation on the "
                            + "project page."));
        }
        if (!(testedCapacity instanceof RequestsPerSecond tested)) {
            // A concurrency workload establishes how many clients the service can carry, not how
            // much traffic it can absorb. Dividing virtual users by requests/sec would produce a
            // multiple with no unit and no meaning.
            return new Result(null, new NotComparable(
                    "This capacity was measured as " + testedCapacity.displayWithUnit()
                            + ", while production traffic is recorded in requests/sec. The two are "
                            + "not the same quantity and cannot be divided: a virtual user's "
                            + "throughput depends on how fast the service responds. Run an "
                            + "arrival-rate workload to establish capacity comparable with production "
                            + "traffic."));
        }
        if (!testedIntegrated) {
            return new Result(null, new NotComparable(
                    "This capacity was measured in an isolated test, where dependencies were simulated "
                            + "or controlled. Comparing it with production traffic would overstate the "
                            + "headroom the service actually has. Run an integrated test against "
                            + "production-like dependencies to establish comparable capacity."));
        }
        BigDecimal peak = observation.peakRate().value();
        if (peak.signum() <= 0) {
            return new Result(null, new NotComparable(
                    "The observed production peak is zero, so a headroom multiple cannot be computed."));
        }
        BigDecimal multiple = tested.value().divide(peak, MathContext.DECIMAL64);
        return new Result(new Headroom(tested, observation.peakRate(), multiple), null);
    }

    /** Either a headroom figure or the reason there isn't one. Never both, never neither. */
    public record Result(Headroom headroom, NotComparable notComparable) {

        public Optional<Headroom> value() {
            return Optional.ofNullable(headroom);
        }

        public Optional<String> reason() {
            return Optional.ofNullable(notComparable).map(NotComparable::reason);
        }

        public boolean isAvailable() {
            return headroom != null;
        }
    }
}
