package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisProvenance;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.metrics.ObservationTrace;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.ExperimentIdentity;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResolvedTarget;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.Verdict;
import com.acltabontabon.vortex.persistence.config.YamlConfigurationStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises persistence against a real SQLite database.
 *
 * <p>No mocks and no in-memory substitute: SQLite is an embedded database, so the real thing is
 * both available and fast. Mocking it would only prove that the mock behaves as expected, while
 * leaving the parts that actually break — the schema, the serialisation of sealed hierarchies,
 * typed map keys — untested.
 */
class PersistenceRoundTripTest {

    private JdbcProjectRepository projects;
    private JdbcProjectRepository.Configurations configurations;
    private JdbcProjectRepository.Catalogs catalogs;
    private JdbcExecutionRepository executions;
    private JdbcAnalysisRepository analyses;
    private JdbcAnalysisRepository.Capacity capacity;
    private FilesystemArtifactStore artifacts;
    private VortexWorkspace workspace;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        workspace = new VortexWorkspace(directory).ensureExists();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(workspace.jdbcUrl());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        migrate(dataSource);

        JdbcClient jdbc = JdbcClient.create(dataSource);
        YamlConfigurationStore configurationStore = new YamlConfigurationStore();

        projects = new JdbcProjectRepository(jdbc);
        configurations = new JdbcProjectRepository.Configurations(jdbc, configurationStore);
        catalogs = new JdbcProjectRepository.Catalogs(jdbc);
        executions = new JdbcExecutionRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));
        analyses = new JdbcAnalysisRepository(jdbc);
        capacity = new JdbcAnalysisRepository.Capacity(jdbc);
        artifacts = new FilesystemArtifactStore(workspace);
    }

    private void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Project storedProject() {
        return projects.save(Fixtures.project());
    }

    @Nested
    @DisplayName("projects and configuration")
    class Projects {

        @Test
        void aProjectSurvivesARoundTrip() {
            Project saved = storedProject();

            assertThat(projects.findById(saved.id())).hasValue(saved);
            assertThat(projects.findByName("checkout-service")).hasValue(saved);
            assertThat(projects.count()).isEqualTo(1);
        }

        @Test
        void projectNamesAreMatchedWithoutRegardToCase() {
            storedProject();

            assertThat(projects.findByName("CHECKOUT-SERVICE")).isPresent();
        }

        @Test
        @DisplayName("configuration round-trips through the same YAML the file would contain")
        void configurationRoundTrips() {
            Project project = storedProject();
            configurations.save(project.id(), Fixtures.configuration());

            var loaded = configurations.findByProject(project.id()).orElseThrow();

            assertThat(loaded.serviceName()).isEqualTo("checkout-service");
            assertThat(loaded.workloads()).extracting(workload -> workload.name())
                    .containsExactlyInAnyOrder("average-load", "capacity", "batch-workers");
            assertThat(loaded.thresholds().size()).isEqualTo(3);
            // The review decision is part of the portable file, not just the local catalog.
            assertThat(loaded.bindingOrDefault(Fixtures.CREATE_ORDER).reviewed()).isTrue();
        }

        @Test
        @DisplayName("a ramping workload keeps its stages through storage")
        void rampingWorkloadsSurvive() {
            Project project = storedProject();
            configurations.save(project.id(), Fixtures.configuration());

            var capacity = configurations.findByProject(project.id()).orElseThrow()
                    .workloadByName("capacity").orElseThrow();

            assertThat(capacity.stages()).hasSize(4);
            assertThat(capacity.peakLevel().asDouble()).isEqualTo(200.0);
            assertThat(capacity.totalDuration()).isEqualTo(Duration.ofMinutes(20));
        }

        @Test
        @DisplayName("a concurrency workload does not come back as an arrival rate")
        void concurrencyWorkloadsKeepTheirUnit() {
            Project project = storedProject();
            configurations.save(project.id(), Fixtures.configuration());

            var workers = configurations.findByProject(project.id()).orElseThrow()
                    .workloadByName("batch-workers").orElseThrow();

            // A stored 50 that has forgotten whether it counted requests or virtual users is not a
            // recoverable number, so the unit travels with it.
            assertThat(workers.model()).isEqualTo(com.acltabontabon.vortex.core.workload.WorkloadModel.CLOSED);
            assertThat(workers.peakLevel().unit()).isEqualTo("VUs");
            assertThat(workers.peakLevel().asDouble()).isEqualTo(50.0);
        }

        @Test
        void aServiceCatalogRoundTrips() {
            Project project = storedProject();
            catalogs.save(project.id(), Fixtures.catalog());

            var loaded = catalogs.findByProject(project.id()).orElseThrow();

            assertThat(loaded.operationCount()).isEqualTo(4);
            assertThat(loaded.mutatingOperations()).hasSize(2);
            assertThat(loaded.title()).isEqualTo("checkout-service");
        }

        @Test
        void deletingAProjectRemovesItsConfiguration() {
            Project project = storedProject();
            configurations.save(project.id(), Fixtures.configuration());

            projects.delete(project.id());

            assertThat(projects.findById(project.id())).isEmpty();
            assertThat(configurations.findByProject(project.id())).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleting a service")
    class ProjectDeletion {

        private ProjectService projectService() {
            return new ProjectService(projects, configurations, catalogs, executions,
                    new YamlConfigurationStore(), artifacts, Clock.fixed(Fixtures.NOW));
        }

        private TestExecution completedExecution(String id) {
            var results = Fixtures.results(281, 0.0008);
            return new TestExecution(ExecutionId.of(id), ProjectId.of("checkout"), Fixtures.plan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600),
                    results, null, null, null, null, "");
        }

        @Test
        @DisplayName("removes the project, its runs, and their filesystem evidence")
        void deletingAProjectRemovesEverythingItLeftBehind() {
            Project project = storedProject();
            configurations.save(project.id(), Fixtures.configuration());
            executions.save(completedExecution("exec1"));
            artifacts.write(ExecutionId.of("exec1"), "plan.json", "{}");

            projectService().delete(project.id());

            assertThat(projects.findById(project.id())).isEmpty();
            assertThat(configurations.findByProject(project.id())).isEmpty();
            assertThat(executions.findByProject(project.id(), 10)).isEmpty();
            assertThat(artifacts.read(ExecutionId.of("exec1"), "plan.json")).isEmpty();
        }

        @Test
        @DisplayName("refuses to delete a service with a run still in progress")
        void refusesToDeleteWhileARunIsInProgress() {
            Project project = storedProject();
            var running = TestExecution.create(ExecutionId.of("stuck"), Fixtures.plan(), Fixtures.NOW)
                    .transitionTo(ExecutionState.VALIDATING, Fixtures.NOW)
                    .transitionTo(ExecutionState.READY, Fixtures.NOW)
                    .transitionTo(ExecutionState.STARTING, Fixtures.NOW)
                    .transitionTo(ExecutionState.RUNNING, Fixtures.NOW);
            executions.save(running);

            assertThatThrownBy(() -> projectService().delete(project.id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("run is in progress");

            assertThat(projects.findById(project.id())).isPresent();
        }

        @Test
        void deletingAnUnknownProjectFailsWithAClearMessage() {
            assertThatThrownBy(() -> projectService().delete(ProjectId.of("does-not-exist")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("executions")
    class Executions {

        private TestExecution completedExecution(String id) {
            var results = Fixtures.results(281, 0.0008);
            var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);

            return new TestExecution(
                    ExecutionId.of(id), ProjectId.of("checkout"), Fixtures.plan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW.plusSeconds(1),
                    Fixtures.NOW.plusSeconds(601), results,
                    new DeterministicSummary("Can it hold 20 requests/sec?", Verdict.PASS,
                            "Yes.", results, evaluation, null, null,
                            List.of("This is an isolated test.")),
                    com.acltabontabon.vortex.core.plan.ToolVersions.unknown(),
                    com.acltabontabon.vortex.core.execution.ExecutionArtifacts.empty()
                            .with("plan.json", "plan.json"),
                    null, "");
        }

        /** A plan declaring a Docker-managed target instead of an external endpoint — this kind of
         *  plan has no pre-run address at all, so {@code configuredTarget}/{@code effectiveTarget}
         *  are absent rather than manufactured. */
        private EffectiveTestPlan dockerImagePlan() {
            EffectiveTestPlan base = Fixtures.plan();
            DockerImageTarget dockerTarget = new DockerImageTarget(
                    new ImageReference("payment-service:1.4.2"), new ContainerPort(8080),
                    new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500),
                            MemoryAllocation.ofMebibytes(512)),
                    null);
            return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                    base.serviceVersion(), base.intent(), base.workloadName(),
                    base.workloadDescription(), base.testType(), base.workloadModel(),
                    base.peakLevel(), base.stages(), base.operations(), base.datasets(),
                    base.workloadSource(), base.thresholds(), base.environmentName(),
                    base.environmentType(), dockerTarget, null, null, "", base.dependencyMode(),
                    base.classification(), base.headers(), base.k6Options(), base.runner(),
                    base.scriptSource(), base.safetyDecisions(), base.fingerprint(),
                    base.validityPolicy(), base.workspacePath());
        }

        /** What {@code DockerImageTargetExecutor.prepare()} would have produced for the plan above
         *  — the runtime fact a run's target preparation discovers, never part of the plan itself. */
        private ResolvedTarget dockerResolvedTarget() {
            return new ResolvedTarget(TargetUrl.of("http://localhost:49172"),
                    TargetOwnership.VORTEX_MANAGED, "abc123containerid",
                    new EffectiveResourceEnvelope(CpuAllocation.ofMillicores(500),
                            MemoryAllocation.ofMebibytes(512)));
        }

        /** What {@code LoadGeneratorResourceBudgetResolver.resolve()} would have produced for this
         *  run — the runtime fact resolution discovers alongside the target, never part of the plan
         *  itself. */
        private com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget resolvedLoadGeneratorBudget() {
            return new com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget(
                    com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC,
                    new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(4000),
                            MemoryAllocation.ofMebibytes(4096)),
                    new com.acltabontabon.vortex.core.evidence.HostShape("Linux", "6.0", "aarch64", 12,
                            34_359_738_368L),
                    new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(1800),
                            MemoryAllocation.ofMebibytes(3277)),
                    new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(5100),
                            MemoryAllocation.ofMebibytes(14_746)),
                    true);
        }

        private TestExecution dockerExecution(String id) {
            var results = Fixtures.results(281, 0.0008);
            return new TestExecution(ExecutionId.of(id), ProjectId.of("checkout"), dockerImagePlan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW.plusSeconds(1),
                    Fixtures.NOW.plusSeconds(601), results, null,
                    com.acltabontabon.vortex.core.plan.ToolVersions.unknown(),
                    com.acltabontabon.vortex.core.execution.ExecutionArtifacts.empty(), null, "",
                    com.acltabontabon.vortex.core.validity.RunQualityAssessment.notAssessed(),
                    dockerResolvedTarget(), resolvedLoadGeneratorBudget());
        }

        @Test
        @DisplayName("a Docker-managed target's resolved runtime facts round-trip through the new "
                + "resolved_target_json column")
        void resolvedTargetRoundTrips() {
            storedProject();
            TestExecution saved = executions.save(dockerExecution("dockerExec"));

            TestExecution loaded = executions.findById(saved.id()).orElseThrow();

            assertThat(loaded.plan().executionTarget()).isInstanceOf(DockerImageTarget.class);
            // Docker/Compose targets have no pre-run address — nothing manufactures one.
            assertThat(loaded.plan().configuredTargetIfPresent()).isEmpty();
            assertThat(loaded.plan().effectiveTargetIfPresent()).isEmpty();
            assertThat(loaded.resolvedTargetIfPresent()).hasValueSatisfying(resolved -> {
                assertThat(resolved.endpoint()).isEqualTo(TargetUrl.of("http://localhost:49172"));
                assertThat(resolved.ownership()).isEqualTo(TargetOwnership.VORTEX_MANAGED);
                assertThat(resolved.telemetryHandleIfPresent()).hasValue("abc123containerid");
                assertThat(resolved.resourcesIfPresent()).hasValueSatisfying(resources -> {
                    assertThat(resources.cpuIfPresent()).hasValue(CpuAllocation.ofMillicores(500));
                    assertThat(resources.memoryIfPresent())
                            .hasValue(MemoryAllocation.ofMebibytes(512));
                });
            });
            assertThat(loaded.resolvedLoadGeneratorBudgetIfPresent()).hasValueSatisfying(resolved -> {
                assertThat(resolved.mode()).isEqualTo(
                        com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC);
                assertThat(resolved.allocation().cpuIfPresent())
                        .hasValue(CpuAllocation.ofMillicores(4000));
                assertThat(resolved.allocation().memoryIfPresent())
                        .hasValue(MemoryAllocation.ofMebibytes(4096));
                assertThat(resolved.colocatedWithManagedSut()).isTrue();
            });
        }

        @Test
        @DisplayName("a run whose target was never resolved comes back with no resolved target, and "
                + "no resolved load generator budget, not empty-but-present ones")
        void absentResolvedTargetRoundTripsToNullRatherThanAnEmptyObject() {
            storedProject();
            TestExecution saved = executions.save(completedExecution("exec1"));
            assertThat(saved.resolvedTarget()).isNull();
            assertThat(saved.resolvedLoadGeneratorBudget()).isNull();

            TestExecution loaded = executions.findById(saved.id()).orElseThrow();
            assertThat(loaded.resolvedLoadGeneratorBudgetIfPresent()).isEmpty();

            assertThat(loaded.resolvedTarget()).isNull();
            assertThat(loaded.resolvedTargetIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("a completed run round-trips with its plan, measurements and verdict intact")
        void executionRoundTrips() {
            storedProject();
            TestExecution saved = executions.save(completedExecution("exec1"));

            TestExecution loaded = executions.findById(saved.id()).orElseThrow();

            assertThat(loaded.state()).isEqualTo(ExecutionState.COMPLETED);
            assertThat(loaded.verdict()).isEqualTo(Verdict.PASS);
            assertThat(loaded.plan().fingerprint()).isEqualTo(saved.plan().fingerprint());
            assertThat(loaded.plan().workloadName()).isEqualTo("average_load");
            assertThat(loaded.duration()).hasValue(Duration.ofSeconds(600));
        }

        @Test
        @DisplayName("sealed threshold types survive storage, which plain Jackson could not do alone")
        void thresholdsKeepTheirTypes() {
            storedProject();
            executions.save(completedExecution("exec1"));

            ThresholdEvaluation evaluation = executions.findById(ExecutionId.of("exec1"))
                    .orElseThrow().summary().thresholds();

            assertThat(evaluation.results()).hasSize(3);
            assertThat(evaluation.overall()).isEqualTo(Verdict.PASS);
            assertThat(evaluation.results()).extracting(r -> r.thresholdId())
                    .containsExactlyInAnyOrder("latency.p95", "latency.p99", "errorRate");
        }

        @Test
        @DisplayName("latency percentiles keyed by a value type survive storage")
        void typedMapKeysSurvive() {
            storedProject();
            executions.save(completedExecution("exec1"));

            var latency = executions.findById(ExecutionId.of("exec1"))
                    .orElseThrow().results().latency();

            assertThat(latency.at(Percentile.P95))
                    .hasValueSatisfying(p95 -> assertThat(p95.toMillis()).isEqualTo(281));
            assertThat(latency.at(Percentile.P50)).isPresent();
        }

        @Test
        void historyIsOrderedMostRecentFirst() {
            storedProject();
            executions.save(completedExecution("exec1"));
            var later = completedExecution("exec2");
            executions.save(new TestExecution(later.id(), later.projectId(), later.plan(),
                    later.state(), Fixtures.NOW.plusSeconds(3600), later.startedAt(),
                    later.finishedAt(), later.results(), later.summary(), later.toolVersions(),
                    later.artifacts(), null, ""));

            assertThat(executions.findByProject(ProjectId.of("checkout"), 10))
                    .extracting(execution -> execution.id().value())
                    .containsExactly("exec2", "exec1");
        }

        @Test
        @DisplayName("runs left mid-flight by a previous process are discoverable")
        void unfinishedRunsAreFound() {
            storedProject();
            var running = TestExecution.create(ExecutionId.of("stuck"), Fixtures.plan(), Fixtures.NOW)
                    .transitionTo(ExecutionState.VALIDATING, Fixtures.NOW)
                    .transitionTo(ExecutionState.READY, Fixtures.NOW)
                    .transitionTo(ExecutionState.STARTING, Fixtures.NOW)
                    .transitionTo(ExecutionState.RUNNING, Fixtures.NOW);
            executions.save(running);
            executions.save(completedExecution("done"));

            assertThat(executions.findUnfinished())
                    .extracting(execution -> execution.id().value())
                    .containsExactly("stuck");
        }

        @Test
        @DisplayName("runs of the same experiment are found by identity")
        void runsOfTheSameExperimentAreFoundByIdentity() {
            storedProject();
            executions.save(completedExecution("exec1"));
            executions.save(completedExecution("exec2"));

            assertThat(executions.findCompatible(ProjectId.of("checkout"),
                    ExperimentIdentity.fingerprintOf(Fixtures.plan()).hash(), null, 10))
                    .hasSize(2);
        }

        @Test
        @DisplayName("a bound excludes the run being compared and everything after it")
        void theBoundExcludesLaterRuns() {
            storedProject();
            executions.save(completedExecution("earlier"));
            executions.save(completedExecution("later"));

            // Both runs share Fixtures.NOW as their requested time, so a bound of NOW excludes
            // both: "strictly before" is what makes "the previous run" mean the previous one.
            assertThat(executions.findCompatible(ProjectId.of("checkout"),
                    ExperimentIdentity.fingerprintOf(Fixtures.plan()).hash(), Fixtures.NOW, 10))
                    .isEmpty();
        }

        @Test
        @DisplayName("re-indexing rewrites the lookup column and leaves the stored plan alone")
        void reindexingTouchesOnlyTheIndex() {
            storedProject();
            executions.save(completedExecution("exec1"));

            assertThat(executions.reindexExperimentFingerprint(ExecutionId.of("exec1"), "abc123"))
                    .isTrue();
            // Idempotent: writing the same value again changes nothing and says so.
            assertThat(executions.reindexExperimentFingerprint(ExecutionId.of("exec1"), "abc123"))
                    .isFalse();

            assertThat(executions.findCompatible(ProjectId.of("checkout"), "abc123", null, 10))
                    .hasSize(1);
            // The plan is evidence of what ran, so its own fingerprint is untouched.
            assertThat(executions.findById(ExecutionId.of("exec1")).orElseThrow()
                    .plan().fingerprint().hash())
                    .isEqualTo(Fixtures.plan().fingerprint().hash());
        }

        @Test
        @DisplayName("the index projection carries the plan and the stored hash, so drift is visible")
        void theIndexProjectionExposesDrift() {
            storedProject();
            executions.save(completedExecution("exec1"));
            executions.reindexExperimentFingerprint(ExecutionId.of("exec1"), "an-older-contract");

            assertThat(executions.findExperimentIndexes()).singleElement().satisfies(indexed -> {
                assertThat(indexed.id().value()).isEqualTo("exec1");
                assertThat(indexed.indexedFingerprint()).isEqualTo("an-older-contract");
                // The plan comes back too, so identity can be re-derived without a second query.
                assertThat(ExperimentIdentity.fingerprintOf(indexed.plan()).hash())
                        .isEqualTo(Fixtures.plan().fingerprint().hash())
                        .isNotEqualTo(indexed.indexedFingerprint());
            });
        }
    }

    @Nested
    @DisplayName("analyses accumulate rather than replace")
    class Analyses {

        private ExecutionId storedExecution() {
            storedProject();
            var results = Fixtures.results(281, 0.0008);
            executions.save(new TestExecution(
                    ExecutionId.of("exec1"), ProjectId.of("checkout"), Fixtures.plan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600),
                    results, null, null, null, null, ""));
            return ExecutionId.of("exec1");
        }

        private Analysis analysis(String id, String model, Instant at) {
            return new Analysis(AnalysisId.of(id), ExecutionId.of("exec1"), AnalysisState.COMPLETED,
                    "Connection-pool saturation is the strongest hypothesis.",
                    List.of(new Finding("Latency rose with pool utilisation.", Confidence.MEDIUM,
                            List.of("metric:http.latency.p95"))),
                    List.of(), List.of(), null,
                    new AnalysisProvenance("ollama", model, "v1", at, 4200), "");
        }

        @Test
        @DisplayName("re-analysing adds a record and keeps the earlier interpretation")
        void reAnalysisIsAdditive() {
            ExecutionId execution = storedExecution();

            analyses.save(analysis("a1", "small-model", Fixtures.NOW));
            analyses.save(analysis("a2", "better-model", Fixtures.NOW.plusSeconds(86400)));

            assertThat(analyses.findByExecution(execution)).hasSize(2);
            assertThat(analyses.findLatest(execution))
                    .hasValueSatisfying(latest -> assertThat(latest.provenance().model())
                            .isEqualTo("better-model"));
        }

        @Test
        void whichModelAndPromptProducedAnOpinionIsRecorded() {
            storedExecution();
            analyses.save(analysis("a1", "small-model", Fixtures.NOW));

            var loaded = analyses.findById(AnalysisId.of("a1")).orElseThrow();

            assertThat(loaded.provenance().describe())
                    .contains("ollama").contains("small-model").contains("v1");
            assertThat(loaded.findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.evidenceIds())
                            .containsExactly("metric:http.latency.p95"));
        }

        @Test
        void aFailedAnalysisIsNotOfferedAsTheLatest() {
            ExecutionId execution = storedExecution();
            analyses.save(Analysis.failed(AnalysisId.of("a1"), execution, "Ollama was unreachable."));

            assertThat(analyses.findByExecution(execution)).hasSize(1);
            assertThat(analyses.findLatest(execution)).isEmpty();
        }
    }

    @Nested
    @DisplayName("capacity observations")
    class Capacity {

        @Test
        void capacityIsStoredWithItsConditions() {
            storedProject();
            var results = Fixtures.results(281, 0.0008);
            executions.save(new TestExecution(ExecutionId.of("exec1"), ProjectId.of("checkout"),
                    Fixtures.plan(), ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(600), results, null, null, null, null, ""));

            capacity.save(new CapacityObservation(ProjectId.of("checkout"), ExecutionId.of("exec1"),
                    "2.17.0", RequestsPerSecond.of(118),
                    com.acltabontabon.vortex.core.workload.WorkloadModel.OPEN, "local", TestClassification.ISOLATED,
                    DependencyMode.MOCKED,
                    List.of("70% GET /accounts/{id}", "30% GET /orders/{id}"), "capacity",
                    List.of("p95 latency below 500 ms"), Duration.ofMinutes(5),
                    Fixtures.plan().fingerprint(), Fixtures.NOW));

            var stored = capacity.findLatest(ProjectId.of("checkout")).orElseThrow();

            assertThat(stored.compliantLevel().asDouble()).isEqualTo(118.0);
            assertThat(stored.label()).isEqualTo("Tested SLO-compliant capacity");
            assertThat(stored.conditions())
                    .anyMatch(condition -> condition.contains("Isolated performance test"))
                    .anyMatch(condition -> condition.contains("70% GET /accounts/{id}"))
                    .anyMatch(condition -> condition.contains("LoadShape model: Arrival rate"));
            assertThat(stored.supportsHeadroom())
                    .as("isolated capacity must not be compared with production traffic")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("artifact store")
    class Artifacts {

        @Test
        void artifactsRoundTripThroughTheFilesystem() {
            var id = ExecutionId.of("exec1");

            artifacts.write(id, "plan.json", "{\"schema\":\"vortex.plan/1\"}");

            assertThat(artifacts.read(id, "plan.json")).hasValue("{\"schema\":\"vortex.plan/1\"}");
            assertThat(artifacts.list(id)).containsExactly("plan.json");
            assertThat(artifacts.sizeOf(id, "plan.json")).hasValueSatisfying(
                    size -> assertThat(size).isPositive());
        }

        @Test
        @DisplayName("a name that would escape the execution directory is rejected")
        void pathTraversalIsRejected() {
            var id = ExecutionId.of("exec1");

            assertThatThrownBy(() -> artifacts.write(id, "../../escaped.txt", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolves outside its execution directory");

            assertThatThrownBy(() -> artifacts.read(id, "../../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void readingAnAbsentArtifactIsEmptyRatherThanAnError() {
            assertThat(artifacts.read(ExecutionId.of("nothing"), "plan.json")).isEmpty();
            assertThat(artifacts.list(ExecutionId.of("nothing"))).isEmpty();
        }
    }

    /**
     * Widening {@code MetricObservation} must not orphan a single stored run.
     *
     * <p>Observation provenance and the start/peak/end trace were added after 0.1.0 shipped. Every
     * {@code results_json} written before that has neither key, and every observation written since
     * that carries neither must still serialise the way it always did — otherwise adding a field to
     * a measurement quietly becomes a migration, and the next person to add one will not risk it.
     */
    @Nested
    @DisplayName("observation provenance was added without a migration")
    class ObservationCompatibility {

        private final com.fasterxml.jackson.databind.ObjectMapper json = JsonDocuments.mapper();

        @Test
        @DisplayName("a document written before provenance existed still reads, with both fields absent")
        void legacyDocumentStillReads() throws Exception {
            // Written by hand rather than by an older build, because the point is the exact bytes
            // that are sitting in people's databases right now.
            String legacy = """
                    {
                      "id": "metric:hikaricp.connections.utilization",
                      "name": "hikaricp.connections.utilization",
                      "source": "ACTUATOR",
                      "unit": "PERCENT",
                      "aggregation": "MAX",
                      "value": 94.0,
                      "window": {"start": "2026-08-21T10:00:00Z", "end": "2026-08-21T10:10:00Z"},
                      "dimensions": {}
                    }""";

            MetricObservation restored = json.readValue(legacy, MetricObservation.class);

            assertThat(restored.value()).isEqualTo(94.0);
            assertThat(restored.source()).isEqualTo(MetricSource.ACTUATOR);
            assertThat(restored.provenanceIfPresent()).isEmpty();
            assertThat(restored.traceIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("an observation with nothing to add serialises exactly as it did before")
        void absentFieldsAddNoKeys() throws Exception {
            MetricObservation bare = MetricObservation.of("metric:cpu", "system.cpu.usage",
                    MetricSource.ACTUATOR, MetricUnit.PERCENT, Aggregation.MAX, 81, WINDOW);

            String written = json.writeValueAsString(bare);

            assertThat(written).doesNotContain("provenance").doesNotContain("trace");
        }

        @Test
        void provenanceAndTraceSurviveARoundTrip() throws Exception {
            MetricObservation observed = MetricObservation
                    .of("metric:pool", "db.pool.utilization", MetricSource.PROMETHEUS,
                            MetricUnit.PERCENT, Aggregation.MAX, 94, WINDOW)
                    .withProvenance(new ObservationProvenance("prometheus",
                            "max(hikaricp_connections_active) / max(hikaricp_connections_max)",
                            "job=checkout", "http://prometheus.internal/graph?g0.expr=..."))
                    .withTrace(new ObservationTrace(31, 94, 47,
                            Instant.parse("2026-08-21T10:06:00Z")));

            MetricObservation restored = json.readValue(
                    json.writeValueAsString(observed), MetricObservation.class);

            assertThat(restored).isEqualTo(observed);
            assertThat(restored.provenanceIfPresent())
                    .hasValueSatisfying(provenance -> {
                        assertThat(provenance.providerId()).isEqualTo("prometheus");
                        assertThat(provenance.entityIdIfPresent()).hasValue("job=checkout");
                        assertThat(provenance.sourceUrlIfPresent()).isPresent();
                    });
            assertThat(restored.traceIfPresent())
                    .hasValueSatisfying(trace -> {
                        assertThat(trace.startValue()).isEqualTo(31);
                        assertThat(trace.roseDuringRun()).isTrue();
                        assertThat(trace.recovered()).isTrue();
                    });
        }

        @Test
        @DisplayName("a whole run carrying observations round-trips through the executions table")
        void observationsSurviveTheDatabase() {
            MetricObservation observed = MetricObservation
                    .of("metric:pool", "db.pool.utilization", MetricSource.DYNATRACE,
                            MetricUnit.PERCENT, Aggregation.MAX, 94, WINDOW)
                    .withProvenance(ObservationProvenance.of("dynatrace",
                            "builtin:service.dbpool.utilization"))
                    .withTrace(new ObservationTrace(31, 94, 47, null));

            MeasuredResults results = Fixtures.results(420, 0.004);
            MeasuredResults withObservation = new MeasuredResults(
                    results.window(), results.targetLoad(), results.achievedRate(),
                    results.requests(), results.failures(), results.latency(),
                    results.perOperation(), results.series(), List.of(observed));

            TestExecution execution = TestExecution
                    .create(ExecutionId.of("obs1"), Fixtures.plan(), Instant.parse("2026-08-21T10:00:00Z"))
                    .withResults(withObservation);
            projects.save(Fixtures.project());
            executions.save(execution);

            TestExecution restored = executions.findById(ExecutionId.of("obs1")).orElseThrow();

            assertThat(restored.results().observations()).singleElement().satisfies(o -> {
                assertThat(o.source()).isEqualTo(MetricSource.DYNATRACE);
                assertThat(o.provenanceIfPresent()).isPresent();
                assertThat(o.traceIfPresent()).hasValueSatisfying(
                        trace -> assertThat(trace.peakValue()).isEqualTo(94));
            });
        }
    }

    private static final com.acltabontabon.vortex.core.metrics.TimeWindow WINDOW =
            new com.acltabontabon.vortex.core.metrics.TimeWindow(
                    Instant.parse("2026-08-21T10:00:00Z"), Instant.parse("2026-08-21T10:10:00Z"));

    @Test
    @DisplayName("the workspace enables write-ahead logging and foreign keys explicitly")
    void workspaceConfiguresSqliteDeliberately() {
        assertThat(workspace.jdbcUrl())
                .contains("journal_mode=WAL")
                .contains("foreign_keys=on")
                .contains("busy_timeout=");
    }

    @Test
    void migrationsRunFromAnEmptyDatabase() {
        assertThat(projects.count()).isZero();
        assertThat(executions.findRecent(10)).isEmpty();
    }
}
