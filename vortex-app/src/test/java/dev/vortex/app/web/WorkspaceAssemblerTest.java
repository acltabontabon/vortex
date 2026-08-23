package dev.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.vortex.app.service.TestRunner;
import dev.vortex.app.web.WorkspaceDtos.CapacityDto;
import dev.vortex.app.web.WorkspaceDtos.RunSummaryDto;
import dev.vortex.app.web.WorkspaceDtos.TestRowDto;
import dev.vortex.app.web.WorkspaceDtos.TestTypeEvidenceDto;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.WorkloadDrift;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.workload.TestType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceAssembler#evidenceByTestType} — the header's "what does Vortex know about this
 * service" rollup. What is under test here is the semantic rule the redesign exists to enforce:
 * each test type's evidence is read as what that type actually measures, never a manufactured
 * universal figure, and a run in flight is never confused with — or allowed to overwrite — prior
 * completed evidence.
 *
 * <p>Exercised directly against the assembler rather than through the controller: every fact this
 * method reads already arrived pre-computed on {@code TestRowDto}, so a hand-built row is enough to
 * pin down the mapping without also standing up a matching {@code ProjectConfiguration}.
 */
class WorkspaceAssemblerTest {

    private final WorkspaceAssembler assembler =
            new WorkspaceAssembler(mock(ProjectService.class), mock(CapacityService.class),
                    mock(WorkloadDrift.class), mock(WorkloadView.class), mock(TestRunner.class),
                    new Display());

    private static RunSummaryDto aRun(TestType type, String verdict, String levelDisplay,
            String durationDisplay, String isoTimestamp) {
        return new RunSummaryDto("exec-1", verdict, verdict.equals("PASS") ? "Pass" : "Fail",
                "Completed", true, "a-test", type.name(), type.label(), levelDisplay, "local",
                "ISOLATED", "2.17.0", "It held.", "120 ms", durationDisplay, "44 minutes ago",
                isoTimestamp, true, List.of());
    }

    private static CapacityDto quotableCapacity(String compliantLevel, String headroom) {
        return new CapacityDto(compliantLevel, "Tested SLO-compliant capacity",
                compliantLevel + " compliant", "Tested capacity boundary", true, "ESTABLISHED",
                "established", "High", null, headroom, headroom == null ? "Not comparable" : null,
                "2.17.0", "local", "ISOLATED", "MOCKED", "a-test", List.of(), List.of(), "1m",
                "22 Aug 2026, 04:55", "exec-1", List.of(), List.of());
    }

    private static CapacityDto nonQuotableCapacity(String boundaryStatusLabel) {
        return new CapacityDto(null, "Tested SLO-compliant capacity", "not established",
                "Tested capacity boundary", false, "FAR_EDGE_NOT_REACHED", boundaryStatusLabel,
                "Low", null, null, "Not comparable", "2.17.0", "local", "ISOLATED", "MOCKED",
                "a-test", List.of(), List.of(), "1m", "22 Aug 2026, 04:55", "exec-1", List.of(),
                List.of());
    }

    private static TestRowDto aTestRow(TestType type, RunSummaryDto latestRun, CapacityDto capacity) {
        return new TestRowDto("a-test", null, "does it work?", type.name(), type.label(),
                type.question(), type.isSaturating(), "OPEN", "Arrival rate", "20 requests/sec",
                "requests/sec", "10m", 1, false, 1, null, null, true, List.of(), "local",
                latestRun, latestRun == null ? 0 : 1, null, List.of(), null, capacity, null);
    }

    private Map<String, TestTypeEvidenceDto> byType(List<TestTypeEvidenceDto> evidence) {
        return evidence.stream().collect(java.util.stream.Collectors.toMap(
                TestTypeEvidenceDto::testType, e -> e));
    }

    @Test
    @DisplayName("carries every test type, in the domain's own order, even for a brand new service")
    void allSixTypesAlwaysPresent() {
        List<TestTypeEvidenceDto> evidence = assembler.evidenceByTestType(List.of(), null);

        assertThat(evidence).hasSize(6);
        assertThat(evidence.stream().map(TestTypeEvidenceDto::testType).toList())
                .containsExactly("SMOKE", "AVERAGE_LOAD", "STRESS", "SPIKE", "SOAK", "BREAKPOINT");
        assertThat(evidence).allSatisfy(e -> assertThat(e.hasEvidence()).isFalse());
    }

    @Nested
    @DisplayName("what a test type's evidence is actually about")
    class Semantics {

        @Test
        @DisplayName("Smoke is a pass/fail word, never a fabricated throughput")
        void smokeIsAnOutcome() {
            RunSummaryDto run = aRun(TestType.SMOKE, "PASS", "5 requests/sec", "10s",
                    "2026-08-22T04:55:00Z");
            var evidence = byType(assembler.evidenceByTestType(
                    List.of(aTestRow(TestType.SMOKE, run, null)), null));

            TestTypeEvidenceDto smoke = evidence.get("SMOKE");
            assertThat(smoke.hasEvidence()).isTrue();
            assertThat(smoke.primaryValueKind()).isEqualTo("OUTCOME");
            assertThat(smoke.primaryValue()).isEqualTo("Pass");
        }

        @Test
        @DisplayName("Average load and Spike report the level that run actually used")
        void averageLoadAndSpikeReportTheTestedLevel() {
            RunSummaryDto run = aRun(TestType.AVERAGE_LOAD, "PASS", "42 requests/sec", "10m",
                    "2026-08-22T04:55:00Z");
            var evidence = byType(assembler.evidenceByTestType(
                    List.of(aTestRow(TestType.AVERAGE_LOAD, run, null)), null));

            TestTypeEvidenceDto averageLoad = evidence.get("AVERAGE_LOAD");
            assertThat(averageLoad.primaryValueKind()).isEqualTo("RATE");
            assertThat(averageLoad.primaryValue()).isEqualTo("42 requests/sec");
        }

        @Test
        @DisplayName("Breakpoint reports the detected level, and the multiple over production it earned")
        void breakpointReportsTheDetectedLevel() {
            RunSummaryDto run = aRun(TestType.BREAKPOINT, "PASS", "200 requests/sec", "20m",
                    "2026-08-22T04:55:00Z");
            CapacityDto capacity = quotableCapacity("112 requests/sec", "3.2×");
            var evidence = byType(assembler.evidenceByTestType(
                    List.of(aTestRow(TestType.BREAKPOINT, run, capacity)), null));

            TestTypeEvidenceDto breakpoint = evidence.get("BREAKPOINT");
            assertThat(breakpoint.primaryValueKind()).isEqualTo("RATE");
            assertThat(breakpoint.primaryValue()).isEqualTo("112 requests/sec");
            assertThat(breakpoint.secondaryValue()).isEqualTo("3.2×");
        }

        @Test
        @DisplayName("Stress falls back to the domain's own boundary sentence when no level is quotable")
        void stressFallsBackToTheBoundarySentence() {
            RunSummaryDto run = aRun(TestType.STRESS, "FAIL", "150 requests/sec", "15m",
                    "2026-08-22T04:55:00Z");
            CapacityDto capacity = nonQuotableCapacity("not established: results were not monotonic");
            var evidence = byType(assembler.evidenceByTestType(
                    List.of(aTestRow(TestType.STRESS, run, capacity)), null));

            TestTypeEvidenceDto stress = evidence.get("STRESS");
            assertThat(stress.primaryValueKind()).isEqualTo("OUTCOME");
            assertThat(stress.primaryValue()).isEqualTo("not established: results were not monotonic");
        }

        @Test
        @DisplayName("Soak reports its measured duration — how long it held, not a rate")
        void soakReportsItsMeasuredDuration() {
            RunSummaryDto run = aRun(TestType.SOAK, "PASS", "10 requests/sec", "6h",
                    "2026-08-22T04:55:00Z");
            var evidence = byType(assembler.evidenceByTestType(
                    List.of(aTestRow(TestType.SOAK, run, null)), null));

            TestTypeEvidenceDto soak = evidence.get("SOAK");
            assertThat(soak.primaryValueKind()).isEqualTo("DURATION");
            assertThat(soak.primaryValue()).isEqualTo("6h");
        }
    }

    @Test
    @DisplayName("picks the most recently run test when more than one test shares a type")
    void picksTheMostRecentTestOfASharedType() {
        RunSummaryDto older = aRun(TestType.AVERAGE_LOAD, "PASS", "20 requests/sec", "10m",
                "2026-08-20T00:00:00Z");
        RunSummaryDto newer = aRun(TestType.AVERAGE_LOAD, "PASS", "50 requests/sec", "10m",
                "2026-08-22T00:00:00Z");

        var evidence = byType(assembler.evidenceByTestType(List.of(
                aTestRow(TestType.AVERAGE_LOAD, older, null),
                aTestRow(TestType.AVERAGE_LOAD, newer, null)), null));

        assertThat(evidence.get("AVERAGE_LOAD").primaryValue()).isEqualTo("50 requests/sec");
    }

    @Test
    @DisplayName("marks a test type as running without discarding — or requiring — prior evidence")
    void runningIsIndependentOfHasEvidence() {
        TestExecution running = TestExecution.create(ExecutionId.of("exec-live"),
                Fixtures.breakpointPlan(), Fixtures.NOW);
        RunSummaryDto priorRun = aRun(TestType.BREAKPOINT, "PASS", "100 requests/sec", "10m",
                "2026-08-20T00:00:00Z");
        CapacityDto priorCapacity = quotableCapacity("100 requests/sec", null);

        var evidence = byType(assembler.evidenceByTestType(
                List.of(aTestRow(TestType.BREAKPOINT, priorRun, priorCapacity)), running));

        TestTypeEvidenceDto breakpoint = evidence.get("BREAKPOINT");
        assertThat(breakpoint.running()).isTrue();
        assertThat(breakpoint.hasEvidence()).isTrue();
        assertThat(breakpoint.primaryValue()).isEqualTo("100 requests/sec");

        // Every other type is untouched by the run in flight.
        assertThat(evidence.get("SMOKE").running()).isFalse();
    }

    @Test
    @DisplayName("a first-ever run in flight has no prior evidence to preserve, and says so honestly")
    void firstRunInFlightHasNoPriorEvidence() {
        TestExecution running = TestExecution.create(ExecutionId.of("exec-live"),
                Fixtures.breakpointPlan(), Fixtures.NOW);

        var evidence = byType(assembler.evidenceByTestType(List.of(), running));

        TestTypeEvidenceDto breakpoint = evidence.get("BREAKPOINT");
        assertThat(breakpoint.running()).isTrue();
        assertThat(breakpoint.hasEvidence()).isFalse();
        assertThat(breakpoint.primaryValue()).isNull();
    }
}
