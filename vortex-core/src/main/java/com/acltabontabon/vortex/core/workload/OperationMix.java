package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * How a workload's traffic is composed across the operations of the system under test.
 *
 * <p>A production service rarely receives traffic on one endpoint. A mix describes what the service
 * actually experiences: 15% order creation, 25% order lookup, 55% status polling, 5% cancellation,
 * all arriving concurrently from many callers.
 *
 * <p>This is <em>aggregate composition</em>, not a sequence. It says nothing about one caller doing
 * these things in order, and it deliberately cannot: a mix and a flow answer different questions,
 * and for service-level capacity the composition is usually the more faithful model. See
 * {@code docs/03-performance-model/workloads.adoc} (Composition versus sequence).
 *
 * <p>A mix describes <em>shape</em> only and carries no magnitude. The level lives on the
 * {@link LoadShape} as a single total, and {@link RateAllocator} divides that total according to
 * these weights. Keeping shape and magnitude apart is what prevents the most damaging modelling
 * mistake in this product: configuring "100/sec" three times and unintentionally generating 300/sec.
 *
 * <p>Weights are meaningful only for {@link WorkloadModel#OPEN} workloads, where they distribute an
 * arrival rate and therefore describe traffic directly. Under a closed workload they would
 * distribute virtual users, whose throughput depends on each operation's latency — so the same
 * percentages would not produce those percentages of traffic. {@code Workload} enforces that.
 */
public record OperationMix(List<WeightedOperation> entries) {

    public OperationMix {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("an operation mix must contain at least one operation");
        }
        long distinct = entries.stream().map(WeightedOperation::operationId).distinct().count();
        if (distinct != entries.size()) {
            throw new IllegalArgumentException("an operation may appear only once in an operation mix");
        }
    }

    /**
     * A mix of exactly one operation.
     *
     * <p>A first-class case, not a degenerate one. "Can {@code POST /applications} sustain 50
     * submissions per second within its latency objective?" is an ordinary and complete performance
     * question, and Vortex requires no other operation to answer it.
     */
    public static OperationMix single(OperationId operationId) {
        return new OperationMix(List.of(WeightedOperation.of(operationId, 1)));
    }

    public static OperationMix of(List<WeightedOperation> entries) {
        return new OperationMix(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isSingleOperation() {
        return entries.size() == 1;
    }

    public List<OperationId> operationIds() {
        return entries.stream().map(WeightedOperation::operationId).toList();
    }

    public boolean contains(OperationId operationId) {
        return entries.stream().anyMatch(e -> e.operationId().equals(operationId));
    }

    public long totalWeight() {
        return entries.stream().mapToLong(e -> e.weight().value()).sum();
    }

    /**
     * One operation's raw weight, or zero when it is not part of this mix.
     *
     * <p>The weight rather than the share, because this is what an editing form round-trips: showing
     * somebody a computed percentage and then storing it back as a weight would renormalise their
     * numbers every time they saved.
     */
    public int weightFor(OperationId operationId) {
        return entries.stream()
                .filter(entry -> entry.operationId().equals(operationId))
                .mapToInt(entry -> entry.weight().value())
                .findFirst()
                .orElse(0);
    }

    /**
     * Each operation's share of total traffic as a fraction of one, to six decimal places.
     *
     * <p>Weights are relative, so {@code 60/30/10} and {@code 6/3/1} both yield
     * {@code 0.6 / 0.3 / 0.1}.
     */
    public SequencedMap<OperationId, BigDecimal> shares() {
        BigDecimal total = BigDecimal.valueOf(totalWeight());
        SequencedMap<OperationId, BigDecimal> shares = new LinkedHashMap<>();
        for (WeightedOperation entry : entries) {
            shares.put(entry.operationId(),
                    BigDecimal.valueOf(entry.weight().value()).divide(total, 6, RoundingMode.HALF_UP));
        }
        return shares;
    }

    /** One operation's share as a display percentage: {@code 60}, {@code 33.3}, {@code 100}. */
    public String sharePercent(OperationId operationId) {
        BigDecimal share = shares().get(operationId);
        Objects.requireNonNull(share, "operation is not part of this mix: " + operationId);
        return Percentages.display(share);
    }
}
