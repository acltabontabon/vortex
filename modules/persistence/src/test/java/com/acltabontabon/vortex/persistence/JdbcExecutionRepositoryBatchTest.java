package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.FailureReason;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.plan.ExperimentIdentity;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The batched paths used at start-up reconciliation, exercised against real SQLite.
 *
 * <p>{@code ExecutionService.reconcileUnfinished()} and {@code
 * ExecutionService.reconcileExperimentIdentity()} used to write one row at a time in a loop; these
 * cover the batched replacement so a regression there is caught here rather than only showing up
 * as a slow start-up against a large execution history.
 */
class JdbcExecutionRepositoryBatchTest {

    private JdbcExecutionRepository executions;
    private JdbcProjectRepository projects;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        VortexWorkspace workspace = new VortexWorkspace(directory).ensureExists();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(workspace.jdbcUrl());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        projects = new JdbcProjectRepository(jdbc);
        executions = new JdbcExecutionRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));

        projects.save(Fixtures.project());
    }

    private TestExecution runningExecution(String id) {
        return TestExecution.create(ExecutionId.of(id), Fixtures.plan(), Fixtures.NOW)
                .transitionTo(ExecutionState.VALIDATING, Fixtures.NOW)
                .transitionTo(ExecutionState.READY, Fixtures.NOW)
                .transitionTo(ExecutionState.STARTING, Fixtures.NOW)
                .transitionTo(ExecutionState.RUNNING, Fixtures.NOW);
    }

    @Test
    @DisplayName("saveAll upserts every execution in the batch")
    void saveAllUpsertsEveryRow() {
        var batch = executions.saveAll(List.of(
                runningExecution("exec1"), runningExecution("exec2"), runningExecution("exec3")));

        assertThat(batch).hasSize(3);
        assertThat(executions.findRecent(10))
                .extracting(execution -> execution.id().value())
                .containsExactlyInAnyOrder("exec1", "exec2", "exec3");
    }

    @Test
    @DisplayName("saveAll on an already-stored row updates it in place")
    void saveAllUpdatesAnExistingRow() {
        executions.save(runningExecution("exec1"));

        TestExecution failed = runningExecution("exec1").failed(
                FailureReason.INTERRUPTED, "interrupted", Fixtures.NOW);
        executions.saveAll(List.of(failed));

        assertThat(executions.findById(ExecutionId.of("exec1")).orElseThrow().state())
                .isEqualTo(ExecutionState.FAILED);
        assertThat(executions.findRecent(10)).hasSize(1);
    }

    @Test
    @DisplayName("saveAll with nothing to save touches the database not at all")
    void saveAllWithNoExecutionsIsANoOp() {
        assertThat(executions.saveAll(List.of())).isEmpty();
        assertThat(executions.findRecent(10)).isEmpty();
    }

    @Test
    @DisplayName("reindexExperimentFingerprints only rewrites rows whose fingerprint actually changed")
    void reindexExperimentFingerprintsRewritesOnlyChangedRows() {
        executions.save(runningExecution("exec1"));
        executions.save(runningExecution("exec2"));
        String current = ExperimentIdentity.fingerprintOf(Fixtures.plan()).hash();

        Map<ExecutionId, String> updates = new LinkedHashMap<>();
        updates.put(ExecutionId.of("exec1"), "a-new-fingerprint");
        // exec2 is passed with its already-current value, mirroring how reconcileExperimentIdentity
        // only includes rows it detected as different — this is here to prove the WHERE guard,
        // not the caller's own filtering, is what keeps a no-op row from counting as changed.
        updates.put(ExecutionId.of("exec2"), current);

        int changed = executions.reindexExperimentFingerprints(updates);

        assertThat(changed).isEqualTo(1);
        assertThat(executions.findExperimentIndexes())
                .filteredOn(indexed -> indexed.id().equals(ExecutionId.of("exec1")))
                .singleElement()
                .satisfies(indexed -> assertThat(indexed.indexedFingerprint())
                        .isEqualTo("a-new-fingerprint"));
    }

    @Test
    @DisplayName("reindexExperimentFingerprints with nothing to change touches the database not at all")
    void reindexExperimentFingerprintsWithNothingToChangeIsANoOp() {
        assertThat(executions.reindexExperimentFingerprints(Map.of())).isZero();
    }
}
