package dev.vortex.core.execution;

import java.util.Objects;

/**
 * A live CPU/memory reading for the run's target, shown alongside {@code currentRate}/{@code
 * currentP95} while a run is in progress.
 *
 * <p>Pre-formatted display strings, deliberately unlike {@link ExecutionProgress#currentRate()}/
 * {@link ExecutionProgress#currentP95()} (which stay as typed domain values and are formatted at the
 * web layer, in {@code RunApiController.toProgressDto}). Those two have a single well-known unit each
 * ({@code RequestsPerSecond}, a latency {@code Duration}); a live resource reading is a used/limit
 * pair ("0.46 / 0.50 cores", "392 / 512 MiB") whose denominator only exists when the target actually
 * has a confirmed resource envelope — there is no typed domain record for that pairing today, and
 * inventing one purely to defer two string-joins to the web layer was not judged worth it here.
 *
 * @param cpu    e.g. {@code "0.46 / 0.50 cores"}, or a used-only display when no limit was confirmed
 * @param memory e.g. {@code "392 / 512 MiB"}, or a used-only display when no limit was confirmed
 */
public record ResourceReading(String cpu, String memory) {
    public ResourceReading {
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
    }
}
