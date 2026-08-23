package com.acltabontabon.vortex.core.threshold;

import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete set of objectives a test is evaluated against.
 *
 * <p>A test plan without thresholds can still generate traffic, but it cannot produce a verdict —
 * and a performance run with no verdict is a demonstration, not evidence. Vortex therefore treats
 * an empty threshold set as a preflight warning rather than a normal state.
 */
public record ThresholdSet(List<Threshold> thresholds) {

    public ThresholdSet {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        long distinct = thresholds.stream().map(Threshold::id).distinct().count();
        if (distinct != thresholds.size()) {
            throw new IllegalArgumentException(
                    "a threshold set may not declare the same objective twice");
        }
    }

    public static ThresholdSet empty() {
        return new ThresholdSet(List.of());
    }

    public static ThresholdSet of(Threshold... thresholds) {
        return new ThresholdSet(List.of(thresholds));
    }

    /**
     * The objectives Vortex proposes when a user has not chosen their own.
     *
     * <p>These are a starting point for a service with no history, not an organisational standard.
     * They exist so that a first-time user is never blocked on "what should my threshold be?".
     */
    public static ThresholdSet suggestedDefaults() {
        return of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(Percentile.P99, Duration.ofMillis(1000)),
                ErrorRateThreshold.ofPercent(1));
    }

    public boolean isEmpty() {
        return thresholds.isEmpty();
    }

    public int size() {
        return thresholds.size();
    }

    public List<LatencyThreshold> latencyThresholds() {
        return thresholds.stream()
                .filter(LatencyThreshold.class::isInstance)
                .map(LatencyThreshold.class::cast)
                .toList();
    }

    public Optional<ErrorRateThreshold> errorRateThreshold() {
        return overall().stream()
                .filter(ErrorRateThreshold.class::isInstance)
                .map(ErrorRateThreshold.class::cast)
                .findFirst();
    }

    public Optional<Threshold> byId(String id) {
        return thresholds.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** The objectives that apply to the run as a whole. */
    public List<Threshold> overall() {
        return thresholds.stream().filter(t -> t.scope().isOverall()).toList();
    }

    /** The objectives scoped to one particular operation. */
    public List<Threshold> forOperation(OperationId operation) {
        return thresholds.stream()
                .filter(t -> t.scope().operationIfPresent().filter(operation::equals).isPresent())
                .toList();
    }

    /** Every operation that has at least one objective of its own, in declaration order. */
    public List<OperationId> scopedOperations() {
        List<OperationId> operations = new ArrayList<>();
        for (Threshold threshold : thresholds) {
            threshold.scope().operationIfPresent().ifPresent(operation -> {
                if (!operations.contains(operation)) {
                    operations.add(operation);
                }
            });
        }
        return List.copyOf(operations);
    }

    public boolean hasOperationScopedThresholds() {
        return thresholds.stream().anyMatch(t -> !t.scope().isOverall());
    }

    /**
     * This set with {@code overrides} layered on top, matching by threshold identifier.
     *
     * <p>How a workload refines the project's objectives without restating them. A project-wide
     * "p95 below 500 ms" stays in force for every workload that says nothing about p95; a workload
     * that declares its own p95 replaces it, rather than adding a second contradictory objective
     * that could never both hold.
     *
     * <p>Order is preserved: inherited objectives keep their position, and objectives the workload
     * introduces are appended.
     */
    public ThresholdSet mergedWith(ThresholdSet overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        Map<String, Threshold> merged = new LinkedHashMap<>();
        for (Threshold threshold : thresholds) {
            merged.put(threshold.id(), threshold);
        }
        for (Threshold threshold : overrides.thresholds()) {
            merged.put(threshold.id(), threshold);
        }
        return new ThresholdSet(List.copyOf(merged.values()));
    }
}
