package dev.vortex.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Execution history.
 *
 * <p>The plan, the measurements and the deterministic summary are stored as JSON documents; a
 * handful of columns beside them are denormalised so the history list can be rendered without
 * deserialising every row.
 *
 * <p>The plan is stored with the execution rather than referenced from the project's current
 * configuration. That is the whole point of an effective plan: reopening a run from six months ago
 * must show the test that ran, not the test today's configuration would produce.
 */
public final class JdbcExecutionRepository implements ExecutionRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO executions (id, project_id, state, verdict, workload_name, test_type,
                                    environment_name, classification, service_version,
                                    plan_fingerprint, requested_at, started_at, finished_at,
                                    plan_json, results_json, summary_json, tool_versions_json,
                                    artifacts_json, failure_reason, failure_detail,
                                    run_quality, run_quality_json)
            VALUES (:id, :projectId, :state, :verdict, :workloadName, :testType,
                    :environmentName, :classification, :serviceVersion, :fingerprint,
                    :requestedAt, :startedAt, :finishedAt, :planJson, :resultsJson, :summaryJson,
                    :toolVersionsJson, :artifactsJson, :failureReason, :failureDetail,
                    :runQuality, :runQualityJson)
            ON CONFLICT (id) DO UPDATE SET
                state = excluded.state,
                verdict = excluded.verdict,
                started_at = excluded.started_at,
                finished_at = excluded.finished_at,
                results_json = excluded.results_json,
                summary_json = excluded.summary_json,
                tool_versions_json = excluded.tool_versions_json,
                artifacts_json = excluded.artifacts_json,
                failure_reason = excluded.failure_reason,
                failure_detail = excluded.failure_detail,
                run_quality = excluded.run_quality,
                run_quality_json = excluded.run_quality_json
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchJdbc;
    private final ObjectMapper json = JsonDocuments.mapper();

    public JdbcExecutionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    @Override
    public TestExecution save(TestExecution execution) {
        jdbc.sql(UPSERT_SQL)
                .param("id", execution.id().value())
                .param("projectId", execution.projectId().value())
                .param("state", execution.state().name())
                .param("verdict", execution.verdict().name())
                .param("workloadName", execution.plan().workloadName())
                .param("testType", execution.plan().testType().name())
                .param("environmentName", execution.plan().environmentName())
                .param("classification", execution.plan().classification().name())
                .param("serviceVersion", execution.plan().serviceVersion())
                .param("fingerprint", execution.plan().fingerprint() == null ? ""
                        : execution.plan().fingerprint().hash())
                .param("requestedAt", execution.requestedAt().toString())
                .param("startedAt", execution.startedAt() == null ? null
                        : execution.startedAt().toString())
                .param("finishedAt", execution.finishedAt() == null ? null
                        : execution.finishedAt().toString())
                .param("planJson", write(execution.plan()))
                .param("resultsJson", execution.results() == null ? null : write(execution.results()))
                .param("summaryJson", execution.summary() == null ? null : write(execution.summary()))
                .param("toolVersionsJson", write(execution.toolVersions()))
                .param("artifactsJson", write(execution.artifacts()))
                .param("failureReason", execution.failureReason() == null ? null
                        : execution.failureReason().name())
                .param("failureDetail", execution.failureDetail())
                // The grade is promoted so a baseline lookup can exclude an invalid run without
                // deserialising every candidate; the findings behind it stay content.
                .param("runQuality", execution.quality().quality().name())
                .param("runQualityJson", write(execution.quality()))
                .update();
        return execution;
    }

    /**
     * Upserts many executions with one batched statement instead of one round trip per row.
     *
     * <p>Used at start-up, when a previous process's orphaned runs are all marked {@code FAILED}
     * at once — a moment whose cost should not grow linearly with how much history a team has
     * accumulated.
     */
    @Override
    public List<TestExecution> saveAll(List<TestExecution> executions) {
        if (executions.isEmpty()) {
            return executions;
        }
        SqlParameterSource[] batch = executions.stream()
                .map(this::paramsFor)
                .toArray(SqlParameterSource[]::new);
        batchJdbc.batchUpdate(UPSERT_SQL, batch);
        return executions;
    }

    private SqlParameterSource paramsFor(TestExecution execution) {
        return new MapSqlParameterSource()
                .addValue("id", execution.id().value())
                .addValue("projectId", execution.projectId().value())
                .addValue("state", execution.state().name())
                .addValue("verdict", execution.verdict().name())
                .addValue("workloadName", execution.plan().workloadName())
                .addValue("testType", execution.plan().testType().name())
                .addValue("environmentName", execution.plan().environmentName())
                .addValue("classification", execution.plan().classification().name())
                .addValue("serviceVersion", execution.plan().serviceVersion())
                .addValue("fingerprint", execution.plan().fingerprint() == null ? ""
                        : execution.plan().fingerprint().hash())
                .addValue("requestedAt", execution.requestedAt().toString())
                .addValue("startedAt", execution.startedAt() == null ? null
                        : execution.startedAt().toString())
                .addValue("finishedAt", execution.finishedAt() == null ? null
                        : execution.finishedAt().toString())
                .addValue("planJson", write(execution.plan()))
                .addValue("resultsJson", execution.results() == null ? null : write(execution.results()))
                .addValue("summaryJson", execution.summary() == null ? null : write(execution.summary()))
                .addValue("toolVersionsJson", write(execution.toolVersions()))
                .addValue("artifactsJson", write(execution.artifacts()))
                .addValue("failureReason", execution.failureReason() == null ? null
                        : execution.failureReason().name())
                .addValue("failureDetail", execution.failureDetail())
                .addValue("runQuality", execution.quality().quality().name())
                .addValue("runQualityJson", write(execution.quality()));
    }

    @Override
    public Optional<TestExecution> findById(ExecutionId id) {
        return jdbc.sql("SELECT * FROM executions WHERE id = :id")
                .param("id", id.value())
                .query(this::map)
                .optional();
    }

    @Override
    public List<TestExecution> findByProject(ProjectId projectId, int limit) {
        return jdbc.sql("""
                SELECT * FROM executions
                WHERE project_id = :projectId
                ORDER BY requested_at DESC
                LIMIT :limit
                """)
                .param("projectId", projectId.value())
                .param("limit", Math.clamp(limit, 1, 500))
                .query(this::map)
                .list();
    }

    @Override
    public List<TestExecution> findRecent(int limit) {
        return jdbc.sql("SELECT * FROM executions ORDER BY requested_at DESC LIMIT :limit")
                .param("limit", Math.clamp(limit, 1, 500))
                .query(this::map)
                .list();
    }

    /**
     * Runs left mid-flight by a previous process.
     *
     * <p>Vortex does not adopt orphaned engine processes on restart, so these are marked failed
     * rather than resumed. Showing a run as still going when nothing is running it would make
     * history untrustworthy, and quietly resuming could point a second load generator at somebody's
     * environment.
     */
    @Override
    public List<TestExecution> findUnfinished() {
        return jdbc.sql("""
                SELECT * FROM executions
                WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                ORDER BY requested_at
                """)
                .query(this::map)
                .list();
    }

    @Override
    public long countByProject(ProjectId projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM executions WHERE project_id = :projectId")
                .param("projectId", projectId.value())
                .query(Long.class)
                .single();
    }

    @Override
    public List<TestExecution> findCompatible(ProjectId projectId, String experimentFingerprint,
            Instant before, int limit) {

        if (experimentFingerprint == null || experimentFingerprint.isBlank()) {
            return List.of();
        }
        // The bound is applied in SQL rather than by filtering afterwards, so asking for "the
        // previous run" never depends on how many later ones happen to exist.
        return jdbc.sql("""
                SELECT * FROM executions
                WHERE project_id = :projectId
                  AND plan_fingerprint = :fingerprint
                  AND state = 'COMPLETED'
                  AND (:before IS NULL OR requested_at < :before)
                ORDER BY requested_at DESC
                LIMIT :limit
                """)
                .param("projectId", projectId.value())
                .param("fingerprint", experimentFingerprint)
                .param("before", before == null ? null : before.toString())
                .param("limit", Math.clamp(limit, 1, 100))
                .query(this::map)
                .list();
    }

    @Override
    public List<ExecutionRepository.ExperimentIndex> findExperimentIndexes() {
        return jdbc.sql("SELECT id, plan_json, plan_fingerprint FROM executions")
                .query((rs, rowNum) -> new ExecutionRepository.ExperimentIndex(
                        ExecutionId.of(rs.getString("id")),
                        read(rs.getString("plan_json"), EffectiveTestPlan.class),
                        rs.getString("plan_fingerprint")))
                .list();
    }

    @Override
    public boolean reindexExperimentFingerprint(ExecutionId id, String experimentFingerprint) {
        String fingerprint = experimentFingerprint == null ? "" : experimentFingerprint;
        // Guarded in SQL rather than by reading first, so a startup pass over an unchanged
        // workspace writes nothing at all and reports honestly that it re-indexed nothing.
        return jdbc.sql("""
                UPDATE executions SET plan_fingerprint = :fingerprint
                WHERE id = :id AND plan_fingerprint <> :fingerprint
                """)
                .param("fingerprint", fingerprint)
                .param("id", id.value())
                .update() > 0;
    }

    /**
     * Rewrites many indexed fingerprints with one batched statement.
     *
     * <p>Used at start-up to reconcile the whole identity index in one round trip rather than one
     * {@code UPDATE} per changed row.
     */
    @Override
    public int reindexExperimentFingerprints(Map<ExecutionId, String> newFingerprints) {
        if (newFingerprints.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = newFingerprints.entrySet().stream()
                .map(entry -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("fingerprint", entry.getValue() == null ? "" : entry.getValue())
                        .addValue("id", entry.getKey().value()))
                .toArray(SqlParameterSource[]::new);
        int[] counts = batchJdbc.batchUpdate("""
                UPDATE executions SET plan_fingerprint = :fingerprint
                WHERE id = :id AND plan_fingerprint <> :fingerprint
                """, batch);
        // Some JDBC drivers report Statement.SUCCESS_NO_INFO (-2) for a batched update instead of
        // a real per-row count. This feeds a start-up log line, not comparison correctness — the
        // WHERE guard above still ensures an unaffected row is genuinely a no-op — so treating an
        // unknown count as "changed" is an acceptable, deliberately conservative approximation.
        return (int) java.util.Arrays.stream(counts).filter(count -> count != 0).count();
    }

    private TestExecution map(ResultSet rs, int rowNum) throws SQLException {
        return new TestExecution(
                ExecutionId.of(rs.getString("id")),
                ProjectId.of(rs.getString("project_id")),
                read(rs.getString("plan_json"), EffectiveTestPlan.class),
                ExecutionState.valueOf(rs.getString("state")),
                Instant.parse(rs.getString("requested_at")),
                instantOrNull(rs.getString("started_at")),
                instantOrNull(rs.getString("finished_at")),
                readOrNull(rs.getString("results_json"), MeasuredResults.class),
                readOrNull(rs.getString("summary_json"), DeterministicSummary.class),
                readOrNull(rs.getString("tool_versions_json"), ToolVersions.class),
                readOrNull(rs.getString("artifacts_json"), ExecutionArtifacts.class),
                rs.getString("failure_reason") == null ? null
                        : FailureReason.valueOf(rs.getString("failure_reason")),
                rs.getString("failure_detail"),
                readOrNull(rs.getString("run_quality_json"), RunQualityAssessment.class));
    }

    private Instant instantOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : Instant.parse(raw);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store an execution document", e);
        }
    }

    private <T> T read(String content, Class<T> type) {
        try {
            return json.readValue(content, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "A stored execution document could not be read. The execution's raw artifacts "
                            + "on disk are unaffected.", e);
        }
    }

    private <T> T readOrNull(String content, Class<T> type) {
        return content == null || content.isBlank() ? null : read(content, type);
    }
}
