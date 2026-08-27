package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.dynatrace.query.DqlToolSchema;
import com.acltabontabon.vortex.dynatrace.query.DynatraceEntitySearch;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition;
import java.util.List;
import java.util.Objects;

/**
 * Best-effort resolves a Dynatrace service entity id from a name, so configuring an observation
 * source does not require copying an opaque id out of Dynatrace's own UI by hand — see
 * docs/adr/adr-058-dynatrace-entity-lookup-by-name.adoc.
 *
 * <p>A suggestion, never authoritative: the caller always keeps manual entry available regardless of
 * what this returns, matching {@code ObservationSource.serviceIdentifier()}'s own "the adapter's
 * business" contract — this class only ever proposes a value for that field, never fills it in on
 * its own.
 */
public final class DynatraceEntityLookup {

    private final DynatraceMcpClientFactory clients;
    private final DynatraceMcpSettings settings;

    public DynatraceEntityLookup(DynatraceMcpClientFactory clients, DynatraceMcpSettings settings) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public sealed interface LookupResult permits Found, Failed {
    }

    public record Found(List<DynatraceEntitySearch.Candidate> candidates) implements LookupResult {
        public Found {
            candidates = List.copyOf(candidates);
        }
    }

    public record Failed(String problem, String remedy) implements LookupResult {
    }

    public LookupResult lookup(String namePhrase) {
        if (!settings.enabled()) {
            return new Failed("Dynatrace MCP is not enabled.",
                    "Enable it and set the endpoint under Settings first.");
        }
        if (settings.endpoint().isBlank()) {
            return new Failed("No Dynatrace MCP endpoint is configured.",
                    "Set the endpoint under Settings, either by pasting the provided config or "
                            + "entering the URL directly.");
        }

        try (DynatraceTelemetryClient client = clients.openIfConfigured()) {
            var organization = resolveOrganization(client);
            if (organization instanceof OrganizationUnresolved unresolved) {
                return new Failed(unresolved.problem(), unresolved.remedy());
            }
            String organizationValue = ((OrganizationResolved) organization).organization();

            var query = DynatraceEntitySearch.queryFor(namePhrase, organizationValue);
            var outcome = client.call(query, settings.queryTimeout());
            return switch (outcome) {
                case DynatraceTelemetryClient.Answered answered ->
                        new Found(DynatraceEntitySearch.parse(answered.result().payload()));
                case DynatraceTelemetryClient.Failed failed ->
                        new Failed(describe(failed.category()) + ": " + failed.detail(),
                                remedyFor(failed.category()));
            };
        } catch (RuntimeException e) {
            return new Failed("Could not search Dynatrace for a matching entity: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()),
                    "Try Test Connection under Settings for more detail.");
        }
    }

    // ------------------------------------------------------------------ organization

    /** A small, independent adaptation of {@code DynatraceMcpObservationSource.resolveOrganization} —
     *  not shared, since that method's error phrasing ("Could not read production traffic...") is
     *  specific to fetching telemetry, not to searching for an entity. */
    private sealed interface OrganizationResolution permits OrganizationResolved, OrganizationUnresolved {
    }

    private record OrganizationResolved(String organization) implements OrganizationResolution {
    }

    private record OrganizationUnresolved(String problem, String remedy) implements OrganizationResolution {
    }

    private OrganizationResolution resolveOrganization(DynatraceTelemetryClient client) {
        var tools = client.listTools(settings.queryTimeout());
        if (tools instanceof DynatraceTelemetryClient.ToolsFailed toolsFailed) {
            return new OrganizationUnresolved(describe(toolsFailed.category()) + ": " + toolsFailed.detail(),
                    remedyFor(toolsFailed.category()));
        }
        var listed = (DynatraceTelemetryClient.ToolsListed) tools;
        var executeDql = listed.tools().stream()
                .filter(tool -> tool.name().equals(DynatraceQueryDefinition.EXECUTE_DQL_TOOL))
                .findFirst();
        if (executeDql.isEmpty()) {
            return new OrganizationUnresolved(
                    "the server does not advertise '" + DynatraceQueryDefinition.EXECUTE_DQL_TOOL + "'.",
                    remedyFor(DynatraceMcpFailureCategory.MCP_TOOL_UNAVAILABLE));
        }
        var resolution = DqlToolSchema.resolveOrganization(executeDql.get().inputSchema(), settings.organization());
        return switch (resolution) {
            case DqlToolSchema.Resolved resolved -> new OrganizationResolved(resolved.organization());
            case DqlToolSchema.Ambiguous ambiguous -> new OrganizationUnresolved(
                    "this Dynatrace account has " + ambiguous.options().size() + " organizations and none is "
                            + "configured (" + ambiguous.options() + ").",
                    "Pick a Dynatrace organization under Settings → Dynatrace, then Save.");
            case DqlToolSchema.Failed failed -> new OrganizationUnresolved(failed.detail(),
                    remedyFor(DynatraceMcpFailureCategory.AMBIGUOUS_ORGANIZATION));
        };
    }

    // ------------------------------------------------------------------ messages

    private String describe(DynatraceMcpFailureCategory category) {
        return switch (category) {
            case CONNECTION_FAILED -> "Dynatrace MCP could not be reached";
            case AUTHENTICATION_FAILED -> "Dynatrace MCP rejected the credentials Vortex presented";
            case PERMISSION_DENIED -> "the configured identity does not have permission for this query";
            case QUERY_REJECTED -> "Dynatrace rejected the query";
            case QUERY_TIMEOUT -> "the search did not complete in time";
            case INVALID_RESPONSE -> "the response could not be understood";
            case SERVICE_NOT_FOUND -> "no matching entity was found";
            case NO_DATA -> "no data was returned";
            case MCP_TOOL_UNAVAILABLE -> "the server does not offer the tool Vortex needs";
            case AMBIGUOUS_ORGANIZATION -> "Vortex could not choose which Dynatrace organization to query";
        };
    }

    private String remedyFor(DynatraceMcpFailureCategory category) {
        return switch (category) {
            case CONNECTION_FAILED -> "Check the endpoint under Settings is correct and reachable — often the VPN.";
            case AUTHENTICATION_FAILED, PERMISSION_DENIED ->
                    "Check the credential referenced under Settings is set and has the metrics.read scope.";
            case QUERY_TIMEOUT -> "Try a more specific name.";
            case MCP_TOOL_UNAVAILABLE -> "The Dynatrace MCP server does not expose execute_dql yet.";
            case AMBIGUOUS_ORGANIZATION ->
                    "Check the Dynatrace MCP server's execute_dql tool advertises exactly one organization.";
            default -> "Try Test Connection under Settings for more detail.";
        };
    }
}
