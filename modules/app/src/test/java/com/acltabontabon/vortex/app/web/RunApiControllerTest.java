package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.app.service.AnalysisRunner;
import com.acltabontabon.vortex.app.service.EvidenceContextFactory;
import com.acltabontabon.vortex.app.service.TestRunner;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.application.ComparisonService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.port.Repositories.AnalysisRepository;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The run lifecycle's JSON edge, against a real evidence assembler — mirrors the retired
 * {@code ResultPageTest}, whose fixture builders it reuses, so the same domain expressions that
 * test guarded against a renamed accessor are still evaluated once here.
 */
@WebMvcTest(controllers = RunApiController.class)
@Import({Display.class, WorkloadView.class, WorkloadDiagramRenderer.class,
        LoadAxisRenderer.class, RateAllocator.class,
        RunApiControllerTest.RealEvidence.class})
class RunApiControllerTest {

    private static final ExecutionId EXECUTION_ID = ExecutionId.of("exec1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private EvidenceContextFactory evidenceContext;
    @MockitoBean
    private TestRunner testRunner;
    @MockitoBean
    private ExecutionRepository executions;
    @MockitoBean
    private AnalysisRepository analyses;
    @MockitoBean
    private AnalysisRunner analysisRunner;
    @MockitoBean
    private ArtifactStore artifacts;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private ComparisonService comparisons;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RealEvidence {
        @org.springframework.context.annotation.Bean
        com.acltabontabon.vortex.core.application.RunEvidenceService runEvidenceService() {
            return new com.acltabontabon.vortex.core.application.RunEvidenceService(
                    new com.acltabontabon.vortex.core.application.DeterministicAnalyzer(new ThresholdEvaluator(),
                            new com.acltabontabon.vortex.core.analysis.BreakpointDetector(),
                            new com.acltabontabon.vortex.core.analysis.SystemSaturationDetector()),
                    new com.acltabontabon.vortex.core.evidence.FindingDetector(),
                    new com.acltabontabon.vortex.core.evidence.EvidenceSanitizer(),
                    new com.acltabontabon.vortex.core.comparison.RegressionEvaluator(),
                    Clock.fixed(Fixtures.NOW));
        }
    }

    @BeforeEach
    void noAnalyses() {
        when(analyses.findLatest(any())).thenReturn(Optional.empty());
        when(analyses.findByExecution(any())).thenReturn(List.of());
        when(comparisons.previousCompatible(any())).thenReturn(Optional.empty());
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
    }

    private TestExecution completed(EffectiveTestPlan plan) {
        var results = Fixtures.resultsWithOperations(281, 0.0008, Fixtures.perOperation(140, 320));
        var evaluation = new ThresholdEvaluator().evaluate(plan.thresholds(), results);
        var summary = new DeterministicSummary(plan.intent().question(), evaluation.overall(),
                "Yes. The service met every objective.", results, evaluation, null, null,
                List.of(plan.classification().caveat()));

        return new TestExecution(EXECUTION_ID, plan.projectId(), plan, ExecutionState.COMPLETED,
                Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600), results, summary,
                null, null, null, "");
    }

    private TestExecution rampedThroughFailure() {
        var plan = Fixtures.breakpointPlan();
        var base = Fixtures.results(281, 0.0008);
        var results = new com.acltabontabon.vortex.core.metrics.MeasuredResults(
                base.window(), base.targetLoad(), base.achievedRate(), base.requests(),
                base.failures(), base.latency(), java.util.Map.of(),
                Fixtures.degradingSeries(plan.stages()), base.observations());

        var evaluation = new ThresholdEvaluator().evaluate(plan.thresholds(), results);
        var summary = new DeterministicSummary(plan.intent().question(), evaluation.overall(),
                "It stopped meeting its objectives above 100 requests/sec.", results, evaluation,
                null, null, List.of(plan.classification().caveat()));

        return new TestExecution(EXECUTION_ID, plan.projectId(), plan, ExecutionState.COMPLETED,
                Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(1200), results, summary,
                null, null, null, "");
    }

    private TestExecution sparse() {
        var plan = Fixtures.plan();
        var bare = new com.acltabontabon.vortex.core.metrics.MeasuredResults(
                new com.acltabontabon.vortex.core.metrics.TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(600)),
                null, null, 10, 0, com.acltabontabon.vortex.core.metrics.LatencyPercentiles.empty(),
                java.util.Map.of(), null, List.of());

        var summary = new DeterministicSummary("Does it work?",
                com.acltabontabon.vortex.core.threshold.Verdict.NOT_EVALUATED, "Not established.", bare,
                com.acltabontabon.vortex.core.threshold.ThresholdEvaluation.empty(), null, null, List.of());

        return new TestExecution(EXECUTION_ID, plan.projectId(), plan, ExecutionState.COMPLETED,
                Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600), bare, summary,
                null, null, null, "");
    }

    @Nested
    @DisplayName("preflight")
    class Preflight {

        @Test
        @DisplayName("reports what will happen, in the domain's own summary")
        void reportsWhatWillHappen() throws Exception {
            var plan = Fixtures.plan();
            var report = new com.acltabontabon.vortex.core.application.PreflightReport(plan, List.of(),
                    com.acltabontabon.vortex.core.safety.SafetyAssessment.clear());
            when(testRunner.prepare(any(), any(), any(), any(), any())).thenReturn(report);

            mockMvc.perform(get("/api/services/checkout/preflight")
                            .param("workload", "average-load").param("environment", "local"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canRun").value(true))
                    .andExpect(jsonPath("$.testTypeLabel").value(plan.testType().label()))
                    .andExpect(jsonPath("$.workloadName").value(plan.workloadName()));
        }

        @Test
        @DisplayName("without a workload or environment configured, says so rather than failing opaquely")
        void withoutAWorkloadOrEnvironment() throws Exception {
            when(projects.configuration(any())).thenReturn(com.acltabontabon.vortex.core.project.ProjectConfiguration.empty());

            mockMvc.perform(get("/api/services/checkout/preflight"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canRun").value(false))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                            "at least one workload and one environment")));
        }
    }

    @Nested
    @DisplayName("starting a run")
    class StartingARun {

        @Test
        @DisplayName("needing a typed confirmation is refused until it is given")
        void needsConfirmation() throws Exception {
            var plan = Fixtures.plan();
            var report = new com.acltabontabon.vortex.core.application.PreflightReport(plan, List.of(),
                    new com.acltabontabon.vortex.core.safety.SafetyAssessment(List.of(
                            com.acltabontabon.vortex.core.safety.SafetyFinding.challenge("mutating-ops",
                                    "This will mutate data", "detail", "I understand"))));
            when(testRunner.prepare(any(), any(), any(), any(), any())).thenReturn(report);

            mockMvc.perform(post("/api/services/checkout/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workload":"average-load","environment":"local"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.started").value(false))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("confirmation")));
        }
    }

    @Nested
    @DisplayName("reading a completed run")
    class ReadingACompletedRun {

        @Test
        @DisplayName("an arrival-rate result carries every section")
        void arrivalRateResultRenders() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.terminal").value(true))
                    .andExpect(jsonPath("$.evidence.workload.configuredPeakDisplay").value(
                            org.hamcrest.Matchers.containsString("requests/sec")))
                    .andExpect(jsonPath("$.evidence.hasOperationBreakdown").value(true))
                    .andExpect(jsonPath("$.evidence.operations[0].name").exists());
        }

        @Test
        @DisplayName("the five Phase 4 sections reach the wire, so the page can render the model")
        void phaseFourSectionsAreServed() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.validity.grade").exists())
                    .andExpect(jsonPath("$.evidence.resources").exists())
                    .andExpect(jsonPath("$.evidence.capacity").exists())
                    .andExpect(jsonPath("$.evidence.load").exists())
                    .andExpect(jsonPath("$.evidence.reliability").exists());
        }

        @Test
        @DisplayName("a run whose generator was never measured says so, rather than reporting no drops")
        void unmeasuredGeneratorIsNotZero() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            // The distinction this phase turns on, asserted at the wire rather than only in the
            // domain: an empty string is "nobody measured this", and a "0" would tell the page to
            // render a generator that kept up.
            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.load.droppedDisplay").value(""))
                    .andExpect(jsonPath("$.evidence.load.droppedWork").value(false))
                    .andExpect(jsonPath("$.evidence.resources.generatorObserved").value(false));
        }

        @Test
        @DisplayName("with no resource telemetry the capacity block still refuses with a reason")
        void capacityRefusalCarriesItsReason() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    // Never both a figure and a refusal, and never neither: the page shows one or
                    // the other in the largest type on the block.
                    .andExpect(jsonPath("$.evidence.capacity.headroomRefusal")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                    .andExpect(jsonPath("$.evidence.capacity.headroomDisplay").value(""));
        }

        @Test
        @DisplayName("each objective carries a typed kind from its own threshold, never guessed from its wording")
        void acceptanceResultsCarryTypedKind() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.acceptance.results[?(@.kind == 'LATENCY')]").isNotEmpty())
                    .andExpect(jsonPath("$.evidence.acceptance.results[?(@.kind == 'ERROR_RATE')]").isNotEmpty());
        }

        @Test
        @DisplayName("a concurrency result states VUs and carries the closed-workload caveat")
        void concurrencyResultRenders() throws Exception {
            var plan = Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)),
                    OperationMix.single(Fixtures.GET_ORDER));
            when(executions.findById(any())).thenReturn(Optional.of(completed(plan)));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.workload.open").value(false))
                    .andExpect(jsonPath("$.evidence.workload.deliveredCaveat").value(
                            org.hamcrest.Matchers.containsString("the virtual users went as fast as the service allowed")));
        }

        @Test
        @DisplayName("a run with nothing optional omits those sections rather than showing them empty")
        void sparseRunOmitsSections() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(sparse()));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.observability.present").value(false))
                    .andExpect(jsonPath("$.evidence.timeline.present").value(false))
                    .andExpect(jsonPath("$.evidence.acceptance.hasObjectives").value(false))
                    .andExpect(jsonPath("$.evidence.acceptance.absenceExplanation").exists());
        }

        @Test
        @DisplayName("the load axis names the last compliant level and the first that failed")
        void loadAxisNamesTheBoundary() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(rampedThroughFailure()));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.loadAxis.renderable").value(true))
                    .andExpect(jsonPath("$.evidence.loadAxis.highestCompliantDisplay").exists())
                    .andExpect(jsonPath("$.evidence.loadAxis.firstNonCompliantDisplay").exists());
        }

        @Test
        @DisplayName("a single held level draws no axis — one point is a measurement, not a range")
        void singleLevelDrawsNoAxis() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.loadAxis.renderable").value(false));
        }

        @Test
        @DisplayName("findings cite their evidence")
        void findingsAreCited() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.of(completed(Fixtures.plan())));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidence.hasFindings").value(true))
                    .andExpect(jsonPath("$.evidence.findings[0].evidenceIds").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("reading a run still in progress")
    class ReadingAnInProgressRun {

        @Test
        @DisplayName("carries the plan summary but no evidence yet")
        void carriesThePlanButNoEvidence() throws Exception {
            var plan = Fixtures.plan();
            var execution = new TestExecution(EXECUTION_ID, plan.projectId(), plan,
                    ExecutionState.RUNNING, Fixtures.NOW, Fixtures.NOW, null, null, null, null, null,
                    null, "");
            when(executions.findById(any())).thenReturn(Optional.of(execution));
            when(testRunner.progressFor(any())).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.running").value(true))
                    .andExpect(jsonPath("$.terminal").value(false))
                    .andExpect(jsonPath("$.evidence").doesNotExist())
                    .andExpect(jsonPath("$.plan.workloadName").value(plan.workloadName()));
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancelling {

        @Test
        @DisplayName("says plainly when there is nothing running to cancel")
        void nothingToCancel() throws Exception {
            when(testRunner.cancel(any())).thenReturn(false);

            mockMvc.perform(post("/api/runs/" + EXECUTION_ID.value() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cancelled").value(false))
                    .andExpect(jsonPath("$.message").value("That run is not currently in progress."));
        }
    }

    @Nested
    @DisplayName("AI analysis")
    class AiAnalysis {

        @Test
        @DisplayName("refuses to start when no model is available, and says why")
        void refusesWithoutAModel() throws Exception {
            when(analysisRunner.availability()).thenReturn(
                    PerformanceAssistant.Availability.unavailable("ollama", "Ollama was not detected.",
                            "Install it from https://ollama.com."));

            mockMvc.perform(post("/api/runs/" + EXECUTION_ID.value() + "/analyze"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.started").value(false))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Ollama was not detected")));
        }

        @Test
        @DisplayName("the panel reports unavailable rather than an empty result when there is no model")
        void panelReportsUnavailable() throws Exception {
            when(analysisRunner.isRunning(any())).thenReturn(false);
            when(analysisRunner.latest(any())).thenReturn(Optional.empty());
            when(analysisRunner.history(any())).thenReturn(List.of());
            when(analysisRunner.availability()).thenReturn(
                    PerformanceAssistant.Availability.unavailable("ollama", "Ollama was not detected.",
                            "Install it from https://ollama.com."));

            mockMvc.perform(get("/api/runs/" + EXECUTION_ID.value() + "/analysis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysing").value(false))
                    .andExpect(jsonPath("$.latest").doesNotExist())
                    .andExpect(jsonPath("$.availability.available").value(false));
        }
    }
}
