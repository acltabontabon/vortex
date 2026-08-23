package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.Repositories.CapacityObservationRepository;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.validity.ValidityEffect;
import dev.vortex.core.validity.ValidityFinding;
import dev.vortex.core.validity.ValidityReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A capacity observation is history, so an invalid run must not write one.
 *
 * <p>Observations are compared against later runs and quoted months afterwards. A figure recorded
 * from a run that never generated the load it asked for would sit in that record permanently, and
 * every comparison against it would inherit the mistake — which is worse than the original run's
 * page being wrong, because nobody rereads the page.
 *
 * <p>What is withheld is the <em>claim</em>. Everything the run measured is stored and rendered
 * exactly as any other run's is; these tests assert both halves.
 */
class CapacityWithheldTest {

    private static final ProjectId PROJECT = ProjectId.of("checkout");

    private final List<CapacityObservation> recorded = new ArrayList<>();
    private final CapacityService capacity = new CapacityService(repository(recorded),
            new HeadroomCalculator(), Clock.fixed(Fixtures.NOW));

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());

    /** A completed run that met every objective — the only kind that can produce a capacity. */
    private TestExecution passingRun(RunQualityAssessment quality) {
        MeasuredResults results = Fixtures.results(120, 0.0);
        var summary = analyzer.analyze(Fixtures.plan(), results);

        return new TestExecution(ExecutionId.of("e1"), PROJECT, Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600),
                results, summary, null, null, null, "", quality);
    }

    private List<StageObservation> stages(TestExecution execution) {
        return analyzer.deriveStages(execution.plan(), execution.results());
    }

    @Test
    @DisplayName("a valid run that met its objectives records its capacity")
    void aValidRunRecordsCapacity() {
        var execution = passingRun(RunQualityAssessment.valid());

        assertThat(capacity.recordFrom(execution, stages(execution))).isPresent();
        assertThat(recorded).hasSize(1);
    }

    @Test
    @DisplayName("an invalid run records none, however comfortably it passed")
    void anInvalidRunRecordsNothing() {
        var execution = passingRun(RunQualityAssessment.of(List.of(new ValidityFinding(
                ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY,
                "The load generator could not start 4812 units of work it was asked to start.",
                List.of()))));

        // The verdict is still PASS. That is the point: the objectives were met and the experiment
        // did not measure what it claims to, and only one of those is a reason to quote a number.
        assertThat(execution.verdict()).isEqualTo(dev.vortex.core.threshold.Verdict.PASS);
        assertThat(capacity.recordFrom(execution, stages(execution))).isEmpty();
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("but every measurement it took is still there")
    void nothingMeasuredIsDiscarded() {
        var execution = passingRun(RunQualityAssessment.of(List.of(new ValidityFinding(
                ValidityReason.GENERATOR_SATURATED, ValidityEffect.WITHHOLDS_CAPACITY,
                "The machine generating the traffic reached its own limit.", List.of()))));

        capacity.recordFrom(execution, stages(execution));

        // Invalidity changes what Vortex is willing to state, never what it keeps.
        assertThat(execution.results().requests()).isPositive();
        assertThat(execution.results().latency().isEmpty()).isFalse();
        assertThat(execution.summaryIfPresent()).isPresent();
    }

    @Test
    @DisplayName("a degraded run still records one, because most runs are degraded")
    void aDegradedRunStillRecordsCapacity() {
        // Refusing every degraded run would refuse almost all of them — partial telemetry is the
        // normal case — and a product that records nothing records nothing worth comparing.
        var execution = passingRun(RunQualityAssessment.of(List.of(new ValidityFinding(
                ValidityReason.TELEMETRY_INCOMPLETE, ValidityEffect.QUALIFIES,
                "Telemetry was incomplete: 2 measurements were asked for and could not be supplied.",
                List.of()))));

        assertThat(capacity.recordFrom(execution, stages(execution))).isPresent();
    }

    @Test
    @DisplayName("a run recorded before validity existed still records one")
    void anUnassessedRunStillRecordsCapacity() {
        // NOT_ASSESSED carries no reason codes and therefore withholds nothing. Treating it as
        // invalid would retroactively delete capacity history that was never in question.
        var execution = passingRun(RunQualityAssessment.notAssessed());

        assertThat(capacity.recordFrom(execution, stages(execution))).isPresent();
    }

    private static CapacityObservationRepository repository(List<CapacityObservation> sink) {
        return new CapacityObservationRepository() {

            @Override
            public CapacityObservation save(CapacityObservation observation) {
                sink.add(observation);
                return observation;
            }

            @Override
            public List<CapacityObservation> findByProject(ProjectId projectId) {
                return sink.stream()
                        .sorted(Comparator.comparing(CapacityObservation::observedAt).reversed())
                        .toList();
            }

            @Override
            public Optional<CapacityObservation> findLatest(ProjectId projectId) {
                return findByProject(projectId).stream().findFirst();
            }

            @Override
            public List<CapacityObservation> findByProjectAndVersion(ProjectId projectId,
                    String serviceVersion) {
                return findByProject(projectId).stream()
                        .filter(observation -> observation.serviceVersion().equals(serviceVersion))
                        .toList();
            }
        };
    }
}
