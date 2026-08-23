package dev.vortex.core.resource;

import dev.vortex.core.evidence.HostShape;
import dev.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Objects;

/**
 * What a {@link LoadGeneratorResourceBudget} resolved to, for one specific host, at one specific
 * moment.
 *
 * <p>Carries structured facts, never a rendered sentence. A UI or a run's provenance record can
 * always turn "12 cores / 32 GiB detected, 2 cores / 3.2 GiB reserved for the host, 5 cores / 14.4 GiB
 * reserved for a colocated system under test, 4 cores / 4 GiB allocated" into whatever wording fits
 * its surface; baking one wording into the domain would make a future copy change look like a change
 * in what actually happened, and would leave nothing for a second surface (a report, a CLI, a future
 * client) to say it differently.
 *
 * <p>{@code allocation} is always fully present — {@link LoadGeneratorResourceBudget.BudgetMode
 * #AUTOMATIC} names an intent, but resolving one must produce a concrete number, exactly the way
 * resolving an {@code ExecutionTarget} produces a concrete {@code ResolvedTarget}.
 *
 * @param mode                    which {@link LoadGeneratorResourceBudget.BudgetMode} produced this
 * @param allocation              the concrete cpu/memory this resolution produced
 * @param detectedHost            the host the allocation was reasoned from; {@link HostShape#unknown()}
 *                                when nothing could be read, in which case {@code osAndVortexReserve}
 *                                is empty and {@code allocation} falls back to a fixed floor
 * @param osAndVortexReserve      what an {@link LoadGeneratorResourceBudget.BudgetMode#AUTOMATIC}
 *                                resolution held back for the host itself; empty for {@code CUSTOM},
 *                                which makes no reservation of its own
 * @param sutReserve              what an {@code AUTOMATIC} resolution held back for a colocated,
 *                                Vortex-managed system under test; empty when not colocated, or for
 *                                {@code CUSTOM}. Informational only — nothing in this feature enforces
 *                                it against the system under test's own resources
 * @param colocatedWithManagedSut whether this resolution reasoned about a system under test sharing
 *                                this host
 */
public record ResolvedLoadGeneratorBudget(
        LoadGeneratorResourceBudget.BudgetMode mode,
        ResourceEnvelopeRequest allocation,
        HostShape detectedHost,
        ResourceEnvelopeRequest osAndVortexReserve,
        ResourceEnvelopeRequest sutReserve,
        boolean colocatedWithManagedSut) {

    public ResolvedLoadGeneratorBudget {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(allocation, "allocation");
        Objects.requireNonNull(detectedHost, "detectedHost");
        Objects.requireNonNull(osAndVortexReserve, "osAndVortexReserve");
        Objects.requireNonNull(sutReserve, "sutReserve");
        if (allocation.cpuIfPresent().isEmpty() || allocation.memoryIfPresent().isEmpty()) {
            throw new IllegalArgumentException(
                    "a resolved budget must carry a concrete cpu and memory allocation on both axes - "
                            + "resolution exists precisely to turn an intent into a number");
        }
    }
}
