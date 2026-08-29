package com.acltabontabon.vortex.core.threshold.recommend;

import java.util.Objects;

/**
 * One deterministic observation about a proposed threshold — never an error unless {@code severity}
 * is {@link Severity#INVALID}.
 *
 * @param severity    how seriously to take this
 * @param thresholdId which threshold this is about, matching {@code Threshold.id()}
 * @param message     the finding, stated in plain language including the numbers that produced it
 */
public record SanityFinding(Severity severity, String thresholdId, String message) {

    public SanityFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(thresholdId, "thresholdId");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("a sanity finding must state what it found");
        }
    }
}
