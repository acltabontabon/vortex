package com.acltabontabon.vortex.core.resource;

import com.acltabontabon.vortex.core.port.HostInformation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Objects;

/**
 * Turns a {@link LoadGeneratorResourceBudget} into a concrete {@link ResolvedLoadGeneratorBudget} for
 * one specific host.
 *
 * <p>{@link LoadGeneratorResourceBudget.BudgetMode#CUSTOM} needs no computation — it already names a
 * concrete envelope, so resolving it is just attaching the current host's shape for context, with
 * empty reserves (a custom budget makes no reservation of its own; that reasoning is
 * {@link LoadGeneratorResourceBudget.BudgetMode#AUTOMATIC}'s alone). {@code AUTOMATIC} delegates to
 * {@link AutomaticLoadGeneratorAllocation}.
 *
 * <p>Either way, this is the single place that dispatch happens — used identically by a Settings page
 * previewing what would currently apply and by a run resolving what actually will, so there is never
 * a second implementation of "what does this budget mean right now" to keep in sync with the first.
 */
public final class LoadGeneratorResourceBudgetResolver {

    private final HostInformation hostInformation;

    public LoadGeneratorResourceBudgetResolver(HostInformation hostInformation) {
        this.hostInformation = Objects.requireNonNull(hostInformation, "hostInformation");
    }

    /**
     * @param colocatedManagedSut whether this resolution should reason about a Vortex-managed system
     *                            under test sharing this host — the caller's to decide, since only it
     *                            knows (or, for a Settings preview with no run yet, conservatively
     *                            assumes) the target this budget is being resolved for
     */
    public ResolvedLoadGeneratorBudget resolve(LoadGeneratorResourceBudget configured,
            boolean colocatedManagedSut) {
        Objects.requireNonNull(configured, "configured");
        var host = hostInformation.describeHost();

        if (configured.mode() == LoadGeneratorResourceBudget.BudgetMode.CUSTOM) {
            return new ResolvedLoadGeneratorBudget(
                    LoadGeneratorResourceBudget.BudgetMode.CUSTOM,
                    configured.envelope(),
                    host,
                    ResourceEnvelopeRequest.none(),
                    ResourceEnvelopeRequest.none(),
                    colocatedManagedSut);
        }
        return AutomaticLoadGeneratorAllocation.resolve(host, colocatedManagedSut);
    }
}
