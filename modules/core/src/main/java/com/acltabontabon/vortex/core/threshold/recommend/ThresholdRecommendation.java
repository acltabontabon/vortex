package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.threshold.Durations;
import java.time.Duration;
import java.util.Objects;

/**
 * One evidence-backed candidate value for a threshold, with the label and provenance a "Help me
 * choose" panel shows beside it.
 *
 * @param label      short, intentional name for this strategy — "Balanced", "Production parity",
 *                   "Stricter objective" — never a raw {@link ThresholdSource} constant name
 * @param latencyValue   the candidate value, when this recommends a latency threshold
 * @param errorRateValue the candidate value, when this recommends an error-rate threshold
 * @param provenance where this number came from and how it was derived
 */
public record ThresholdRecommendation(
        String label, Duration latencyValue, ErrorRate errorRateValue, ThresholdProvenance provenance) {

    public ThresholdRecommendation {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(provenance, "provenance");
        if ((latencyValue == null) == (errorRateValue == null)) {
            throw new IllegalArgumentException(
                    "a threshold recommendation carries exactly one of latencyValue or errorRateValue");
        }
    }

    public static ThresholdRecommendation ofLatency(String label, Duration value, ThresholdProvenance provenance) {
        return new ThresholdRecommendation(label, value, null, provenance);
    }

    public static ThresholdRecommendation ofErrorRate(String label, ErrorRate value, ThresholdProvenance provenance) {
        return new ThresholdRecommendation(label, null, value, provenance);
    }

    /** The value formatted for display, whichever metric this recommendation carries. */
    public String displayValue() {
        return latencyValue != null ? Durations.display(latencyValue) : errorRateValue.display();
    }
}
