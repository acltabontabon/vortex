package dev.vortex.core.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * Which model produced an interpretation, and with which prompt.
 *
 * <p>Recorded on the analysis rather than the execution, because the measurements remain valid when
 * a run is re-analysed with a better model later. It also makes an old interpretation legible:
 * "generated in March by a 4-billion-parameter local model using prompt v1" is useful context for
 * deciding how much weight to give it.
 *
 * @param provider      e.g. {@code ollama}
 * @param model         the model identifier
 * @param promptVersion the version of the prompt template used
 * @param generatedAt   when the interpretation was produced (UTC)
 * @param durationMs    how long inference took
 */
public record AnalysisProvenance(
        String provider,
        String model,
        String promptVersion,
        Instant generatedAt,
        long durationMs) {

    public AnalysisProvenance {
        Objects.requireNonNull(generatedAt, "generatedAt");
        provider = provider == null ? "unknown" : provider;
        model = model == null ? "unknown" : model;
        promptVersion = promptVersion == null ? "unknown" : promptVersion;
    }

    /** Display line shown alongside every AI-generated section. */
    public String describe() {
        return "Generated locally using " + provider + " · model " + model + " · prompt " + promptVersion;
    }
}
