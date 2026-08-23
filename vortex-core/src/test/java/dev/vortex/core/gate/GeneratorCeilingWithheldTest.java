package dev.vortex.core.gate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.capacity.SustainableCapacityCalculator;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.LoadGeneration;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.ReliabilityBreakdown;
import dev.vortex.core.metrics.ResponseClass;
import dev.vortex.core.metrics.SamplePoint;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.Repositories.CapacityObservationRepository;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.threshold.Verdict;
import dev.vortex.core.validity.RunQuality;
import dev.vortex.core.validity.RunQualityAssessor;
import dev.vortex.core.validity.ValidityReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The acceptance test Phase 4 exists for.
 *
 * <p>A workload asks for 1,000 requests/sec. The machine running the generator produces 720. Every
 * downstream calculation is arithmetically correct and every one of them is about the wrong system:
 *
 * <pre>
 *   Achieved throughput   720 requests/sec
 *   p95 latency           180 ms
 *   Error rate            0.04%
 *   Verdict               PASS
 *   Tested capacity       720 requests/sec
 * </pre>
 *
 * <p>Nothing there is false. Read together they say the service demonstrated 720 requests/sec within
 * its objectives, when what was demonstrated is that <em>Vortex's own hardware</em> could produce
 * 720 and the service was comfortable with whatever it was given.
 *
 * <p>Deterministic by construction: it builds the evidence rather than racing a real machine, so it
 * cannot pass on a slow laptop and silently stop exercising the path on a fast build agent. The
 * companion test in {@code vortex-k6} drives real k6 to prove that evidence of this shape actually
 * arrives.
 */
class GeneratorCeilingWithheldTest {

    private static final ProjectId PROJECT = ProjectId.of("checkout");

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());
    private final RunQualityAssessor validity = new RunQualityAssessor();
    private final SustainableCapacityCalculator sustainable = new SustainableCapacityCalculator();

    private final List<CapacityObservation> recorded = new ArrayList<>();
    private final CapacityService capacity = new CapacityService(repository(recorded),
            new HeadroomCalculator(), Clock.fixed(Fixtures.NOW));

    /**
     * A run that asked for more than its generator could produce, and whose service never noticed.
     *
     * <p>Comfortable latency, no errors, every objective met - and 4,812 units of work the generator
     * could not start. The whole point is that only the last of those distinguishes it from a
     * genuinely healthy run.
     */
    private TestExecution generatorBound() {
        MeasuredResults comfortable = Fixtures.results(180, 0.0);
        MeasuredResults results = new MeasuredResults(
                comfortable.window(),
                OFFERED,
                RequestsPerSecond.of(720),
                comfortable.requests(), 0, comfortable.latency(), Map.of(),
                new MetricSeries(Duration.ofSeconds(5), List.of(new SamplePoint(
                        Fixtures.NOW, Duration.ofSeconds(5), RequestsPerSecond.of(720),
                        ErrorRate.ZERO, Duration.ofMillis(180), OFFERED,
                        50, 4_812L))),
                List.of(), List.of(), List.of(),
                new LoadGeneration(30_852L, 4_812L, 51.4),
                comfortable.phases(),
                new ReliabilityBreakdown(Map.of(ResponseClass.SUCCESS, comfortable.requests()),
                        Map.of(), Map.of("200", comfortable.requests()), comfortable.requests()));

        var plan = askingFor(OFFERED);
        var summary = analyzer.analyze(plan, results);
        var quality = validity.assess(plan, results, analyzer.deriveStages(plan, results),
                ExecutionState.COMPLETED, null);

        return new TestExecution(ExecutionId.of("ceiling"), PROJECT, plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "", quality);
    }

    /** What the workload asked for, and what its own generator could not produce. */
    private static final RequestsPerSecond OFFERED = RequestsPerSecond.of(1_000);

    /**
     * A plan that ramps to the offered level and then holds it well past its sustain duration.
     *
     * <p>Held deliberately long. If the level were only passed through, this run would be refused a
     * capacity for being too brief - and the test would pass while proving nothing about the
     * generator. The only condition that may fail here is the first one.
     */
    private static dev.vortex.core.plan.EffectiveTestPlan askingFor(RequestsPerSecond level) {
        return Fixtures.plan(dev.vortex.core.workload.TestType.AVERAGE_LOAD,
                new dev.vortex.core.workload.RampingArrivalRateShape(RequestsPerSecond.of(10),
                        List.of(new dev.vortex.core.workload.Stage(level, Duration.ofMinutes(1)),
                                new dev.vortex.core.workload.Stage(level, Duration.ofMinutes(10)))));
    }

    @Test
    @DisplayName("the run passes every objective, which is exactly what makes it dangerous")
    void theVerdictIsStillPass() {
        var execution = generatorBound();

        assertThat(execution.verdict()).isEqualTo(Verdict.PASS);
        assertThat(execution.results().errorRate().asFraction()).isZero();
    }

    @Test
    @DisplayName("and is nonetheless invalid, naming the generator")
    void theRunIsInvalid() {
        var quality = generatorBound().quality();

        assertThat(quality.quality()).isEqualTo(RunQuality.INVALID);
        assertThat(quality.has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isTrue();
    }

    @Test
    @DisplayName("the finding names the number an engineer would need to argue with it")
    void theFindingIsArguable() {
        var finding = generatorBound().quality()
                .finding(ValidityReason.OFFERED_LOAD_NOT_GENERATED).orElseThrow();

        assertThat(finding.statement()).contains("4812");
        assertThat(finding.evidenceIds()).isNotEmpty();
    }

    @Test
    @DisplayName("no capacity observation is recorded, so the figure never enters the history")
    void noCapacityIsRecorded() {
        var execution = generatorBound();

        assertThat(capacity.recordFrom(execution, stagesOf(execution))).isEmpty();
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("and no sustainable capacity is claimed, with the failing condition named")
    void noSustainableCapacityIsClaimed() {
        var execution = generatorBound();
        var result = sustainable.calculate(execution.plan(), stagesOf(execution),
                execution.quality(), execution.summary().limits());

        assertThat(result.isEstablished()).isFalse();
        assertThat(result.headline()).contains("4812");
    }

    @Test
    @DisplayName("headroom is refused rather than divided out of a number nobody demonstrated")
    void headroomIsRefused() {
        var execution = generatorBound();
        var result = sustainable.calculate(execution.plan(), stagesOf(execution),
                execution.quality(), execution.summary().limits());

        var headroom = new HeadroomCalculator().calculate(result, true,
                dev.vortex.core.capacity.BoundaryStatus.ESTABLISHED, null);

        assertThat(headroom.isAvailable()).isFalse();
        assertThat(headroom.reason()).isPresent();
    }

    @Test
    @DisplayName("every measurement the run took is kept and reported")
    void nothingMeasuredIsDiscarded() {
        var execution = generatorBound();

        // Invalidity changes what Vortex is willing to state, never what it stores. A page that
        // hid its measurements would make an invalid run indistinguishable from a failed one.
        assertThat(execution.results().requests()).isPositive();
        assertThat(execution.results().latency().isEmpty()).isFalse();
        assertThat(execution.results().series().points()).isNotEmpty();
        assertThat(execution.results().achievedRateIfPresent()).isPresent();
    }

    @Test
    @DisplayName("a run whose generator kept up is unaffected, so the guard is not a blanket refusal")
    void aHealthyRunStillReportsItsCapacity() {
        MeasuredResults comfortable = Fixtures.results(180, 0.0);
        MeasuredResults results = new MeasuredResults(
                comfortable.window(), OFFERED, OFFERED,
                comfortable.requests(), 0, comfortable.latency(), Map.of(), MetricSeries.empty(),
                List.of(), List.of(), List.of(),
                new LoadGeneration(30_852L, 0L, 51.4), comfortable.phases(),
                new ReliabilityBreakdown(Map.of(ResponseClass.SUCCESS, comfortable.requests()),
                        Map.of(), Map.of(), comfortable.requests()));

        var plan = askingFor(OFFERED);
        var summary = analyzer.analyze(plan, results);
        var quality = validity.assess(plan, results, List.of(), ExecutionState.COMPLETED, null);
        var execution = new TestExecution(ExecutionId.of("healthy"), PROJECT, plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "", quality);

        assertThat(quality.quality()).isEqualTo(RunQuality.VALID);
        assertThat(capacity.recordFrom(execution, stagesOf(execution))).isPresent();
    }

    private List<StageObservation> stagesOf(TestExecution execution) {
        return analyzer.deriveStages(execution.plan(), execution.results());
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
