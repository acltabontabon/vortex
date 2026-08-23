package com.acltabontabon.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.analysis.EvidenceIds;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.analysis.SystemSaturation;
import com.acltabontabon.vortex.core.catalog.PayloadProvenance;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.ObservationTrace;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceLimit;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.core.plan.ScriptSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Deterministic findings are the sentences a report puts in front of a reader, so their wording is
 * as much a part of the contract as their arithmetic.
 *
 * <p>Two things are asserted here that are easy to lose. First, that a rule fires when it should and
 * cites something real. Second — and this is the one that would quietly rot — that no finding ever
 * claims causation. A load test observes two signals moving together; the step from association to
 * cause takes context a run does not contain, and a sentence that skips it will eventually be quoted
 * in a review as though it had not.
 */
class FindingDetectorTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plusSeconds(600));

    /**
     * Wording that must never appear in a deterministic finding.
     *
     * <p>Taken from the say/not-say table in {@code docs/02-architecture/execution-and-evidence.adoc}
     * (Evidence model). The
     * table is described there as enforced in prompts, templates and code; this is the code half.
     */
    private static final List<String> FORBIDDEN = List.of(
            "caused by", "the bottleneck is", "we found that", "maximum throughput",
            "proves", "because of this", "due to the");

    private final FindingDetector detector = new FindingDetector();

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("a violated objective is a failure that names what was observed")
        void violatedObjective() {
            var findings = detect(results(900, 0.0), thresholds(results(900, 0.0)));

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.level()).isEqualTo(FindingLevel.FAIL);
                assertThat(finding.id()).startsWith("finding:threshold.");
                assertThat(finding.evidenceIds()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("an objective that was met is reported, so a pass is evidenced rather than assumed")
        void metObjective() {
            var findings = detect(results(120, 0.0), thresholds(results(120, 0.0)));

            assertThat(findings).anySatisfy(
                    finding -> assertThat(finding.level()).isEqualTo(FindingLevel.PASS));
        }

        @Test
        @DisplayName("an unevaluated objective is a warning and is never worded as a pass")
        void unevaluatedIsNeverAPass() {
            var threshold = Fixtures.thresholds().thresholds().get(0);
            var evaluation = new ThresholdEvaluation(List.of(
                    ThresholdResult.notEvaluated(threshold, "No latency was recorded.")));

            var findings = detect(results(120, 0.0), evaluation);

            var unevaluated = findings.stream()
                    .filter(finding -> finding.id().startsWith("finding:threshold."))
                    .findFirst().orElseThrow();

            assertThat(unevaluated.level()).isEqualTo(FindingLevel.WARNING);
            assertThat(unevaluated.strength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
            assertThat(unevaluated.headline().toLowerCase(Locale.ROOT))
                    .contains("not evaluated")
                    .doesNotContain("met");
        }

        @Test
        @DisplayName("a run with no objectives says so, rather than showing an empty table")
        void noObjectivesAtAll() {
            var findings = detect(results(120, 0.0), ThresholdEvaluation.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:objectives.absent");
                assertThat(finding.level()).isEqualTo(FindingLevel.WARNING);
                assertThat(finding.headline()).contains("neither passed nor failed");
            });
        }
    }

    @Nested
    @DisplayName("workload")
    class LoadShape {

        @Test
        @DisplayName("a run that delivered its offered rate says so")
        void sustained() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:throughput.sustained");
                assertThat(finding.level()).isEqualTo(FindingLevel.PASS);
            });
        }

        @Test
        @DisplayName("a shortfall is a warning that refuses to guess which side caused it")
        void shortfall() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 0.82),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            var shortfall = findings.stream()
                    .filter(finding -> finding.id().equals("finding:throughput.shortfall"))
                    .findFirst().orElseThrow();

            assertThat(shortfall.level()).isEqualTo(FindingLevel.WARNING);
            // The service failing to absorb traffic and the generator failing to produce it are
            // different problems. Naming one would be a guess.
            assertThat(shortfall.detail())
                    .contains("could not absorb")
                    .contains("could not sustain");
            assertThat(shortfall.evidenceIds())
                    .contains(EvidenceIds.THROUGHPUT_TARGET, EvidenceIds.THROUGHPUT_ACHIEVED);
        }

        @Test
        @DisplayName("a severe shortfall is a failure: the run did not test what was configured")
        void severeShortfallIsAFailure() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 0.55),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:throughput.shortfall");
                assertThat(finding.level()).isEqualTo(FindingLevel.FAIL);
            });
        }

        @Test
        @DisplayName("a closed workload reports no shortfall, because throughput was never a target")
        void closedWorkloadHasNoShortfall() {
            var findings = detector.detect(identity(), workload(WorkloadModel.CLOSED, null),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            assertThat(findings).noneSatisfy(
                    finding -> assertThat(finding.id()).isEqualTo("finding:throughput.shortfall"));
            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:workload.closed");
                assertThat(finding.headline()).contains("outcome, not a target");
            });
        }
    }

    @Nested
    @DisplayName("operations")
    class Operations {

        @Test
        @DisplayName("a planned operation that issued nothing is a warning, never a flawless zero")
        void noTrafficIsAWarning() {
            var operation = new OperationEvidence(Fixtures.GET_ACCOUNT, "getAccount",
                    "GET /accounts/{id}", BigDecimal.valueOf(50), PayloadProvenance.SCHEMA_GENERATED,
                    null, List.of());

            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(operation),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).endsWith(".noTraffic");
                assertThat(finding.level()).isEqualTo(FindingLevel.WARNING);
                assertThat(finding.headline()).contains("issued no requests");
            });
        }

        @Test
        @DisplayName("an operation far slower than the aggregate is surfaced, since the aggregate hides it")
        void latencyDivergence() {
            // Aggregate p95 is 120 ms; this operation's is 800 ms. Blended into a total, it
            // disappears — which is the entire reason the breakdown exists.
            var slow = new OperationMetrics(Fixtures.GET_ORDER, "getOrder", null,
                    RequestsPerSecond.of(20), 1_000, 0,
                    LatencyPercentiles.builder().atMillis(95, 800).build());

            var operation = new OperationEvidence(Fixtures.GET_ORDER, "getOrder",
                    "GET /orders/{id}", BigDecimal.valueOf(50), PayloadProvenance.SCHEMA_GENERATED,
                    slow, List.of());

            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(operation),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).endsWith(".latency");
                assertThat(finding.level()).isEqualTo(FindingLevel.WARNING);
                assertThat(finding.evidenceIds())
                        .contains(EvidenceIds.operationLatency(Fixtures.GET_ORDER, Percentile.P95));
            });
        }

        @Test
        @DisplayName("an operation in line with the aggregate produces no noise")
        void comparableOperationIsSilent() {
            var normal = new OperationMetrics(Fixtures.GET_ORDER, "getOrder", null,
                    RequestsPerSecond.of(20), 1_000, 0,
                    LatencyPercentiles.builder().atMillis(95, 130).build());

            var operation = new OperationEvidence(Fixtures.GET_ORDER, "getOrder",
                    "GET /orders/{id}", BigDecimal.valueOf(50), PayloadProvenance.SCHEMA_GENERATED,
                    normal, List.of());

            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(operation),
                    ObservabilityEvidence.empty());

            assertThat(findings).noneSatisfy(
                    finding -> assertThat(finding.id()).endsWith(".latency"));
        }
    }

    @Nested
    @DisplayName("resources, and the line between correlation and cause")
    class Resources {

        @Test
        @DisplayName("a saturated resource alongside a breakpoint is reported as coincidence, not cause")
        void correlationIsNeverCausation() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(900, 0.0), breakpoint(), null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    observability(94));

            var correlation = findings.stream()
                    .filter(finding -> finding.id().endsWith(".correlation"))
                    .findFirst().orElseThrow();

            assertThat(correlation.level()).isEqualTo(FindingLevel.OBSERVATION);
            assertThat(correlation.headline()).contains("in the same run in which");
            assertThat(correlation.detail())
                    .contains("coincided")
                    .contains("not that either produced the other");
        }

        @Test
        @DisplayName("a saturated resource with every objective met is still worth knowing")
        void highUtilisationWithoutAViolation() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    observability(94));

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).endsWith(".high");
                assertThat(finding.level()).isEqualTo(FindingLevel.OBSERVATION);
            });
        }

        @Test
        @DisplayName("a resource nowhere near its limit is not remarked on")
        void ordinaryUtilisationIsSilent() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    observability(42));

            assertThat(findings).noneSatisfy(
                    finding -> assertThat(finding.id()).startsWith("finding:observation."));
        }

        @Test
        @DisplayName("a signal with a trace is held more firmly than one sampled once")
        void traceStrengthensTheFinding() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    observability(94));

            var withTrace = findings.stream()
                    .filter(finding -> finding.id().startsWith("finding:observation."))
                    .findFirst().orElseThrow();

            assertThat(withTrace.strength()).isEqualTo(EvidenceStrength.MEDIUM);
            assertThat(withTrace.detail()).contains("It moved");
        }

        @Test
        @DisplayName("a measurement no provider classified never becomes a limiting-resource claim")
        void anUnclassifiedSignalIsNeverALimitClaim() {
            // The same value that produces a finding above, with the classification removed. An
            // error rate, a cache hit ratio and a percentile all arrive looking exactly like this,
            // and under the old unit-based rule all three produced a resource finding.
            var unclassified = new ObservabilityEvidence(
                    List.of(ObservedSignal.of(pool(94).observation())),
                    List.of("Service metrics endpoint"), List.of());

            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(), unclassified);

            assertThat(findings).noneSatisfy(
                    finding -> assertThat(finding.id()).startsWith("finding:observation."));
        }

        @Test
        @DisplayName("the load generator's own saturation is never the service's constraint")
        void theGeneratorIsNeverTheServicesConstraint() {
            var generator = new ObservabilityEvidence(
                    List.of(ObservedSignal.of(new ResourceSignal(pool(99).observation(),
                            ResourceKind.CPU, ResourceScope.LOAD_GENERATOR,
                            ResourceLimit.inherentToPercentage()))),
                    List.of("Load generator"), List.of());

            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null, null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(), generator);

            // Vortex's own machine at 99% is worth noticing — that is what GENERATOR_SATURATED will
            // rest on — but it says nothing about the service, and must not appear as though it did.
            assertThat(findings).noneSatisfy(
                    finding -> assertThat(finding.id()).startsWith("finding:observation."));
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        void aBreakpointCarriesItsOwnEvidenceStrength() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(900, 0.0), breakpoint(), null),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:slo.breakpoint");
                assertThat(finding.strength()).isEqualTo(EvidenceStrength.HIGH);
            });
        }

        @Test
        @DisplayName("saturation that was not established says exactly that, and offers no number")
        void saturationNotEstablished() {
            var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                    performance(results(120, 0.0), null,
                            SystemSaturation.notEstablished("Only two levels were tested.")),
                    AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                    ObservabilityEvidence.empty());

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.id()).isEqualTo("finding:saturation.notEstablished");
                assertThat(finding.strength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
                assertThat(finding.headline()).contains("did not establish");
            });
        }
    }

    @Test
    @DisplayName("no finding, on any fixture, ever claims causation")
    void languageIsHeldToTheTable() {
        List<List<DeterministicFinding>> everyShape = List.of(
                detect(results(900, 0.05), thresholds(results(900, 0.05))),
                detect(results(120, 0.0), thresholds(results(120, 0.0))),
                detector.detect(identity(), workload(WorkloadModel.OPEN, 0.55),
                        performance(results(900, 0.2), breakpoint(),
                                SystemSaturation.notEstablished("Too few levels.")),
                        AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                        observability(99)),
                detector.detect(identity(), workload(WorkloadModel.CLOSED, null),
                        performance(results(120, 0.0), null, null),
                        AcceptanceEvidence.of(ThresholdEvaluation.empty()), List.of(),
                        ObservabilityEvidence.empty()));

        for (List<DeterministicFinding> findings : everyShape) {
            for (DeterministicFinding finding : findings) {
                String text = (finding.headline() + " " + finding.detail()).toLowerCase(Locale.ROOT);
                assertThat(FORBIDDEN)
                        .as("finding %s must not claim causation: %s", finding.id(), text)
                        .noneSatisfy(phrase -> assertThat(text).contains(phrase));
            }
        }
    }

    @Test
    @DisplayName("every finding cites evidence, and a finding that cannot is rejected outright")
    void findingsAlwaysCiteEvidence() {
        var findings = detect(results(900, 0.05), thresholds(results(900, 0.05)));

        assertThat(findings).isNotEmpty();
        assertThat(findings).allSatisfy(
                finding -> assertThat(finding.evidenceIds()).isNotEmpty());

        assertThatThrownBy(() -> new DeterministicFinding("finding:made.up", FindingLevel.FAIL,
                "Something went wrong.", "", EvidenceStrength.HIGH, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cites no evidence");
    }

    @Test
    @DisplayName("findings come back most serious first, so a reader sees failures before observations")
    void orderedBySeverity() {
        var findings = detector.detect(identity(), workload(WorkloadModel.OPEN, 0.55),
                performance(results(900, 0.0), breakpoint(), null),
                AcceptanceEvidence.of(thresholds(results(900, 0.0))), List.of(),
                observability(94));

        List<Integer> ranks = findings.stream().map(finding -> finding.level().rank()).toList();

        assertThat(ranks).isSorted();
    }

    // ---------------------------------------------------------------- fixtures

    private List<DeterministicFinding> detect(MeasuredResults results,
            ThresholdEvaluation evaluation) {
        return detector.detect(identity(), workload(WorkloadModel.OPEN, 1.0),
                performance(results, null, null), AcceptanceEvidence.of(evaluation), List.of(),
                ObservabilityEvidence.empty());
    }

    private ThresholdEvaluation thresholds(MeasuredResults results) {
        return new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
    }

    private static MeasuredResults results(long p95Millis, double errorFraction) {
        return Fixtures.results(p95Millis, errorFraction);
    }

    private static PerformanceEvidence performance(MeasuredResults results,
            SloBreakpoint breakpoint, SystemSaturation saturation) {
        return new PerformanceEvidence(results, breakpoint, saturation, null);
    }

    private static SloBreakpoint breakpoint() {
        return new SloBreakpoint(RequestsPerSecond.of(150), RequestsPerSecond.of(120),
                List.of("latency.p95"), EvidenceStrength.HIGH, 8);
    }

    /**
     * A connection pool the Actuator adapter classified, which is what it does today.
     *
     * <p>Classified deliberately. A finding that a resource reached its limit now requires a signal
     * that declared what it was and what its limit is, so a fixture carrying a bare percentage would
     * assert nothing except that unclassified measurements stay silent — which is what
     * {@link #anUnclassifiedSignalIsNeverALimitClaim()} is for.
     */
    private static ObservabilityEvidence observability(double utilisationPercent) {
        return new ObservabilityEvidence(List.of(ObservedSignal.of(pool(utilisationPercent))),
                List.of("Service metrics endpoint"), List.of());
    }

    private static ResourceSignal pool(double utilisationPercent) {
        var observation = MetricObservation
                .of("metric:hikaricp.connections.utilization", "hikaricp.connections.utilization",
                        MetricSource.ACTUATOR, MetricUnit.PERCENT, Aggregation.MAX,
                        utilisationPercent, WINDOW)
                .withTrace(new ObservationTrace(31, utilisationPercent, 47, START.plusSeconds(360)));

        return new ResourceSignal(observation, ResourceKind.POOL,
                ResourceScope.SYSTEM_UNDER_TEST, ResourceLimit.inherentToPercentage());
    }

    private static WorkloadEvidence workload(WorkloadModel model, Double deliveredFraction) {
        return new WorkloadEvidence(model, RequestsPerSecond.of(120), RequestsPerSecond.of(118),
                deliveredFraction, "", WorkloadSource.manual(),
                Duration.ofMinutes(10), Duration.ofMinutes(10), List.of(), List.of(),
                72_000L, "", 71_000, 12, ScriptSource.GENERATED, Map.of(), Map.of());
    }

    private static RunIdentity identity() {
        return new RunIdentity(ExecutionId.of("exec1"), ProjectId.of("checkout"),
                "checkout-service", "1.4.0", "production-peak", "", TestType.AVERAGE_LOAD,
                "local", EnvironmentType.LOCAL_ISOLATED, TestClassification.ISOLATED,
                DependencyMode.MOCKED, "http://localhost:8080", "",
                "EXTERNAL_ENDPOINT", "http://localhost:8080", "Externally managed", "", null,
                START, START, START.plusSeconds(600), Duration.ofMinutes(10));
    }
}
