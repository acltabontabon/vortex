package dev.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.app.service.ComparisonAnalysisRunner;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.application.ComparisonService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.threshold.ThresholdEvaluator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The run history across every service, and two runs set side by side, as JSON. */
@WebMvcTest(controllers = GlobalRunsApiController.class)
@Import(Display.class)
class GlobalRunsApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private ExecutionRepository executions;
    @MockitoBean
    private ComparisonService comparisons;
    @MockitoBean
    private ComparisonAnalysisRunner comparisonAnalysisRunner;

    private TestExecution completed(EffectiveTestPlan plan, ExecutionId id) {
        var results = Fixtures.resultsWithOperations(281, 0.0008, Fixtures.perOperation(140, 320));
        var evaluation = new ThresholdEvaluator().evaluate(plan.thresholds(), results);
        var summary = new DeterministicSummary(plan.intent().question(), evaluation.overall(),
                "Yes.", results, evaluation, null, null, List.of());
        return new TestExecution(id, plan.projectId(), plan, ExecutionState.COMPLETED,
                Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600), results, summary,
                null, null, null, "");
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        @DisplayName("rows carry the plan and outcome of the run, not today's configuration")
        void rowsCarryThePlanAndOutcome() throws Exception {
            var plan = Fixtures.plan();
            var execution = completed(plan, ExecutionId.of("exec1"));
            when(executions.findRecent(anyInt())).thenReturn(List.of(execution));
            when(projects.all()).thenReturn(List.of());

            mockMvc.perform(get("/api/runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalBeforeFilters").value(1))
                    .andExpect(jsonPath("$.rows[0].executionId").value("exec1"))
                    .andExpect(jsonPath("$.rows[0].workloadName").value(plan.workloadName()))
                    .andExpect(jsonPath("$.rows[0].terminal").value(true))
                    .andExpect(jsonPath("$.rows[0].verdict").exists());
        }

        @Test
        @DisplayName("a filter that matches nothing empties the rows without losing the true total")
        void filterMatchingNothingEmptiesRows() throws Exception {
            var plan = Fixtures.plan();
            var execution = completed(plan, ExecutionId.of("exec1"));
            when(executions.findRecent(anyInt())).thenReturn(List.of(execution));
            when(projects.all()).thenReturn(List.of());

            mockMvc.perform(get("/api/runs").param("environment", "nowhere-configured"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalBeforeFilters").value(1))
                    .andExpect(jsonPath("$.rows").isEmpty());
        }

        @Test
        @DisplayName("the offered filter values are only what this history actually contains")
        void offeredFiltersAreOnlyWhatExists() throws Exception {
            var plan = Fixtures.plan();
            var execution = completed(plan, ExecutionId.of("exec1"));
            when(executions.findRecent(anyInt())).thenReturn(List.of(execution));
            when(projects.all()).thenReturn(List.of());

            mockMvc.perform(get("/api/runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environments[0]").value(plan.environmentName()))
                    .andExpect(jsonPath("$.workloadNames[0]").value(plan.workloadName()));
        }
    }

    @Nested
    @DisplayName("compare")
    class Compare {

        @Test
        @DisplayName("two runs of the same experiment support a regression verdict")
        void sameExperimentSupportsAVerdict() throws Exception {
            var plan = Fixtures.plan();
            var baseline = completed(plan, ExecutionId.of("baseline1"));
            var candidate = completed(plan, ExecutionId.of("candidate1"));
            when(executions.findById(ExecutionId.of("baseline1"))).thenReturn(Optional.of(baseline));
            when(executions.findById(ExecutionId.of("candidate1"))).thenReturn(Optional.of(candidate));

            var comparison = new dev.vortex.core.comparison.RegressionEvaluator()
                    .compare(baseline, candidate);
            var verdict = new dev.vortex.core.comparison.RegressionEvaluator().evaluate(comparison);
            when(comparisons.compareAndEvaluate(baseline, candidate))
                    .thenReturn(new ComparisonService.Result(baseline, candidate, comparison, verdict));

            mockMvc.perform(get("/api/runs/compare")
                            .param("baseline", "baseline1").param("candidate", "candidate1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.supportsRegressionVerdict").value(true))
                    .andExpect(jsonPath("$.verdictLabel").value(verdict.label()))
                    .andExpect(jsonPath("$.baseline.executionId").value("baseline1"))
                    .andExpect(jsonPath("$.candidate.executionId").value("candidate1"));
        }

        @Test
        @DisplayName("an unknown execution id is refused with 404, not an opaque server error")
        void unknownExecutionIsRefused() throws Exception {
            when(executions.findById(any())).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/runs/compare").param("baseline", "x").param("candidate", "y"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("AI comparison analysis")
    class AiComparisonAnalysis {

        @Test
        @DisplayName("refuses to start when no model is available, and says why")
        void refusesWithoutAModel() throws Exception {
            var plan = Fixtures.plan();
            var baseline = completed(plan, ExecutionId.of("baseline1"));
            var candidate = completed(plan, ExecutionId.of("candidate1"));
            when(executions.findById(ExecutionId.of("baseline1"))).thenReturn(Optional.of(baseline));
            when(executions.findById(ExecutionId.of("candidate1"))).thenReturn(Optional.of(candidate));
            when(comparisonAnalysisRunner.availability()).thenReturn(
                    PerformanceAssistant.Availability.unavailable("ollama", "Ollama was not detected.",
                            "Install it from https://ollama.com."));

            mockMvc.perform(post("/api/runs/compare/analyze")
                            .param("baseline", "baseline1").param("candidate", "candidate1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.started").value(false))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Ollama was not detected")));
        }

        @Test
        @DisplayName("the panel reports which pair is analysing")
        void panelReportsAnalysing() throws Exception {
            when(comparisonAnalysisRunner.isRunning(any(), any())).thenReturn(true);
            when(comparisonAnalysisRunner.latest(any(), any())).thenReturn(Optional.empty());
            when(comparisonAnalysisRunner.availability()).thenReturn(
                    PerformanceAssistant.Availability.unavailable("ollama", "x", "y"));

            mockMvc.perform(get("/api/runs/compare/analysis")
                            .param("baseline", "baseline1").param("candidate", "candidate1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysing").value(true))
                    .andExpect(jsonPath("$.latest").doesNotExist());
        }
    }
}
