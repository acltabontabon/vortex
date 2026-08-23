package dev.vortex.core.validity;

import dev.vortex.core.analysis.EvidenceIds;
import dev.vortex.core.analysis.ResourcePressure;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.metrics.FailureClass;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.StageTelemetry;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.threshold.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether an experiment was carried out as specified.
 *
 * <p>A pure function of the same evidence tier everything else is derived from. Given identical
 * measurements it returns an identical assessment, which is what lets a stored grade be trusted
 * months later rather than recomputed from whatever the current code believes.
 *
 * <h2>Closed by design</h2>
 * One private method per reason code, and no strategy interface. The code set is closed because
 * every entry has to cite a measurement Vortex actually collects; an extension point would invite
 * codes that cite nothing, which is the one thing ADR-038 forbids outright.
 *
 * <h2>Absence never strengthens a conclusion</h2>
 * Every rule below fires on something measured. Where the measurement is missing the rule does not
 * fire — and its silence is never read as evidence that the thing it looks for did not happen. That
 * cuts hardest for {@code GENERATOR_SATURATED}: a run with no generator telemetry is not a run with
 * a healthy generator.
 */
public final class RunQualityAssessor {

    /**
     * How much p95 may rise between stages before the service counts as showing distress.
     *
     * <p>Used only to <em>refuse</em> the indirect generator attribution below, so a low bar is the
     * cautious direction: the smaller this is, the more readily Vortex decides the service might be
     * struggling and declines to blame its own generator.
     */
    private static final double LATENCY_RISE_INDICATING_DISTRESS = 1.10;

    /**
     * Assesses one run.
     *
     * @param plan          the experiment as specified, carrying the policy the rules compare against
     * @param results       what was measured, including what the generator itself managed
     * @param stages        the run cut by level, empty for a workload with no stages to align to
     * @param terminalState the state the execution ended in
     * @param failureReason why it failed, when it did
     */
    public RunQualityAssessment assess(EffectiveTestPlan plan, MeasuredResults results,
            List<StageObservation> stages, ExecutionState terminalState,
            FailureReason failureReason) {

        if (plan == null || results == null) {
            return RunQualityAssessment.notAssessed();
        }
        ValidityPolicy policy = plan.validityPolicy();
        List<StageObservation> byLevel = stages == null ? List.of() : stages;

        List<ValidityFinding> findings = new ArrayList<>();
        interrupted(terminalState, failureReason).ifPresent(findings::add);
        generatorSaturated(results).ifPresent(findings::add);
        generatorHostUnderPressure(results).ifPresent(findings::add);
        offeredLoadNotGenerated(results, byLevel, policy).ifPresent(findings::add);
        runTooShort(plan, results, policy).ifPresent(findings::add);
        insufficientSamples(byLevel, policy).ifPresent(findings::add);
        telemetryIncomplete(results, byLevel).ifPresent(findings::add);
        targetUnavailable(results, policy).ifPresent(findings::add);
        windowMisaligned(results, policy).ifPresent(findings::add);
        // WARM_UP_NOT_COMPLETED is deliberately absent. The workload model cannot express a declared
        // warm-up in this phase, so there is no measurement to fire on — and inventing an input so
        // the rule has something to read is exactly the approximation ADR-038 forbids.

        return RunQualityAssessment.of(findings);
    }

    // ---------------------------------------------------------------- the generator

    /**
     * The load the workload asked for was never produced.
     *
     * <p>Two branches, and the second is deliberately hard to satisfy. The direct one reads a counter
     * the engine published about itself; the indirect one infers the same thing from a shortfall, and
     * inference about which of two systems fell behind is exactly where a validity model can start
     * inventing conclusions.
     */
    private Optional<ValidityFinding> offeredLoadNotGenerated(MeasuredResults results,
            List<StageObservation> stages, ValidityPolicy policy) {

        Optional<ValidityFinding> direct = droppedWork(results, stages);
        if (direct.isPresent()) {
            // Direct evidence about the generator, and sufficient on its own. Preferred whenever it
            // exists — there is nothing to infer when the engine has already said so.
            return direct;
        }
        return shortfallAttributableToTheGenerator(results, stages, policy);
    }

    /** The engine reported it could not start work it was asked to start. */
    private Optional<ValidityFinding> droppedWork(MeasuredResults results,
            List<StageObservation> stages) {

        Optional<Long> dropped = results.generation().iterationsDroppedIfPresent();
        if (dropped.isEmpty() || dropped.get() == 0) {
            return Optional.empty();
        }
        LoadLevel from = firstLevelWithDroppedWork(results, stages).orElse(null);
        String where = from == null ? "" : " First observed while the workload held "
                + from.displayWithUnit() + ".";
        String share = results.generation().droppedFraction()
                .map(fraction -> String.format(" That is %.1f%% of the work it was asked to start.",
                        fraction.doubleValue() * 100))
                .orElse("");

        return Optional.of(new ValidityFinding(ValidityReason.OFFERED_LOAD_NOT_GENERATED,
                ValidityEffect.WITHHOLDS_CAPACITY,
                "The load generator could not start " + dropped.get() + " units of work it was "
                        + "asked to start, so the offered load was never actually offered." + share
                        + where + " A capacity figure from this run would describe the machine "
                        + "running Vortex rather than the service.",
                List.of(EvidenceIds.ITERATIONS_DROPPED, EvidenceIds.THROUGHPUT_ACHIEVED),
                from));
    }

    /** The lowest level at which the series recorded work the generator could not start. */
    private Optional<LoadLevel> firstLevelWithDroppedWork(MeasuredResults results,
            List<StageObservation> stages) {

        return results.series().points().stream()
                .filter(point -> point.iterationsDroppedIfPresent().orElse(0L) > 0)
                .map(point -> point.targetLoadIfPresent().orElse(null))
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.comparingDouble(LoadLevel::asDouble))
                .or(() -> stages.isEmpty() ? Optional.empty()
                        : Optional.ofNullable(stages.getFirst().targetLoad()));
    }

    /**
     * A shortfall that can be attributed to the generator rather than to the service.
     *
     * <p>Every condition below must be <em>positively established</em>. "The service showed no
     * distress" has to mean distress was looked for, with the instruments to find it, and was not
     * there — not that nobody looked. Absence of evidence would otherwise become evidence about
     * whose fault a shortfall was, which is the failure this whole axis exists to prevent.
     *
     * <p>Where any input is missing or ambiguous this returns empty. The shortfall is not discarded:
     * it remains a measurement, and the finding detector already reports it as one, explicitly
     * refusing to name which side produced it.
     */
    private Optional<ValidityFinding> shortfallAttributableToTheGenerator(MeasuredResults results,
            List<StageObservation> stages, ValidityPolicy policy) {

        if (stages.size() < 2) {
            // Condition 4 needs a healthy lower stage to compare latency against. With one stage
            // there is nothing to establish that the service was coping before it stopped.
            return Optional.empty();
        }
        if (!results.reliability().wasReported()) {
            // Condition 3. Without an outcome distribution Vortex cannot tell a service refusing
            // work from a service that was never reached, and both explain a shortfall.
            return Optional.empty();
        }

        List<StageObservation> ascending = stages.stream()
                .sorted(java.util.Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .toList();

        for (int index = 1; index < ascending.size(); index++) {
            StageObservation stage = ascending.get(index);
            StageObservation below = ascending.get(index - 1);

            if (!isShortBy(stage, policy) || !stage.isCompliant() || !below.isCompliant()) {
                continue;
            }
            if (latencyRose(below, stage) || stage.errorRate().fraction().signum() > 0) {
                // The service was slowing down or failing. Whatever else is true, this shortfall
                // cannot be attributed to the generator.
                continue;
            }
            if (!stage.hasTypedResources()) {
                // Condition 5. Nobody measured the service's resources, so "nothing was near its
                // limit" is not something this run established.
                continue;
            }
            if (stage.serviceResourceSignals().stream().anyMatch(ResourcePressure::isUnderPressure)) {
                continue;
            }

            String shortfall = stage.rateShortfall()
                    .map(fraction -> String.format("%.0f%%", fraction * 100))
                    .orElse("a material amount");

            return Optional.of(new ValidityFinding(ValidityReason.OFFERED_LOAD_NOT_GENERATED,
                    ValidityEffect.WITHHOLDS_CAPACITY,
                    "Achieved throughput fell " + shortfall + " below the offered "
                            + stage.targetLoad().displayWithUnit() + " while the service met every "
                            + "objective, returned no errors, and had no observed resource near its "
                            + "limit. The shortfall is therefore attributable to the load generator "
                            + "rather than to the service, and no capacity may be quoted at or above "
                            + "this level.",
                    List.of(EvidenceIds.THROUGHPUT_ACHIEVED, EvidenceIds.THROUGHPUT_TARGET,
                            EvidenceIds.REQUEST_ERROR_RATE),
                    stage.targetLoad()));
        }
        return Optional.empty();
    }

    private boolean isShortBy(StageObservation stage, ValidityPolicy policy) {
        return stage.rateShortfall()
                .map(fraction -> fraction > policy.materialShortfallFraction())
                .orElse(false);
    }

    private boolean latencyRose(StageObservation below, StageObservation stage) {
        var lower = below.p95IfPresent();
        var upper = stage.p95IfPresent();
        if (lower.isEmpty() || upper.isEmpty() || lower.get().isZero()) {
            // No comparison is possible, so the stage cannot be established as untroubled.
            return true;
        }
        double ratio = (double) upper.get().toNanos() / lower.get().toNanos();
        return ratio >= LATENCY_RISE_INDICATING_DISTRESS;
    }

    /** A resource on the machine generating the traffic reached its declared limit. */
    private Optional<ValidityFinding> generatorSaturated(MeasuredResults results) {
        Optional<ResourceSignal> saturated =
                results.resourcesScopedTo(ResourceScope.LOAD_GENERATOR).stream()
                        .filter(ResourceSignal::isAtItsLimit)
                        .findFirst();

        // Absent generator telemetry lands here as an empty stream, which is correct and must stay
        // correct: this rule never fires on a guess, and its silence is not proof of health.
        return saturated.map(signal -> new ValidityFinding(ValidityReason.GENERATOR_SATURATED,
                ValidityEffect.WITHHOLDS_CAPACITY,
                "The load generator's own process or container reached its limit: " + signal.describe()
                        + ". A capacity figure from this run would describe the generator's own "
                        + "ceiling rather than the service under test.",
                List.of(signal.signalId())));
    }

    /**
     * The machine running the generator — not the generator's own process or container — was under
     * pressure.
     *
     * <p>Deliberately weaker than {@link #generatorSaturated}: anything else sharing that machine
     * could be the actual cause, so this qualifies a run's confidence rather than withholding its
     * capacity conclusion the way a signal scoped to the generator itself does.
     */
    private Optional<ValidityFinding> generatorHostUnderPressure(MeasuredResults results) {
        Optional<ResourceSignal> pressured =
                results.resourcesScopedTo(ResourceScope.LOAD_GENERATOR_HOST).stream()
                        .filter(ResourcePressure::isUnderPressure)
                        .findFirst();

        return pressured.map(signal -> new ValidityFinding(ValidityReason.GENERATOR_HOST_UNDER_PRESSURE,
                ValidityEffect.QUALIFIES,
                "The machine running the load generator was under resource pressure (not the "
                        + "generator's own process or container): " + signal.describe() + ". This "
                        + "does not by itself mean the generator could not keep up, but it qualifies "
                        + "confidence in the load this run generated.",
                List.of(signal.signalId())));
    }

    // ---------------------------------------------------------------- the experiment

    private Optional<ValidityFinding> runTooShort(EffectiveTestPlan plan, MeasuredResults results,
            ValidityPolicy policy) {

        Duration minimum = policy.minimumRunDuration(plan.testType());
        Duration measured = results.duration();
        if (minimum.isZero() || measured.compareTo(minimum) >= 0) {
            return Optional.empty();
        }
        return Optional.of(new ValidityFinding(ValidityReason.RUN_TOO_SHORT,
                ValidityEffect.QUALIFIES,
                "Held for " + Durations.display(measured) + "; " + plan.testType().asPhrase()
                        + " requires " + Durations.display(minimum)
                        + " before its conclusions stand unqualified.",
                List.of(EvidenceIds.THROUGHPUT_ACHIEVED)));
    }

    private Optional<ValidityFinding> insufficientSamples(List<StageObservation> stages,
            ValidityPolicy policy) {

        long minimum = policy.minimumRequestsPerStage();
        List<StageObservation> thin = stages.stream()
                .filter(stage -> stage.requests() > 0 && !stage.hasEnoughSamples(minimum))
                .toList();
        if (thin.isEmpty()) {
            return Optional.empty();
        }
        StageObservation worst = thin.getFirst();
        return Optional.of(new ValidityFinding(ValidityReason.INSUFFICIENT_SAMPLES,
                ValidityEffect.QUALIFIES,
                thin.size() + " of " + stages.size() + " stages carried fewer than " + minimum
                        + " requests — the thinnest held " + worst.targetLoad().displayWithUnit()
                        + " and carried " + worst.requests() + ". Those stages may not be a "
                        + "capacity boundary edge.",
                List.of(EvidenceIds.REQUEST_COUNT),
                worst.targetLoad()));
    }

    private Optional<ValidityFinding> telemetryIncomplete(MeasuredResults results,
            List<StageObservation> stages) {

        boolean gaps = !results.telemetryGaps().isEmpty();
        long coveredStages = results.stageTelemetry().stream()
                .filter(stage -> !stage.isEmpty())
                .count();
        boolean stagesUncovered = !stages.isEmpty() && coveredStages < stages.size();

        if (!gaps && !stagesUncovered) {
            return Optional.empty();
        }
        String detail = gaps
                ? results.telemetryGaps().size() + " measurement"
                        + (results.telemetryGaps().size() == 1 ? " was" : "s were")
                        + " asked for and could not be supplied"
                : "telemetry covered " + coveredStages + " of " + stages.size() + " stages";

        return Optional.of(new ValidityFinding(ValidityReason.TELEMETRY_INCOMPLETE,
                ValidityEffect.QUALIFIES,
                "Telemetry was incomplete: " + detail + ". No resource can be named as the first to "
                        + "reach its limit when not all of them were observed.",
                results.telemetryGaps().stream()
                        .map(gap -> gap.metricName())
                        .filter(name -> !name.isBlank())
                        .toList()));
    }

    /**
     * Requests failed without reaching the service, in numbers that put its availability in doubt.
     *
     * <p>Qualifies rather than invalidates below half the run, because the same shape is what a
     * saturated generator produces and Vortex must not diagnose the service from it. Above half,
     * whatever the cause, the run did not measure a service serving traffic.
     */
    private Optional<ValidityFinding> targetUnavailable(MeasuredResults results,
            ValidityPolicy policy) {

        Optional<Double> unreached = results.reliability().unreachedShare();
        if (unreached.isEmpty() || unreached.get() <= policy.targetUnavailableShare()) {
            return Optional.empty();
        }
        double share = unreached.get();
        boolean most = share > 0.5;

        return Optional.of(new ValidityFinding(ValidityReason.TARGET_UNAVAILABLE_DURING_RUN,
                most ? ValidityEffect.WITHHOLDS_ALL_CLAIMS : ValidityEffect.QUALIFIES,
                String.format("%.1f%% of requests failed without reaching the service — %d timed "
                                + "out and %d could not connect — against a threshold of %.0f%%. "
                                + "%s", share * 100,
                        results.reliability().count(FailureClass.TIMEOUT),
                        results.reliability().count(FailureClass.CONNECTION),
                        policy.targetUnavailableShare() * 100,
                        most ? "Most of this run did not reach the service at all."
                                : "The same shape is produced by a saturated generator, so this "
                                        + "qualifies the run rather than diagnosing the service."),
                List.of(EvidenceIds.REQUEST_FAILURES, EvidenceIds.REQUEST_ERROR_RATE)));
    }

    private Optional<ValidityFinding> interrupted(ExecutionState terminalState,
            FailureReason failureReason) {

        boolean cancelled = terminalState == ExecutionState.CANCELLED;
        boolean broken = failureReason == FailureReason.INTERRUPTED;
        if (!cancelled && !broken) {
            return Optional.empty();
        }
        return Optional.of(new ValidityFinding(ValidityReason.EXECUTION_INTERRUPTED,
                ValidityEffect.WITHHOLDS_CAPACITY,
                (cancelled ? "The run was cancelled" : "The run was interrupted")
                        + " before it finished, so the levels it reached were not held as specified. "
                        + "Everything measured up to that point is kept and reported; no capacity is "
                        + "quoted from it.",
                List.of()));
    }

    /**
     * A provider's telemetry describes a different period of the service's life than the run.
     *
     * <p>Compared against the execution window rather than assumed: a clock skew of a few seconds is
     * ordinary, and a window that misses by minutes is describing something else entirely.
     */
    private Optional<ValidityFinding> windowMisaligned(MeasuredResults results,
            ValidityPolicy policy) {

        Duration tolerance = policy.telemetryWindowTolerance();
        var runWindow = results.window();

        for (StageTelemetry stage : results.stageTelemetry()) {
            if (stage.isEmpty()) {
                continue;
            }
            Duration startsAfter = Duration.between(runWindow.start(), stage.window().start());
            Duration endsBefore = Duration.between(stage.window().end(), runWindow.end());
            Duration worst = startsAfter.abs().compareTo(endsBefore.abs()) >= 0
                    ? startsAfter.abs() : endsBefore.abs();

            if (worst.compareTo(tolerance) > 0
                    && !overlaps(stage.window(), runWindow)) {
                return Optional.of(new ValidityFinding(ValidityReason.WINDOW_MISALIGNED,
                        ValidityEffect.QUALIFIES,
                        "Telemetry for stage " + stage.stageIndex() + " covers a window "
                                + Durations.display(worst) + " away from the execution window, "
                                + "against a tolerance of " + Durations.display(tolerance)
                                + ". Those measurements cannot corroborate what the run observed.",
                        List.of()));
            }
        }
        return Optional.empty();
    }

    private boolean overlaps(dev.vortex.core.metrics.TimeWindow left,
            dev.vortex.core.metrics.TimeWindow right) {
        return left.start().isBefore(right.end()) && right.start().isBefore(left.end());
    }
}
