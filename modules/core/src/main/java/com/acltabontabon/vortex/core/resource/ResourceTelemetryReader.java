package com.acltabontabon.vortex.core.resource;

import com.acltabontabon.vortex.core.metrics.TelemetryCompleteness;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.util.List;

/**
 * Reads back what a {@link ResourceSampleSink} wrote for one execution.
 *
 * <p>The read-side counterpart to {@link ResourceSampleSinkFactory}, kept just as small and for the
 * same reason: everything that knows an execution's raw telemetry lives in a JSON-lines artifact on
 * disk stays in the one adapter that implements this, so {@code RunEvidenceService} only ever asks
 * "what was recorded, and how completely" without knowing where or how.
 */
public interface ResourceTelemetryReader {

    Result read(ExecutionId executionId);

    /**
     * @param completeness whether the artifact this came from describes the whole run, part of it,
     *                     or was never available at all
     * @param samples      every raw sample retained; empty when {@code completeness} is
     *                     {@link TelemetryCompleteness.Status#UNAVAILABLE}
     */
    record Result(TelemetryCompleteness completeness, List<ResourceSample> samples) {
        public Result {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    /** A reader that finds nothing, for tests and for an installation with no telemetry artifacts. */
    static ResourceTelemetryReader unavailable() {
        return executionId -> new Result(TelemetryCompleteness.unavailable(), List.of());
    }
}
