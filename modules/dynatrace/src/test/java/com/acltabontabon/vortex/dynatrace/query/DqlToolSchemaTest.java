package com.acltabontabon.vortex.dynatrace.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DqlToolSchemaTest {

    private static final Map<String, Object> ONE_ORGANIZATION = Map.of(
            "properties", Map.of("organization", Map.of("enum", List.of("my-org"))));

    private static final Map<String, Object> TWO_ORGANIZATIONS = Map.of(
            "properties", Map.of("organization", Map.of("enum", List.of("org-a", "org-b"))));

    @Test
    void aSingleEnumeratedValueIsResolvedAutomaticallyRegardlessOfWhatIsConfigured() {
        assertThat(DqlToolSchema.resolveOrganization(ONE_ORGANIZATION, ""))
                .isInstanceOfSatisfying(DqlToolSchema.Resolved.class,
                        resolved -> assertThat(resolved.organization()).isEqualTo("my-org"));
        assertThat(DqlToolSchema.resolveOrganization(ONE_ORGANIZATION, "something-else"))
                .isInstanceOfSatisfying(DqlToolSchema.Resolved.class,
                        resolved -> assertThat(resolved.organization()).isEqualTo("my-org"));
    }

    @Test
    void multipleEnumeratedValuesWithNothingConfiguredAreOfferedAsOptions() {
        var resolution = DqlToolSchema.resolveOrganization(TWO_ORGANIZATIONS, "");

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Ambiguous.class,
                ambiguous -> assertThat(ambiguous.options()).containsExactly("org-a", "org-b"));
    }

    @Test
    void multipleEnumeratedValuesWithABlankConfiguredValueAreOfferedAsOptions() {
        var resolution = DqlToolSchema.resolveOrganization(TWO_ORGANIZATIONS, "   ");

        assertThat(resolution).isInstanceOf(DqlToolSchema.Ambiguous.class);
    }

    @Test
    void multipleEnumeratedValuesWithAValidConfiguredValueResolveToIt() {
        var resolution = DqlToolSchema.resolveOrganization(TWO_ORGANIZATIONS, "org-b");

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Resolved.class,
                resolved -> assertThat(resolved.organization()).isEqualTo("org-b"));
    }

    @Test
    void multipleEnumeratedValuesWithAStaleConfiguredValueAreRefused() {
        var resolution = DqlToolSchema.resolveOrganization(TWO_ORGANIZATIONS, "org-c");

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> {
                    assertThat(failed.detail()).contains("org-c");
                    assertThat(failed.detail()).contains("Pick again under Settings");
                });
    }

    @Test
    void noOrganizationPropertyIsRefused() {
        var schema = Map.<String, Object>of("properties", Map.of("dqlStatement", Map.of("type", "string")));

        var resolution = DqlToolSchema.resolveOrganization(schema, "");

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> assertThat(failed.detail()).contains("no 'organization' property"));
    }

    @Test
    void anEmptyEnumIsRefused() {
        var schema = Map.<String, Object>of(
                "properties", Map.of("organization", Map.of("enum", List.of())));

        var resolution = DqlToolSchema.resolveOrganization(schema, "");

        assertThat(resolution).isInstanceOfSatisfying(DqlToolSchema.Failed.class,
                failed -> assertThat(failed.detail()).contains("no enumerated values"));
    }
}
