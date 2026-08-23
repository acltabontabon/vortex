package dev.vortex.app.web;

import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.AllocatedRate;
import dev.vortex.core.workload.RateAllocation;
import dev.vortex.core.workload.RateAllocator;
import dev.vortex.core.workload.Workload;
import dev.vortex.core.workload.WorkloadModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns a workload into the rows the interface draws.
 *
 * <p>One place does this so that the workload page, the evaluation page, the preflight screen and
 * the editor's live preview all show the same numbers. They previously did not exist at all outside
 * preflight, which is why "120 requests/sec across four operations" was a figure the user had to
 * divide in their head.
 *
 * <h2>The rates come from the real allocator</h2>
 * {@link RateAllocator} apportions by largest remainder with a floor of one unit per operation, and
 * reports the rounding drift it could not avoid. Computing the split in JavaScript for the editor
 * preview would have been easy and would have produced numbers that quietly disagree with the ones
 * the run actually drives — so the preview is a server round trip instead.
 */
@Component("workloads")
public class WorkloadView {

    private final RateAllocator rateAllocator;

    public WorkloadView(RateAllocator rateAllocator) {
        this.rateAllocator = Objects.requireNonNull(rateAllocator, "rateAllocator");
    }

    /**
     * One operation's part of a workload.
     *
     * @param share   percentage of the service's traffic, already formatted
     * @param rate    the requests per second this operation receives, absent under a concurrency
     *                workload where there is no traffic total to divide
     * @param known   whether the operation is in the imported API description. An unknown operation
     *                is shown rather than hidden: it is the reason the workload will not run
     */
    public record Row(String operationId, String label, String method, String path, String share,
            BigDecimal shareFraction, Optional<RequestsPerSecond> rate, boolean known) {

        public String rateDisplay() {
            return rate.map(RequestsPerSecond::display).orElse("—");
        }

        /**
         * The share with its unit, which is the only form worth showing.
         *
         * <p>OperationMix formats the number alone ("55"); a bare 55 beside a rate of 66 is two
         * numbers a reader has to work out the meaning of.
         */
        public String sharePercent() {
            return share + "%";
        }

        /** Bar width as a percentage string, for the inline mix visualisation. */
        public String barWidth() {
            return shareFraction.multiply(BigDecimal.valueOf(100))
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
        }
    }

    /**
     * How a workload's total traffic divides across its operations.
     *
     * @param rows        one per operation, in configured order
     * @param drift       the rounding the allocator could not avoid, or empty when exact. Shown
     *                    rather than hidden, because a total that does not add up is the first thing
     *                    a careful reader checks
     * @param concurrency true when this is a closed workload, where the rows describe which single
     *                    operation the virtual users drive rather than a division of traffic
     */
    public record Composition(List<Row> rows, Optional<BigDecimal> drift, boolean concurrency) {

        public boolean hasUnknownOperations() {
            return rows.stream().anyMatch(row -> !row.known());
        }
    }

    public Composition compose(Workload workload, ServiceCatalog catalog) {
        Objects.requireNonNull(workload, "workload");

        boolean open = workload.model() == WorkloadModel.OPEN;
        RateAllocation allocation = open
                ? rateAllocator.allocate((RequestsPerSecond) workload.peakLevel(),
                        workload.operations())
                : null;

        List<Row> rows = new ArrayList<>();
        for (OperationId id : workload.operations().operationIds()) {
            Optional<Operation> found = catalog == null ? Optional.empty() : catalog.find(id);
            Optional<RequestsPerSecond> rate = allocation == null
                    ? Optional.empty()
                    : allocation.forOperation(id).map(AllocatedRate::rate);

            rows.add(new Row(
                    id.value(),
                    found.map(Operation::label).orElse(id.value()),
                    found.map(operation -> operation.method().name()).orElse(""),
                    found.map(Operation::path).orElse(""),
                    workload.operations().sharePercent(id),
                    workload.operations().shares().getOrDefault(id, BigDecimal.ZERO),
                    rate,
                    found.isPresent()));
        }

        Optional<BigDecimal> drift = allocation != null && !allocation.isExact()
                ? Optional.of(allocation.roundingDrift())
                : Optional.empty();

        return new Composition(List.copyOf(rows), drift, !open);
    }

    /**
     * The composition of a plan that has already been resolved.
     *
     * <p>Preflight and the evidence pages read the plan rather than the definition, because the plan
     * is what will actually run. They are normally the same; when they are not — somebody edited the
     * workload after the plan was resolved — showing the definition would describe a test that is not
     * about to happen.
     */
    public Composition compose(EffectiveTestPlan plan) {
        Objects.requireNonNull(plan, "plan");
        boolean open = plan.workloadModel() == WorkloadModel.OPEN;

        List<Row> rows = new ArrayList<>();
        for (PlannedOperation operation : plan.operations()) {
            rows.add(new Row(
                    operation.operationId().value(),
                    operation.name(),
                    "",
                    operation.name(),
                    operation.sharePercent(),
                    operation.share(),
                    operation.arrivalRateIfPresent(),
                    true));
        }
        // A resolved plan carries no drift: the allocation already happened and was recorded.
        return new Composition(List.copyOf(rows), Optional.empty(), !open);
    }

    /**
     * A one-line summary of the traffic, for lists and headers.
     *
     * <p>Deliberately states the unit every time. "120" alone is the figure a reader has to go
     * looking for the meaning of, and requests per second and virtual users are not comparable
     * quantities.
     */
    public String headline(Workload workload) {
        String level = workload.model() == WorkloadModel.OPEN
                ? workload.peakLevel().display() + " requests/sec"
                : workload.peakLevel().display() + " concurrent users";
        if (workload.shape().isRamping()) {
            level = "ramping to " + level;
        }
        return level;
    }
}
