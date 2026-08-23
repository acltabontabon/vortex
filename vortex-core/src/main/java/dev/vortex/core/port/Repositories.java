package dev.vortex.core.port;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.project.Project;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.AnalysisId;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The persistence ports, grouped because they are read together and small individually.
 *
 * <p>These are use-case shaped rather than table shaped. There is no generic {@code Repository<T>}
 * and no CRUD surface mirroring the schema: each method exists because some application service
 * needs exactly it. A repository that can express any query tends to leak query construction into
 * the domain, and then the database schema starts dictating the model.
 */
public final class Repositories {

    private Repositories() {
    }

    public interface ProjectRepository {

        Project save(Project project);

        Optional<Project> findById(ProjectId id);

        Optional<Project> findByName(String name);

        List<Project> findAll();

        void delete(ProjectId id);

        long count();
    }

    /**
     * Stores the configuration mirrored from {@code vortex.yaml}.
     *
     * <p>The file remains the portable source of truth; this is a local index of it, so the UI can
     * work without re-parsing on every request.
     */
    public interface ProjectConfigurationRepository {

        void save(ProjectId projectId, ProjectConfiguration configuration);

        Optional<ProjectConfiguration> findByProject(ProjectId projectId);
    }

    public interface ServiceCatalogRepository {

        void save(ProjectId projectId, ServiceCatalog catalog);

        Optional<ServiceCatalog> findByProject(ProjectId projectId);
    }

    public interface ExecutionRepository {

        TestExecution save(TestExecution execution);

        /**
         * Persists many executions in one round trip.
         *
         * <p>Used at start-up, when a previous process's orphaned runs are all marked failed at
         * once rather than one statement per row.
         */
        List<TestExecution> saveAll(List<TestExecution> executions);

        Optional<TestExecution> findById(ExecutionId id);

        List<TestExecution> findByProject(ProjectId projectId, int limit);

        List<TestExecution> findRecent(int limit);

        /**
         * Executions left non-terminal by a previous process.
         *
         * <p>Vortex does not adopt orphaned engine processes on restart. These are marked failed
         * with {@code INTERRUPTED} so history stays truthful rather than showing runs that appear
         * to still be going.
         */
        List<TestExecution> findUnfinished();

        /**
         * Completed runs of the same experiment, most recent first.
         *
         * <p>This is what "compare with the previous compatible run" resolves to, and it is a query
         * rather than a scan on purpose: the fingerprint column is indexed, and an application-side
         * filter over every execution a project has ever produced would get slower exactly as a team
         * accumulated the history that makes comparison worth having.
         *
         * <p>The project is a parameter rather than part of the fingerprint because two services
         * can legitimately share an experiment definition, and comparing across them is not what
         * anybody means by "the previous run".
         *
         * @param experimentFingerprint the identity to match, from {@code ExperimentIdentity}
         * @param before                only runs requested strictly before this instant; use
         *                              {@code null} for no bound
         */
        List<TestExecution> findCompatible(ProjectId projectId, String experimentFingerprint,
                Instant before, int limit);

        /**
         * Just enough of every execution to re-derive its experiment identity and see whether the
         * stored index still matches.
         *
         * <p>A projection rather than whole executions: this is read at every start-up, and there
         * is no reason to deserialise measurements and summaries in order to check a hash.
         *
         * <p>Unbounded, unlike every other query here. Silently skipping rows would leave part of a
         * team's comparison history unreachable with nothing to indicate it had happened, and "no
         * previous compatible run exists" is also what Vortex says about a first run.
         */
        List<ExperimentIndex> findExperimentIndexes();

        /**
         * One execution's stored identity index, beside the plan it should have been derived from.
         *
         * @param indexedFingerprint what the lookup column currently holds; may be blank
         */
        record ExperimentIndex(ExecutionId id, EffectiveTestPlan plan, String indexedFingerprint) {
        }

        /**
         * Re-indexes one execution's experiment fingerprint, if it has changed.
         *
         * <p>Updates the lookup column only. The stored plan is left exactly as it was written: it
         * is the historical record of what ran, and the fingerprint embedded in it is the value
         * that was true at the time.
         *
         * @return {@code true} when the stored value actually changed, so a caller can report how
         *         much was re-indexed without re-reading every row
         */
        boolean reindexExperimentFingerprint(ExecutionId id, String experimentFingerprint);

        /**
         * Rewrites many indexed fingerprints in one round trip.
         *
         * <p>Used at start-up to reconcile every execution's identity index against the current
         * fingerprint contract without one {@code UPDATE} per row.
         *
         * @param newFingerprints execution id to its recomputed fingerprint; only rows that
         *                        actually need to change should be passed in
         * @return how many rows were rewritten
         */
        int reindexExperimentFingerprints(Map<ExecutionId, String> newFingerprints);

        long countByProject(ProjectId projectId);
    }

    /**
     * Stores analyses. Additive by design: re-analysing an execution adds a record rather than
     * replacing the previous interpretation.
     */
    public interface AnalysisRepository {

        Analysis save(Analysis analysis);

        Optional<Analysis> findById(AnalysisId id);

        /** All analyses for an execution, newest first. */
        List<Analysis> findByExecution(ExecutionId executionId);

        /** The most recent usable analysis, when one exists. */
        Optional<Analysis> findLatest(ExecutionId executionId);
    }

    public interface CapacityObservationRepository {

        CapacityObservation save(CapacityObservation observation);

        List<CapacityObservation> findByProject(ProjectId projectId);

        Optional<CapacityObservation> findLatest(ProjectId projectId);

        /**
         * Every observation recorded against one release.
         *
         * <p>Per version because tested capacity moves with the release, the configuration and the
         * size of the data. A history that interleaves two releases invites a comparison between
         * numbers that were never comparable.
         */
        List<CapacityObservation> findByProjectAndVersion(ProjectId projectId, String serviceVersion);
    }
}
