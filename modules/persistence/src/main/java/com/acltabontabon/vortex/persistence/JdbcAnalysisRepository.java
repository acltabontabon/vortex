package com.acltabontabon.vortex.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.port.Repositories.AnalysisRepository;
import com.acltabontabon.vortex.core.port.Repositories.CapacityObservationRepository;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.Ids;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AI interpretations of executions.
 *
 * <p>Additive by design. Re-analysing a run with a newer model or a revised prompt inserts another
 * row rather than replacing the previous interpretation, and every row records which provider,
 * model and prompt version produced it. Measurements are immutable; opinions about them are
 * versioned, and knowing that an opinion came from a small local model six months ago is part of
 * knowing what it is worth.
 */
public final class JdbcAnalysisRepository implements AnalysisRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json = JsonDocuments.mapper();

    public JdbcAnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Analysis save(Analysis analysis) {
        jdbc.sql("""
                INSERT INTO analyses (id, execution_id, state, conclusion, content_json, provider,
                                      model, prompt_version, generated_at, duration_ms, failure_message)
                VALUES (:id, :executionId, :state, :conclusion, :contentJson, :provider, :model,
                        :promptVersion, :generatedAt, :durationMs, :failureMessage)
                ON CONFLICT (id) DO UPDATE SET
                    state = excluded.state,
                    conclusion = excluded.conclusion,
                    content_json = excluded.content_json,
                    provider = excluded.provider,
                    model = excluded.model,
                    prompt_version = excluded.prompt_version,
                    generated_at = excluded.generated_at,
                    duration_ms = excluded.duration_ms,
                    failure_message = excluded.failure_message
                """)
                .param("id", analysis.id().value())
                .param("executionId", analysis.executionId().value())
                .param("state", analysis.state().name())
                .param("conclusion", analysis.conclusion())
                .param("contentJson", write(analysis))
                .param("provider", analysis.provenanceIfPresent().map(p -> p.provider()).orElse(""))
                .param("model", analysis.provenanceIfPresent().map(p -> p.model()).orElse(""))
                .param("promptVersion",
                        analysis.provenanceIfPresent().map(p -> p.promptVersion()).orElse(""))
                .param("generatedAt", analysis.provenanceIfPresent()
                        .map(p -> p.generatedAt().toString()).orElse(Instant.now().toString()))
                .param("durationMs", analysis.provenanceIfPresent().map(p -> p.durationMs()).orElse(0L))
                .param("failureMessage", analysis.failureMessage())
                .update();
        return analysis;
    }

    @Override
    public Optional<Analysis> findById(AnalysisId id) {
        return jdbc.sql("SELECT content_json FROM analyses WHERE id = :id")
                .param("id", id.value())
                .query(String.class)
                .optional()
                .map(this::read);
    }

    @Override
    public List<Analysis> findByExecution(ExecutionId executionId) {
        return jdbc.sql("""
                SELECT content_json FROM analyses
                WHERE execution_id = :executionId
                ORDER BY generated_at DESC
                """)
                .param("executionId", executionId.value())
                .query(String.class)
                .list()
                .stream()
                .map(this::read)
                .toList();
    }

    @Override
    public Optional<Analysis> findLatest(ExecutionId executionId) {
        return jdbc.sql("""
                SELECT content_json FROM analyses
                WHERE execution_id = :executionId AND state = 'COMPLETED'
                ORDER BY generated_at DESC
                LIMIT 1
                """)
                .param("executionId", executionId.value())
                .query(String.class)
                .optional()
                .map(this::read);
    }

    private String write(Analysis analysis) {
        try {
            return json.writeValueAsString(analysis);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store an analysis", e);
        }
    }

    private Analysis read(String content) {
        try {
            return json.readValue(content, Analysis.class);
        } catch (Exception e) {
            throw new IllegalStateException("A stored analysis could not be read", e);
        }
    }

    /**
     * Capacity evidence.
     *
     * <p>Rows accumulate rather than replacing one another, because tested capacity is not a
     * property a service has — it moves with the version, the configuration, the infrastructure and
     * the size of the data. Every row carries the conditions it was measured under.
     */
    public static final class Capacity implements CapacityObservationRepository {

        private final JdbcClient jdbc;
        private final ObjectMapper json = JsonDocuments.mapper();

        public Capacity(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public CapacityObservation save(CapacityObservation observation) {
            jdbc.sql("""
                    INSERT INTO capacity_observations (id, project_id, execution_id, service_version,
                                                       compliant_rate, failing_rate,
                                                       boundary_status, boundary_strength,
                                                       environment_name,
                                                       classification, dependency_mode, content_json,
                                                       observed_at, sustainable_rate,
                                                       sustainable_json)
                    VALUES (:id, :projectId, :executionId, :serviceVersion, :compliantRate,
                            :failingRate, :boundaryStatus, :boundaryStrength,
                            :environmentName, :classification, :dependencyMode, :contentJson,
                            :observedAt, :sustainableRate, :sustainableJson)
                    """)
                    .param("id", Ids.generate())
                    .param("projectId", observation.projectId().value())
                    .param("executionId", observation.executionId().value())
                    .param("serviceVersion", observation.serviceVersion())
                    .param("compliantRate", observation.compliantLevel().displayWithUnit())
                    // Null rather than an empty string: a run that never violated an objective has
                    // no failing edge, which is a different fact from one recorded as blank.
                    .param("failingRate", observation.firstNonCompliantIfPresent()
                            .map(edge -> edge.display()).orElse(null))
                    .param("boundaryStatus", observation.boundaryStatus().name())
                    .param("boundaryStrength", observation.boundaryStrength().name())
                    .param("environmentName", observation.environmentName())
                    .param("classification", observation.classification().name())
                    .param("dependencyMode", observation.dependencyMode().name())
                    .param("contentJson", write(observation))
                    .param("observedAt", observation.observedAt().toString())
                    // Null when no sustainable capacity was established, which is a real and common
                    // outcome. The conditions behind it ride in the JSON beside it, so a reader can
                    // tell "evaluated and not met" from "never evaluated".
                    .param("sustainableRate", observation.sustainable().levelIfPresent()
                            .map(level -> level.displayWithUnit()).orElse(null))
                    .param("sustainableJson", writeSustainable(observation))
                    .update();
            return observation;
        }

        @Override
        public List<CapacityObservation> findByProject(ProjectId projectId) {
            return jdbc.sql("""
                    SELECT content_json FROM capacity_observations
                    WHERE project_id = :projectId
                    ORDER BY observed_at DESC
                    """)
                    .param("projectId", projectId.value())
                    .query(String.class)
                    .list()
                    .stream()
                    .map(this::read)
                    .toList();
        }

        @Override
        public Optional<CapacityObservation> findLatest(ProjectId projectId) {
            return jdbc.sql("""
                    SELECT content_json FROM capacity_observations
                    WHERE project_id = :projectId
                    ORDER BY observed_at DESC
                    LIMIT 1
                    """)
                    .param("projectId", projectId.value())
                    .query(String.class)
                    .optional()
                    .map(this::read);
        }

        @Override
        public List<CapacityObservation> findByProjectAndVersion(ProjectId projectId,
                String serviceVersion) {
            return jdbc.sql("""
                    SELECT content_json FROM capacity_observations
                    WHERE project_id = :projectId AND service_version = :serviceVersion
                    ORDER BY observed_at DESC
                    """)
                    .param("projectId", projectId.value())
                    .param("serviceVersion", serviceVersion == null ? "" : serviceVersion)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(this::read)
                    .toList();
        }

        private String write(CapacityObservation observation) {
            try {
                return json.writeValueAsString(observation);
            } catch (Exception e) {
                throw new IllegalStateException("Could not store a capacity observation", e);
            }
        }

        /**
         * The five conditions, promoted alongside the level so a reader can see why there isn't one.
         *
         * <p>Duplicated from {@code content_json}, which already holds the whole observation. The
         * copy exists so a query can distinguish an observation whose conditions were evaluated and
         * not met from one recorded before they existed — without deserialising every row to find
         * out. Null for the second case, which is what makes historical headroom keep working.
         */
        private String writeSustainable(CapacityObservation observation) {
            if (observation.sustainable().conditions().isEmpty()) {
                return null;
            }
            try {
                return json.writeValueAsString(observation.sustainable());
            } catch (Exception e) {
                throw new IllegalStateException("Could not store a sustainable capacity", e);
            }
        }

        private CapacityObservation read(String content) {
            try {
                return json.readValue(content, CapacityObservation.class);
            } catch (Exception e) {
                throw new IllegalStateException("A stored capacity observation could not be read", e);
            }
        }
    }
}
