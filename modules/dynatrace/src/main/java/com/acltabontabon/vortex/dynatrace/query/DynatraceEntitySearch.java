package com.acltabontabon.vortex.dynatrace.query;

import com.acltabontabon.vortex.dynatrace.DynatraceTelemetryQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Looks up candidate Dynatrace service entities by name, instead of requiring an entity id to
 * already be known — see docs/adr/adr-058-dynatrace-entity-lookup-by-name.adoc.
 *
 * <p>Not a {@link DynatraceQueryDefinition}: that sealed interface's shape assumes an entity id
 * already exists and a numeric answer comes back for one window — neither applies here. This is its
 * own small, self-contained query-and-parse pair, the same way {@link Dql} isolates DQL grammar from
 * everything that consumes a result.
 */
public final class DynatraceEntitySearch {

    /** Versioned the same way every other Dynatrace query id is — see
     *  {@code DynatraceQueryDefinition}'s own Javadoc on why the version lives in the id. */
    public static final String ID = "dynatrace.entity-search.v1";

    private DynatraceEntitySearch() {
    }

    public static DynatraceTelemetryQuery queryFor(String namePhrase, String organization) {
        return Dql.query(ID, Dql.entitySearch(namePhrase), organization);
    }

    /** One matching entity — {@code id} is what {@code ObservationSource.serviceIdentifier()} needs. */
    public record Candidate(String id, String name) {
    }

    /**
     * Walks the response defensively rather than assuming a fixed envelope (the same reason
     * {@code TelemetryNormalizer} does — an {@code execute_dql} response shape is not contractually
     * documented, see its own Javadoc), collecting every object that carries both an {@code id} and a
     * {@code name} field. Only an {@code id} starting with {@code "SERVICE-"} is accepted — the format
     * {@code ObservationSource}'s own validation message already asserts — as a sanity filter against
     * an unrelated {@code id}-named field elsewhere in the envelope (a request id, a tool-call id, ...)
     * being mistaken for a candidate.
     */
    public static List<Candidate> parse(JsonNode payload) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        collect(payload, candidates, seenIds);
        return List.copyOf(candidates);
    }

    private static void collect(JsonNode node, List<Candidate> candidates, Set<String> seenIds) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode idNode = node.get("id");
            JsonNode nameNode = node.get("name");
            if (idNode != null && idNode.isTextual() && idNode.asText().startsWith("SERVICE-")
                    && nameNode != null && nameNode.isTextual() && seenIds.add(idNode.asText())) {
                candidates.add(new Candidate(idNode.asText(), nameNode.asText()));
            }
            node.fields().forEachRemaining(entry -> collect(entry.getValue(), candidates, seenIds));
        } else if (node.isArray()) {
            node.forEach(element -> collect(element, candidates, seenIds));
        }
    }
}
