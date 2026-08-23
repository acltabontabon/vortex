package dev.vortex.core.port;

import dev.vortex.core.resource.LoadGeneratorResourceBudget;

/**
 * The load generator's resource budget as currently configured, at the moment something asks.
 *
 * <p>A port rather than a direct read of Settings, for the same reason {@link HostInformation} is
 * one: {@code vortex-core} does not depend on how or where a setting is stored, and a run resolving
 * its own budget should not care that the answer today comes from a live, Settings-mutable value.
 */
@FunctionalInterface
public interface LoadGeneratorBudgetProvider {

    LoadGeneratorResourceBudget current();
}
