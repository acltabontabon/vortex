package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides whether a run showed the system genuinely running out of capacity — and, much more often,
 * decides that it did not.
 *
 * <p>Detecting an SLO breakpoint is easy: a threshold either was or was not crossed. Detecting that
 * a <em>system</em> broke is not. The signals are indirect and individually unreliable: the load
 * generator falling behind its schedule, errors climbing steeply, timeouts appearing, latency
 * growing without bound. Any one of them has innocent explanations — a garbage collection pause, a
 * noisy laptop, a slow first request against a cold cache.
 *
 * <p>This detector is therefore built to under-report. It requires at least
 * {@value SystemSaturation#MINIMUM_CORROBORATING_SIGNALS} independent signals before it will say
 * anything, and even then it reports a bounded range rather than a point. When the evidence does
 * not meet that bar it returns {@link SystemSaturation.Status#NOT_ESTABLISHED} with an explanation.
 *
 * <p>That outcome is a feature. "System breakpoint: not established by this test" is honest, and it
 * tells an engineer to run a longer or higher ramp. A confident "147 requests/sec" derived from one
 * noisy heuristic would be the most damaging number Vortex could print.
 */
public final class SystemSaturationDetector {

    /** Achieved rate falling this far below target suggests the generator could not push traffic through. */
    private static final double RATE_SHORTFALL_THRESHOLD = 0.15;

    /** An error rate above this is treated as a runaway rather than ordinary noise. */
    private static final double ERROR_RUNAWAY_FRACTION = 0.10;

    /** Latency growing by this multiple relative to the healthy baseline stage. */
    private static final double LATENCY_EXPLOSION_MULTIPLE = 5.0;

    public SystemSaturation detect(List<StageObservation> stages) {
        if (stages == null || stages.size() < 3) {
            return SystemSaturation.notEstablished(
                    "This run did not produce enough distinct traffic levels to tell whether the system "
                            + "stopped coping. A stress test with several increasing stages is needed to "
                            + "establish system saturation.");
        }

        List<StageObservation> ordered = new ArrayList<>(stages);
        ordered.sort(Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()));

        StageObservation healthiest = ordered.stream()
                .filter(StageObservation::isCompliant)
                .filter(s -> s.p95IfPresent().isPresent())
                .findFirst()
                .orElse(null);

        List<String> signals = new ArrayList<>();
        LoadLevel lowerBound = null;
        LoadLevel upperBound = null;

        for (StageObservation stage : ordered) {
            List<String> stageSignals = signalsFor(stage, healthiest);
            if (!stageSignals.isEmpty()) {
                if (lowerBound == null) {
                    lowerBound = stage.targetLoad();
                }
                upperBound = stage.targetLoad();
                for (String signal : stageSignals) {
                    String qualified = signal + " at " + stage.targetLoad().displayWithUnit();
                    if (!signals.contains(qualified)) {
                        signals.add(qualified);
                    }
                }
            }
        }

        long distinctSignalKinds = signals.stream()
                .map(s -> s.substring(0, Math.min(s.length(), s.indexOf(" at ") < 0 ? s.length() : s.indexOf(" at "))))
                .distinct()
                .count();

        if (distinctSignalKinds < SystemSaturation.MINIMUM_CORROBORATING_SIGNALS) {
            return SystemSaturation.notEstablished(
                    "No convincing saturation was observed. "
                            + (signals.isEmpty()
                            ? "The service absorbed every level this run offered, so its breaking "
                            + "point is above the range tested."
                            : "Only one kind of signal appeared (" + signals.getFirst() + "), which is not "
                            + "enough to distinguish genuine saturation from ordinary variance. Vortex "
                            + "requires at least "
                            + SystemSaturation.MINIMUM_CORROBORATING_SIGNALS
                            + " independent signals before reporting a system breaking point."));
        }

        EvidenceStrength strength = distinctSignalKinds >= 3
                ? EvidenceStrength.MEDIUM
                : EvidenceStrength.LOW;

        return new SystemSaturation(
                SystemSaturation.Status.OBSERVED,
                lowerBound,
                upperBound,
                signals,
                strength,
                "Saturation signals appeared between " + lowerBound.display() + " and "
                        + upperBound.displayWithUnit() + ". This is a bounded range, not a precise "
                        + "breaking point: the exact level depends on conditions this test did not control.");
    }

    private List<String> signalsFor(StageObservation stage, StageObservation healthiest) {
        List<String> signals = new ArrayList<>();

        stage.rateShortfall().ifPresent(shortfall -> {
            if (shortfall >= RATE_SHORTFALL_THRESHOLD) {
                signals.add("the achieved rate fell "
                        + Math.round(shortfall * 100) + "% short of the offered rate");
            }
        });

        if (stage.errorRate().asFraction() >= ERROR_RUNAWAY_FRACTION) {
            signals.add("the error rate reached " + stage.errorRate().display());
        }

        if (healthiest != null && stage.p95IfPresent().isPresent()
                && healthiest.p95IfPresent().isPresent()) {
            double baseline = healthiest.p95().toNanos();
            double observed = stage.p95().toNanos();
            if (baseline > 0 && observed / baseline >= LATENCY_EXPLOSION_MULTIPLE) {
                signals.add("p95 latency grew to "
                        + Math.round(observed / baseline) + "× its healthy level");
            }
        }

        return signals;
    }
}
