package com.acltabontabon.vortex.core.metrics;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The outcomes a run produced, rather than a single count of things that went wrong.
 *
 * <p>An error rate answers "how much broke?". It cannot answer "what broke, and does it belong in a
 * capacity conclusion?" — and that second question decides whether a run found a limit or found a
 * misconfigured workload.
 *
 * <h2>Absence is not success</h2>
 * {@link #isEmpty()} means no outcome was classified: an engine that reported no status information,
 * or an execution recorded before this was collected. It must never be read as "every request
 * succeeded". The run's {@code errorRate} is unchanged and still authoritative for how much failed;
 * this record only says what kind.
 *
 * <h2>Why raw codes are strings the adapter chose</h2>
 * {@code byCode} carries whatever the engine called each outcome — {@code "200"}, {@code "503"},
 * {@code "1211"}. The core never parses, ranges over or reasons about those keys; it reasons over
 * {@link ResponseClass} and {@link FailureClass}, which the adapter assigned. That is what lets a
 * status distribution exist without HTTP's vocabulary reaching a module that must stay
 * transport-neutral.
 *
 * @param byResponseClass answers received, by class
 * @param byFailureClass  failures, by how they failed
 * @param byCode          the engine's own code for each outcome, and how many carried it
 * @param total           outcomes classified — not necessarily the run's request count
 */
public record ReliabilityBreakdown(
        Map<ResponseClass, Long> byResponseClass,
        Map<FailureClass, Long> byFailureClass,
        Map<String, Long> byCode,
        long total) {

    public ReliabilityBreakdown {
        byResponseClass = copyOf(byResponseClass, ResponseClass.class);
        byFailureClass = copyOf(byFailureClass, FailureClass.class);
        byCode = byCode == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(byCode));
        if (total < 0) {
            throw new IllegalArgumentException("classified outcome count must not be negative");
        }
    }

    /** Nothing was classified. Distinct from "everything succeeded" — see the class comment. */
    public static ReliabilityBreakdown notReported() {
        return new ReliabilityBreakdown(Map.of(), Map.of(), Map.of(), 0);
    }

    /** Whether the engine reported anything Vortex could classify. */
    public boolean wasReported() {
        return total > 0 || !byResponseClass.isEmpty() || !byFailureClass.isEmpty();
    }

    public boolean isEmpty() {
        return !wasReported();
    }

    public long count(ResponseClass responseClass) {
        return byResponseClass.getOrDefault(responseClass, 0L);
    }

    public long count(FailureClass failureClass) {
        return byFailureClass.getOrDefault(failureClass, 0L);
    }

    /** Every failure the run classified, however it failed. */
    public long failures() {
        return byFailureClass.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * The share of classified outcomes in a failure class.
     *
     * <p>Absent when nothing was classified, rather than zero: a fraction of a denominator nobody
     * measured is not a measurement.
     */
    public Optional<Double> share(FailureClass failureClass) {
        return total == 0 ? Optional.empty() : Optional.of((double) count(failureClass) / total);
    }

    /**
     * The share of classified outcomes that failed without the target answering.
     *
     * <p>The input to {@code TARGET_UNAVAILABLE_DURING_RUN}. Absent when nothing was classified, so
     * that rule cannot fire on an absence.
     */
    public Optional<Double> unreachedShare() {
        if (total == 0) {
            return Optional.empty();
        }
        long unreached = count(FailureClass.TIMEOUT) + count(FailureClass.CONNECTION);
        return Optional.of((double) unreached / total);
    }

    private static <E extends Enum<E>> Map<E, Long> copyOf(Map<E, Long> source, Class<E> type) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<E, Long> copy = new EnumMap<>(type);
        source.forEach((key, value) -> {
            if (key != null && value != null && value != 0) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}
