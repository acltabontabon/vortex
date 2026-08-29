package com.acltabontabon.vortex.core.application;

import java.time.Instant;
import java.util.Objects;

/**
 * One point in a threshold's history — a value that was actually tested against, and when.
 *
 * @param executionId the run this value was configured for
 * @param value       the threshold's plain-language value at the time, e.g. {@code 550 ms}
 * @param at          when that run was requested
 */
public record ThresholdHistoryEntry(String executionId, String value, Instant at) {

    public ThresholdHistoryEntry {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(at, "at");
    }
}
