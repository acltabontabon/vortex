package com.acltabontabon.vortex.dynatrace;

import java.util.Objects;

/**
 * A cheap, cached "is Dynatrace MCP reachable" answer for the Settings-page status badge.
 *
 * <p>Never probes on its own: the local npx/mcp-remote bridge spawns a process and may pop a browser
 * window for OAuth on first use, so this class must never trigger that as a side effect of a passive
 * page view. Instead it reports the last real check a person actually asked for — a "Test connection"
 * click ({@link #recordTestResult}) — and falls back to "not yet checked" until one has happened.
 * {@link #invalidate()} clears that recorded result when the configuration it was measured against
 * changes, e.g. a Settings Save.
 */
public final class DynatraceMcpAvailability {

    /** The passive check's result: whether it is reachable, and if not, why and what to do. */
    public record Availability(boolean available, String problem, String remedy) {
        static Availability ready() {
            return new Availability(true, "", "");
        }

        static Availability unavailable(String problem, String remedy) {
            return new Availability(false, problem, remedy);
        }
    }

    private final DynatraceMcpSettings settings;

    private volatile Availability lastTestResult;

    public DynatraceMcpAvailability(DynatraceMcpSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Availability check() {
        if (!settings.enabled()) {
            return Availability.unavailable("Dynatrace MCP is not enabled.",
                    "Turn it on and set the endpoint under Settings.");
        }
        if (settings.endpoint().isBlank()) {
            return Availability.unavailable("No Dynatrace MCP endpoint is configured.",
                    "Paste the provided config, or enter the endpoint manually, under Settings.");
        }
        Availability recorded = lastTestResult;
        if (recorded != null) {
            return recorded;
        }
        return Availability.unavailable("Not checked yet.", "Use Test Connection under Settings to check it.");
    }

    /** Records what a real "Test connection" click found, so the passive badge reflects it until the
     *  configuration changes again. */
    public void recordTestResult(boolean succeeded, String problem, String remedy) {
        lastTestResult = succeeded ? Availability.ready() : Availability.unavailable(problem, remedy);
    }

    /** Discards any recorded test result — e.g. right after a settings Save, since a prior successful
     *  test no longer speaks for the new configuration. */
    public void invalidate() {
        lastTestResult = null;
    }
}
