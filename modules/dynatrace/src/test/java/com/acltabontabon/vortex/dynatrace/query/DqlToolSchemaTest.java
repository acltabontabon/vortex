package com.acltabontabon.vortex.dynatrace.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DqlToolSchemaTest {

    @Test
    void aSingleEnumeratedValueIsResolvedAutomatically() {
        var schema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of("my-org"))));

        var resolution = DqlToolSchema.resolveOrganization(schema);

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Resolved.class,
                resolved -> assertThat(resolved.organization()).isEqualTo("my-org"));
    }

    @Test
    void multipleEnumeratedValuesAreRefusedRatherThanGuessed() {
        var schema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of("org-a", "org-b"))));

        var resolution = DqlToolSchema.resolveOrganization(schema);

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> assertThat(failed.detail()).contains("cannot choose one automatically"));
    }

    @Test
    void noOrganizationPropertyIsRefused() {
        var schema = Map.<String, Object>of("properties", Map.of("dqlStatement", Map.of("type", "string")));

        var resolution = DqlToolSchema.resolveOrganization(schema);

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> assertThat(failed.detail()).contains("no 'organization' property"));
    }

    @Test
    void anEmptyEnumIsRefused() {
        var schema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of())));

        var resolution = DqlToolSchema.resolveOrganization(schema);

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> assertThat(failed.detail()).contains("no enumerated values"));
    }
}
