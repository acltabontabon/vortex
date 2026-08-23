package com.acltabontabon.vortex.core.resource;

import com.acltabontabon.vortex.core.shared.ExecutionId;

/**
 * Opens a {@link ResourceSampleSink} for one execution.
 *
 * <p>The seam between "the collector samples a run" and "samples are persisted somewhere" — a
 * telemetry collector is constructed once and reused across every run, while where a run's samples
 * go is per-execution. Kept as its own tiny port, rather than injecting {@code ArtifactStore}
 * straight into the collector, so the collector's own constructor stays about what produces resource
 * samples, not how they end up on disk; only the one adapter that implements this factory needs to
 * know about artifacts, execution ids, or JSON.
 */
public interface ResourceSampleSinkFactory {

    ResourceSampleSink open(ExecutionId executionId);

    /** A factory that keeps nothing, for tests and for an installation with nowhere to persist raw
     *  telemetry to. */
    static ResourceSampleSinkFactory none() {
        return executionId -> ResourceSampleSink.discard();
    }
}
