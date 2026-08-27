package com.acltabontabon.vortex.dynatrace.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DynatraceEntitySearchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode parse(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void queryForBuildsTheExecuteDqlToolCall() {
        var query = DynatraceEntitySearch.queryFor("checkout", "my-org");

        assertThat(query.toolName()).isEqualTo("execute_dql");
        assertThat(query.arguments()).containsEntry("organization", "my-org");
        assertThat(query.arguments().get("dqlStatement").toString())
                .contains("fetch dt.entity.service")
                .contains("scanLimitGBytes: 1")
                .contains("matchesPhrase(entity.name, \"checkout\")")
                .contains("fields id, name = entity.name")
                .contains("limit 20");
    }

    @Test
    void parseCollectsCandidatesFromABareArray() throws Exception {
        var payload = parse("""
                [{"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"},
                 {"id":"SERVICE-AAAAAAAAAAAAAAAA","name":"checkout-service-canary"}]""");

        var candidates = DynatraceEntitySearch.parse(payload);

        assertThat(candidates).containsExactlyInAnyOrder(
                new DynatraceEntitySearch.Candidate("SERVICE-1A2B3C4D5E6F7890", "checkout-service"),
                new DynatraceEntitySearch.Candidate("SERVICE-AAAAAAAAAAAAAAAA", "checkout-service-canary"));
    }

    @Test
    void parseFindsCandidatesNestedUnderARecordsEnvelope() throws Exception {
        var payload = parse("""
                {"records": [{"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"}]}""");

        var candidates = DynatraceEntitySearch.parse(payload);

        assertThat(candidates).containsExactly(
                new DynatraceEntitySearch.Candidate("SERVICE-1A2B3C4D5E6F7890", "checkout-service"));
    }

    @Test
    void parseIgnoresAnUnrelatedIdFieldThatDoesNotLookLikeAServiceEntity() throws Exception {
        var payload = parse("""
                {"requestId": "abc-123", "name": "not a service", "records": [
                  {"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"}
                ]}""");

        var candidates = DynatraceEntitySearch.parse(payload);

        assertThat(candidates).containsExactly(
                new DynatraceEntitySearch.Candidate("SERVICE-1A2B3C4D5E6F7890", "checkout-service"));
    }

    @Test
    void parseDeduplicatesRepeatedIds() throws Exception {
        var payload = parse("""
                [{"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"},
                 {"id":"SERVICE-1A2B3C4D5E6F7890","name":"checkout-service"}]""");

        var candidates = DynatraceEntitySearch.parse(payload);

        assertThat(candidates).hasSize(1);
    }

    @Test
    void parseOfEmptyResultsYieldsNoCandidates() throws Exception {
        assertThat(DynatraceEntitySearch.parse(parse("[]"))).isEmpty();
        assertThat(DynatraceEntitySearch.parse(parse("{\"records\": []}"))).isEmpty();
    }
}
