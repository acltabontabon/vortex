package dev.vortex.core.resource;

/**
 * Where a telemetry-sampling session sends the raw readings it takes.
 *
 * <p>Deliberately the only thing {@code ObservabilityTelemetryCollector} knows about persistence: the
 * collector emits observations to a sink and has no idea whether they end up in a file, where that
 * file lives, or how it is named. That split is what lets the collector's own sampling logic be
 * tested with an in-memory sink instead of a real artifact store, and keeps artifact-naming and
 * serialization concerns in exactly one adapter.
 */
public interface ResourceSampleSink {

    /** Records one sample. Must not throw for reasons the caller should have to handle — a sink that
     *  cannot persist a sample degrades itself, it does not fail the run it is describing. */
    void accept(ResourceSample sample);

    /**
     * Marks the session over.
     *
     * @param reason null for a normal end; otherwise a short, human-readable cause (for example, a
     *               safety ceiling was reached) that the sink folds into the completeness it records
     *               for anyone reading this artifact back later
     */
    void close(String reason);

    /** A sink that keeps nothing, for tests and for a run with nowhere to persist telemetry to. */
    static ResourceSampleSink discard() {
        return new ResourceSampleSink() {
            @Override
            public void accept(ResourceSample sample) {
            }

            @Override
            public void close(String reason) {
            }
        };
    }
}
