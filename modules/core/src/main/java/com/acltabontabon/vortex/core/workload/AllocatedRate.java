package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentages;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The share of a workload's total arrival rate assigned to one operation.
 *
 * <p>This type cannot be constructed outside this package. That is deliberate: the only legitimate
 * way to obtain a per-operation rate is to ask {@link RateAllocator} to divide a total, which
 * guarantees the parts always sum to (approximately) the whole.
 *
 * <p>The mistake this prevents is subtle and expensive. Given a mix of 60/30/10 and a total of
 * 100 requests/sec, it is easy to configure three load-generator workloads at 100/sec each and
 * unknowingly run a 300/sec test — then conclude the service is three times stronger, or three
 * times weaker, than it is.
 */
public final class AllocatedRate {

    private final OperationId operationId;
    private final RequestsPerSecond rate;
    private final BigDecimal share;

    AllocatedRate(OperationId operationId, RequestsPerSecond rate, BigDecimal share) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.rate = Objects.requireNonNull(rate, "rate");
        this.share = Objects.requireNonNull(share, "share");
    }

    public OperationId operationId() {
        return operationId;
    }

    /** This operation's absolute arrival rate. */
    public RequestsPerSecond rate() {
        return rate;
    }

    /** This operation's fraction of total traffic, in the range (0, 1]. */
    public BigDecimal share() {
        return share;
    }

    /** This operation's share as a display percentage: {@code 60}, {@code 33.3}, {@code 100}. */
    public String sharePercent() {
        return Percentages.display(share);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AllocatedRate a
                && operationId.equals(a.operationId)
                && rate.equals(a.rate)
                && share.compareTo(a.share) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationId, rate, share.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return operationId + " -> " + rate.displayWithUnit();
    }
}
