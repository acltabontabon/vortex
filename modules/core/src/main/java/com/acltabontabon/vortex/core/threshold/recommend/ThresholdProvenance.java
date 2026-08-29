package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a threshold's value came from, snapshotted at the moment it was chosen.
 *
 * <p>Mirrors {@code WorkloadSource} in spirit — the same discipline of "the source is visible next
 * to the number, and a manual figure is never upgraded by using it" — but snapshotted rather than
 * live: once a threshold is saved, this is what a report reads back months later, not a fresh call to
 * whatever production observation happens to exist by then. See {@code ThresholdSetProvenance} for
 * how a whole {@code ThresholdSet} carries one of these per objective.
 *
 * @param source              where this number came from
 * @param detail              free text, e.g. a dashboard name or an SLO attribution; shown verbatim
 * @param observedAt          when the underlying evidence was observed, and over what period
 * @param derivation          the arithmetic that produced this value, stated so it can be checked —
 *                            empty for {@link ThresholdSource#MANUAL_OBJECTIVE},
 *                            {@link ThresholdSource#SLO} and {@link ThresholdSource#EXTERNAL_REQUIREMENT},
 *                            which are never calculated
 * @param quality             how much this evidence is worth trusting, at the moment it was derived
 * @param baselineExecutionId the execution this was derived from, when the source is a Vortex
 *                            baseline or a previous execution; empty otherwise
 * @param derivedAt           when this provenance was computed — the anchor for staleness checks
 */
public record ThresholdProvenance(
        ThresholdSource source,
        String detail,
        Observation observedAt,
        String derivation,
        EvidenceQuality quality,
        String baselineExecutionId,
        Instant derivedAt) {

    public ThresholdProvenance {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(derivedAt, "derivedAt");
        detail = detail == null ? "" : detail.trim();
        derivation = derivation == null ? "" : derivation.trim();
        observedAt = observedAt == null ? Observation.unknown() : observedAt;
        baselineExecutionId = baselineExecutionId == null ? "" : baselineExecutionId.trim();
    }

    /** A threshold typed in directly, with nothing behind it. Honest, not a failure state. */
    public static ThresholdProvenance manual(Instant derivedAt) {
        return new ThresholdProvenance(ThresholdSource.MANUAL_OBJECTIVE, "", Observation.unknown(), "",
                EvidenceQuality.LIMITED, "", derivedAt);
    }

    /** A named, externally-defined objective — an SLO or an external requirement, attributed by hand. */
    public static ThresholdProvenance attributed(ThresholdSource source, String attribution, Instant derivedAt) {
        if (source != ThresholdSource.SLO && source != ThresholdSource.EXTERNAL_REQUIREMENT) {
            throw new IllegalArgumentException(
                    "attributed() is only for SLO or EXTERNAL_REQUIREMENT, not " + source);
        }
        return new ThresholdProvenance(source, attribution, Observation.unknown(), "",
                EvidenceQuality.MODERATE, "", derivedAt);
    }

    /**
     * A figure calculated from production evidence or a Vortex baseline.
     *
     * <p>The derivation is required rather than optional, matching {@code WorkloadSource.derived}: a
     * derived number whose arithmetic was not recorded is indistinguishable from one somebody
     * invented.
     */
    public static ThresholdProvenance derived(ThresholdSource source, String detail, Observation observedAt,
            String derivation, EvidenceQuality quality, String baselineExecutionId, Instant derivedAt) {
        if (!source.isDerived()) {
            throw new IllegalArgumentException("derived() is only for a computed source, not " + source);
        }
        if (derivation == null || derivation.isBlank()) {
            throw new IllegalArgumentException("a derived threshold must state its derivation");
        }
        return new ThresholdProvenance(source, detail, observedAt, derivation, quality,
                baselineExecutionId, derivedAt);
    }

    public Optional<String> derivationIfPresent() {
        return derivation.isBlank() ? Optional.empty() : Optional.of(derivation);
    }

    public Optional<String> baselineExecutionIdIfPresent() {
        return baselineExecutionId.isBlank() ? Optional.empty() : Optional.of(baselineExecutionId);
    }

    /** One line describing the provenance, for display beside the value it qualifies. */
    public String describe() {
        return detail.isBlank() ? source.label() : source.label() + " · " + detail;
    }
}
