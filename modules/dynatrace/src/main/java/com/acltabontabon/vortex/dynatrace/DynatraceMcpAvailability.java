package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A cheap, cached "is Dynatrace MCP reachable" probe for the Settings-page status badge.
 *
 * <p>Not the same job as {@link DynatraceMcpConnectionTest}: that runs fresh on every "Test
 * connection" click, because a stale cached answer right after editing a header would be actively
 * misleading. This exists only for the passive badge shown on every page load, where a 15-second
 * cache avoids reopening a connection on every render — the same cache window
 * {@code OllamaAvailability} uses, without its circuit breaker: there is no repeated-call-storm risk
 * to guard against when the probe fires once per page view rather than once per model call.
 */
public final class DynatraceMcpAvailability {

    private static final Duration CACHE_FOR = Duration.ofSeconds(15);

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
    private final DynatraceMcpClientFactory clients;

    private volatile Availability cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public DynatraceMcpAvailability(DynatraceMcpSettings settings, DynatraceMcpClientFactory clients) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    public Availability check() {
        Instant now = Instant.now();
        Availability snapshot = cached;
        if (snapshot != null && Duration.between(cachedAt, now).compareTo(CACHE_FOR) < 0) {
            return snapshot;
        }
        Availability result = probe();
        cached = result;
        cachedAt = now;
        return result;
    }

    /** Forces the next {@link #check()} to probe again, e.g. right after a settings Save. */
    public void refresh() {
        cachedAt = Instant.EPOCH;
    }

    private Availability probe() {
        if (!settings.enabled()) {
            return Availability.unavailable("Dynatrace MCP is not enabled.",
                    "Turn it on and set the endpoint under Settings.");
        }
        if (settings.endpoint().isBlank()) {
            return Availability.unavailable("No Dynatrace MCP endpoint is configured.",
                    "Paste the endpoint SRE gave you, or enter it manually, under Settings.");
        }
        try (DynatraceTelemetryClient client = clients.openIfConfigured()) {
            if (client == null) {
                return Availability.unavailable("Dynatrace MCP is not configured.",
                        "Set the endpoint under Settings.");
            }
            var tools = client.listTools(Duration.ofSeconds(5));
            return switch (tools) {
                case DynatraceTelemetryClient.ToolsListed ignored -> Availability.ready();
                case DynatraceTelemetryClient.ToolsFailed failed ->
                        Availability.unavailable(failed.detail(), remedyFor(failed.category()));
            };
        } catch (RuntimeException e) {
            DynatraceMcpFailureCategory category = DynatraceMcpFailureClassifier.classify(e);
            return Availability.unavailable(
                    e.getMessage() == null ? category.name() : e.getMessage(), remedyFor(category));
        }
    }

    private String remedyFor(DynatraceMcpFailureCategory category) {
        return switch (category) {
            case CONNECTION_FAILED -> "Check the endpoint is correct and reachable — often the VPN.";
            case AUTHENTICATION_FAILED, PERMISSION_DENIED ->
                    "Check the credential referenced under Settings is set and has permission.";
            case QUERY_TIMEOUT -> "The server did not respond in time. Try again.";
            case MCP_TOOL_UNAVAILABLE -> "The server does not advertise the tool Vortex needs.";
            default -> "Try Test Connection under Settings for more detail.";
        };
    }
}
