package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ProductionObservationSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.dynatrace.normalize.NormalizedTelemetry;
import com.acltabontabon.vortex.dynatrace.normalize.TelemetryNormalizer;
import com.acltabontabon.vortex.dynatrace.query.DqlToolSchema;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueries;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition;
import java.time.Duration;
import java.util.Objects;

/**
 * Asks Dynatrace what a service receives in production, over MCP instead of Dynatrace's REST API.
 *
 * <p>Same domain contract as the REST {@code DynatraceObservationSource} — same
 * {@link ProductionObservation}, same {@link ObservationProvenance} shape, same "never throw for an
 * expected failure" rule — chosen by {@code CalibrationService} purely because
 * {@link ObservationSource#transport()} says {@code MCP} rather than {@code REST}. Nothing downstream
 * of this class knows MCP was involved.
 *
 * <p>Only throughput feeds {@link ProductionObservation} today: that record has no field for latency
 * percentiles (ADR-017 excludes them deliberately) or a failure-rate fraction, so
 * {@code dynatrace.request-latency.v1} and {@code dynatrace.failure-rate.v1} exist as deterministic,
 * versioned query definitions but are not yet wired into a retrieval — the same scope the REST
 * adapter has today. Widening {@code ProductionObservation} to carry them is a separate, explicit
 * decision this class does not make on its own.
 */
public final class DynatraceMcpObservationSource implements ProductionObservationSource {

    /**
     * The throughput DQL always ends in {@code | summarize peak = max(rate), ...}
     * ({@link com.acltabontabon.vortex.dynatrace.query.DynatraceQueries#THROUGHPUT_V1}), so Dynatrace's
     * own query engine collapses the response to three scalars no matter how many buckets
     * {@code interval:} produces internally — Vortex never receives, or pays transport cost for, a
     * per-bucket array on this path (see ADR-057). Window-scaled {@code ObservationResolution}
     * exists to protect Prometheus and the Dynatrace REST adapter, which do receive and reduce a raw
     * point array themselves; neither concern applies here, so this class ignores whatever resolution
     * {@code CalibrationService} computed and always samples at native, one-minute granularity — the
     * same interval a person querying Dynatrace directly would use, and the only way "peak" means the
     * busiest minute rather than the busiest hour.
     */
    private static final Duration NATIVE_RESOLUTION = Duration.ofMinutes(1);

    private final DynatraceMcpClientFactory clients;
    private final DynatraceMcpSettings settings;
    private final TelemetryNormalizer normalizer = new TelemetryNormalizer();

    public DynatraceMcpObservationSource(DynatraceMcpClientFactory clients, DynatraceMcpSettings settings) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String id() {
        return "dynatrace-mcp";
    }

    @Override
    public boolean supports(ObservationSource source) {
        return source != null && source.kind() == ObservationSource.Kind.DYNATRACE
                && source.transport() == ObservationSource.Transport.MCP;
    }

    @Override
    public Retrieval retrieve(ObservationRequest request) {
        ObservationSource source = request.source();
        Retrieval unavailable = checkAvailable();
        if (unavailable != null) {
            return unavailable;
        }

        try (DynatraceTelemetryClient client = clients.openIfConfigured()) {
            var organization = resolveOrganization(client);
            if (organization instanceof OrganizationResult.Failure failure) {
                return failure.notRetrieved();
            }
            String organizationValue = ((OrganizationResult.Success) organization).organization();

            var query = DynatraceQueries.THROUGHPUT_V1.queryFor(source.serviceIdentifier(),
                    request.window(), NATIVE_RESOLUTION, organizationValue);
            var outcome = client.call(query, settings.queryTimeout());

            var rates = ratesFrom(outcome, DynatraceQueries.THROUGHPUT_V1, request.window(),
                    source.serviceIdentifier());
            if (rates instanceof Failure failure) {
                return failure.toNotRetrieved();
            }
            RateStatistics stats = ((Success) rates).statistics();

            return new Retrieved(new ProductionObservation(
                    RequestsPerSecond.of(stats.average()),
                    RequestsPerSecond.of(stats.p95()),
                    RequestsPerSecond.of(stats.peak()),
                    null, null,
                    NATIVE_RESOLUTION,
                    "Dynatrace MCP (" + source.serviceIdentifier() + ")",
                    Observation.over(request.window().start(), request.window().end()),
                    new ObservationProvenance(id(), DynatraceQueries.THROUGHPUT_V1.id(),
                            source.serviceIdentifier(), ""),
                    "Operation mix is not available over MCP yet."));
        } catch (RuntimeException e) {
            return new NotRetrieved("Could not read production traffic from Dynatrace MCP",
                    e.getMessage() == null ? e.toString() : e.getMessage(),
                    "Try Test Connection under Settings for more detail.");
        }
    }

    @Override
    public Retrieval verify(ObservationSource source, TimeWindow window, Duration resolution) {
        Retrieval unavailable = checkAvailable();
        if (unavailable != null) {
            return unavailable;
        }

        try (DynatraceTelemetryClient client = clients.openIfConfigured()) {
            var organization = resolveOrganization(client);
            if (organization instanceof OrganizationResult.Failure failure) {
                return failure.notRetrieved();
            }
            String organizationValue = ((OrganizationResult.Success) organization).organization();

            var query = DynatraceQueries.THROUGHPUT_V1.queryFor(source.serviceIdentifier(), window,
                    NATIVE_RESOLUTION, organizationValue);
            var outcome = client.call(query, settings.queryTimeout());

            var rates = ratesFrom(outcome, DynatraceQueries.THROUGHPUT_V1, window,
                    source.serviceIdentifier());
            if (rates instanceof Failure failure) {
                return failure.toNotRetrieved();
            }
            RateStatistics stats = ((Success) rates).statistics();

            return new Retrieved(new ProductionObservation(
                    null, null, RequestsPerSecond.of(stats.peak()), null, null, NATIVE_RESOLUTION,
                    "Dynatrace MCP (" + source.serviceIdentifier() + ")",
                    Observation.over(window.start(), window.end()),
                    new ObservationProvenance(id(), DynatraceQueries.THROUGHPUT_V1.id(),
                            source.serviceIdentifier(), ""),
                    ""));
        } catch (RuntimeException e) {
            return new NotRetrieved("Could not reach Dynatrace MCP",
                    e.getMessage() == null ? e.toString() : e.getMessage(),
                    "Try Test Connection under Settings for more detail.");
        }
    }

    // ------------------------------------------------------------------ availability

    private Retrieval checkAvailable() {
        if (!settings.enabled()) {
            return new NotRetrieved("Cannot reach Dynatrace over MCP",
                    "Dynatrace MCP is not enabled.",
                    "Enable it and set the endpoint under Settings first.");
        }
        if (settings.endpoint().isBlank()) {
            return new NotRetrieved("Cannot reach Dynatrace over MCP",
                    "no Dynatrace MCP endpoint is configured.",
                    "Set the endpoint under Settings, either by pasting the provided config or "
                            + "entering the URL directly.");
        }
        return null;
    }

    // ------------------------------------------------------------------ organization resolution

    private sealed interface OrganizationResult permits OrganizationResult.Success, OrganizationResult.Failure {
        record Success(String organization) implements OrganizationResult {
        }

        record Failure(NotRetrieved notRetrieved) implements OrganizationResult {
        }
    }

    /** Resolves {@code execute_dql}'s required {@code organization} argument from the server's own
     *  tool schema, once per opened connection — never hard-coded, never guessed. See
     *  {@link DqlToolSchema}. */
    private OrganizationResult resolveOrganization(DynatraceTelemetryClient client) {
        var tools = client.listTools(settings.queryTimeout());
        if (tools instanceof DynatraceTelemetryClient.ToolsFailed failed) {
            return new OrganizationResult.Failure(new NotRetrieved(
                    "Could not read production traffic from Dynatrace MCP",
                    describe(failed.category()) + ": " + failed.detail(), remedyFor(failed.category())));
        }
        var listed = (DynatraceTelemetryClient.ToolsListed) tools;
        var executeDql = listed.tools().stream()
                .filter(tool -> tool.name().equals(DynatraceQueryDefinition.EXECUTE_DQL_TOOL))
                .findFirst();
        if (executeDql.isEmpty()) {
            return new OrganizationResult.Failure(new NotRetrieved(
                    "Could not read production traffic from Dynatrace MCP",
                    "the server does not advertise '" + DynatraceQueryDefinition.EXECUTE_DQL_TOOL + "'.",
                    remedyFor(DynatraceMcpFailureCategory.MCP_TOOL_UNAVAILABLE)));
        }
        var resolution = DqlToolSchema.resolveOrganization(executeDql.get().inputSchema(), settings.organization());
        return switch (resolution) {
            case DqlToolSchema.Resolved resolved -> new OrganizationResult.Success(resolved.organization());
            case DqlToolSchema.Ambiguous ambiguous -> new OrganizationResult.Failure(new NotRetrieved(
                    "Could not read production traffic from Dynatrace MCP",
                    "this Dynatrace account has " + ambiguous.options().size() + " organizations and none is "
                            + "configured (" + ambiguous.options() + ").",
                    "Pick a Dynatrace organization under Settings → Dynatrace, then Save."));
            case DqlToolSchema.Failed failed -> new OrganizationResult.Failure(new NotRetrieved(
                    "Could not read production traffic from Dynatrace MCP", failed.detail(),
                    remedyFor(DynatraceMcpFailureCategory.AMBIGUOUS_ORGANIZATION)));
        };
    }

    // ------------------------------------------------------------------ shaping the answer

    private sealed interface RateResult permits Success, Failure {
    }

    private record Success(RateStatistics statistics) implements RateResult {
    }

    private record Failure(String what, String why, String remedy) implements RateResult {
        NotRetrieved toNotRetrieved() {
            return new NotRetrieved(what, why, remedy);
        }
    }

    /**
     * Reads {@code peak}/{@code average}/{@code p95} directly from what {@link TelemetryNormalizer}
     * found — Dynatrace's own {@code summarize} pipeline (see {@code Dql#throughput}) already
     * computed these, so Vortex trusts that math rather than re-deriving it from a raw per-bucket
     * series. Coupled to {@code DynatraceQueries#THROUGHPUT_V1} specifically by these literal field
     * names, the only definition currently wired into a live retrieval.
     */
    private RateResult ratesFrom(DynatraceTelemetryClient.TelemetryOutcome outcome,
            DynatraceQueryDefinition definition, TimeWindow window, String entityId) {

        if (outcome instanceof DynatraceTelemetryClient.Failed failed) {
            return new Failure("Could not read production traffic from Dynatrace MCP",
                    describe(failed.category()) + ": " + failed.detail(), remedyFor(failed.category()));
        }
        DynatraceTelemetryResult result = ((DynatraceTelemetryClient.Answered) outcome).result();

        var outcome2 = normalizer.normalize(definition, result, window, entityId);
        if (outcome2 instanceof TelemetryNormalizer.Rejected rejected) {
            return new Failure("Could not read production traffic from Dynatrace MCP",
                    rejected.reason().detail(),
                    "Check observation.serviceIdentifier is this service's Dynatrace entity id, and "
                            + "that the observation window actually had traffic.");
        }
        NormalizedTelemetry telemetry = ((TelemetryNormalizer.Normalized) outcome2).telemetry();

        double peak = telemetry.valuesByField().get("peak").get(0);
        double average = telemetry.valuesByField().get("average").get(0);
        double p95 = telemetry.valuesByField().get("p95").get(0);

        return new Success(new RateStatistics(average, p95, peak));
    }

    private record RateStatistics(double average, double p95, double peak) {
    }

    private String describe(DynatraceMcpFailureCategory category) {
        return switch (category) {
            case CONNECTION_FAILED -> "Dynatrace MCP could not be reached";
            case AUTHENTICATION_FAILED -> "Dynatrace MCP rejected the credentials Vortex presented";
            case PERMISSION_DENIED -> "the configured identity does not have permission for this query";
            case QUERY_REJECTED -> "Dynatrace rejected the query";
            case QUERY_TIMEOUT -> "the query did not complete in time";
            case INVALID_RESPONSE -> "the response could not be understood";
            case SERVICE_NOT_FOUND -> "the entity was not found";
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
            case QUERY_TIMEOUT -> "A thirty-day range can be slow to evaluate. Try a shorter window.";
            case SERVICE_NOT_FOUND -> "Check the entity id is this service's (it starts with SERVICE-).";
            case MCP_TOOL_UNAVAILABLE -> "The Dynatrace MCP server does not expose execute_dql yet.";
            case AMBIGUOUS_ORGANIZATION ->
                    "Check the Dynatrace MCP server's execute_dql tool advertises exactly one organization.";
            default -> "Try Test Connection under Settings for more detail.";
        };
    }
}
