package dev.vortex.app.config;

import dev.vortex.app.VortexProperties;
import dev.vortex.core.port.LoadGeneratorBudgetProvider;
import dev.vortex.core.resource.LoadGeneratorResourceBudget;
import dev.vortex.core.target.CpuAllocation;
import dev.vortex.core.target.MemoryAllocation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The load generator's resource budget, live and mutable — the same {@code Settings → some page}
 * pattern {@code dev.vortex.ai.AiSettings} already uses for the AI model: seeded from {@link
 * VortexProperties} at startup, held in an {@link AtomicReference} so a change from Settings →
 * Load Generator Resources takes effect without a restart.
 *
 * <p>Read only when a run resolves its own budget — never by a run already executing, which already
 * resolved and carries its own snapshot. A change here therefore only ever affects runs that start
 * after it, not one already in flight.
 */
@Component
public class LoadGeneratorBudgetSettings implements LoadGeneratorBudgetProvider {

    private final AtomicReference<LoadGeneratorResourceBudget> current;

    public LoadGeneratorBudgetSettings(VortexProperties properties) {
        this(fromProperties(properties.loadGenerator()));
    }

    private LoadGeneratorBudgetSettings(LoadGeneratorResourceBudget initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /** For tests and other callers that already have a concrete budget in hand and no {@link
     *  VortexProperties} to derive it from. */
    public static LoadGeneratorBudgetSettings seeded(LoadGeneratorResourceBudget initial) {
        return new LoadGeneratorBudgetSettings(initial);
    }

    @Override
    public LoadGeneratorResourceBudget current() {
        return current.get();
    }

    /** Switches the budget in place, effective for the next run that resolves it. */
    public void update(LoadGeneratorResourceBudget budget) {
        current.set(Objects.requireNonNull(budget, "budget"));
    }

    private static LoadGeneratorResourceBudget fromProperties(VortexProperties.LoadGenerator configured) {
        if (!configured.isCustom() || configured.cpuMillicores() == null
                || configured.memoryMebibytes() == null) {
            return LoadGeneratorResourceBudget.automatic();
        }
        return LoadGeneratorResourceBudget.custom(
                CpuAllocation.ofMillicores(configured.cpuMillicores()),
                MemoryAllocation.ofMebibytes(configured.memoryMebibytes()));
    }
}
