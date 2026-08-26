package com.acltabontabon.vortex.dynatrace.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DynatraceQueryDefinitionTest {

    private static final TimeWindow WINDOW =
            new TimeWindow(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"));

    @Test
    void everyDefinitionSendsDqlStatementAndOrganizationToExecuteDql() {
        for (var definition : DynatraceQueries.baseline()) {
            var query = definition.queryFor("SERVICE-1", WINDOW, Duration.ofMinutes(1), "my-org");

            assertThat(query.toolName()).isEqualTo("execute_dql");
            assertThat(query.arguments()).containsKey("dqlStatement");
            assertThat(query.arguments()).containsEntry("organization", "my-org");
            assertThat(query.arguments()).doesNotContainKey("query");
        }
    }
}
