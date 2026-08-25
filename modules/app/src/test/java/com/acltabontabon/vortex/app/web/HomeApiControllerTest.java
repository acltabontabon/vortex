package com.acltabontabon.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.core.application.CapacityService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.capacity.HeadroomCalculator;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.workload.Workload;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The homepage's data, in the shape the React front end actually consumes.
 *
 * <p>The equivalent of the old {@code HomePageTest}, moved here with {@code HomeController} — the
 * behaviour under test (what a service card says, that "not evaluated" is never shown as a pass) is
 * exactly the same domain-driven behaviour; only the assertions moved from Thymeleaf markup strings
 * to {@code jsonPath}, since the page that consumes this is no longer server-rendered.
 *
 */
@WebMvcTest(controllers = HomeApiController.class)
@Import(Display.class)
class HomeApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapacityService capacity;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private ExecutionRepository executions;

    @BeforeEach
    void nothingMeasured() {
        when(capacity.latest(any())).thenReturn(Optional.empty());
        when(capacity.headroom(nullable(CapacityObservation.class), any()))
                .thenAnswer(call -> new HeadroomCalculator().calculate(null, false, null, null));
        when(projects.catalog(any())).thenReturn(Optional.empty());
        when(projects.configuration(any())).thenReturn(ProjectConfiguration.empty());
    }

    @Nested
    @DisplayName("with nothing set up yet")
    class Empty {

        @Test
        @DisplayName("returns no cards")
        void emptyHome() throws Exception {
            when(projects.all()).thenReturn(List.of());
            when(executions.findRecent(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards").isEmpty());
        }
    }

    @Nested
    @DisplayName("with services")
    class WithServices {

        @Test
        @DisplayName("a service still being set up cannot run, and names what is missing")
        void incompleteServiceCannotRun() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(executions.findRecent(anyInt())).thenReturn(List.of());
            when(projects.configuration(any())).thenReturn(ProjectConfiguration.empty());

            var missing = ProjectConfiguration.empty().readiness(false, false).blockers();

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].name").value("checkout-service"))
                    .andExpect(jsonPath("$.cards[0].canRun").value(false))
                    .andExpect(jsonPath("$.cards[0].blockers.length()").value(missing.size()))
                    .andExpect(jsonPath("$.cards[0].blockers[0]").value(missing.getFirst().label()));
        }

        @Test
        @DisplayName("a configured service can run")
        void configuredServiceCanRun() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].canRun").value(true));
        }

        @Test
        @DisplayName("a service with evidence leads with the run's own answer")
        void readyServiceLeadsWithItsAnswer() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of(completedRun()));

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].latestVerdict.answer").value(
                            "It sustained the workload and met every objective."))
                    .andExpect(jsonPath("$.cards[0].latestVerdict.verdict").value("PASS"));
        }

        /**
         * An objective that was never checked has not been met. This is the one distinction that
         * would be easiest to lose in translation to a badge/verdict/color, so it's asserted
         * directly against the wire value rather than any rendering of it.
         */
        @Test
        @DisplayName("a run without a verdict is not reported as a pass")
        void unevaluatedIsNotAPass() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of(unevaluatedRun()));

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].latestVerdict.verdict").value("NOT_EVALUATED"));
        }
    }

    @Nested
    @DisplayName("what a card carries for the homepage's own commands")
    class IntentFacts {

        @Test
        @DisplayName("lists every configured workload with the kind of test it is")
        void listsWorkloads() throws Exception {
            var configured = Fixtures.configuration().workloads();
            var kinds = configured.stream().map(w -> w.type().name()).distinct().sorted().toList();

            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of());

            // Stated against the fixture rather than a copied list, so this keeps meaning what it
            // says if the fixture's workloads change.
            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].workloads.length()").value(configured.size()))
                    .andExpect(jsonPath("$.cards[0].workloads[*].testType", containsInAnyOrder(
                            configured.stream().map(w -> w.type().name()).toArray())))
                    .andExpect(jsonPath("$.cards[0].workloads[*].name", containsInAnyOrder(
                            configured.stream().map(Workload::name).toArray())));

            // The point of shipping the set: the intents ask for a kind, not for a position.
            org.assertj.core.api.Assertions.assertThat(kinds).contains("BREAKPOINT", "AVERAGE_LOAD");
        }

        /** Absence is reported as absence, never as a default that reads like a measurement. */
        @Test
        @DisplayName("does not claim production was observed when it was not")
        void productionAbsenceIsHonest() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].productionObserved").value(false))
                    .andExpect(jsonPath("$.cards[0].objectivesConfigured").value(true));
        }

        /** A run still in flight is not yet evidence, so it is not yet something to compare. */
        @Test
        @DisplayName("counts only finished runs")
        void countsOnlyTerminalRuns() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of(completedRun(), runningRun()));

            mockMvc.perform(get("/api/home"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards[0].recentTerminalRunCount").value(1))
                    .andExpect(jsonPath("$.cards[0].running").value(true));
        }

        /**
         * The homepage reads every service's history from one scan, on purpose. Per-workload
         * runnability would cost a plan resolution — and a load-generator version subprocess —
         * for every workload of every service, which is why {@code WorkloadRefDto} does not carry
         * it. This is the test that fails loudly if somebody reaches for that later.
         */
        @Test
        @DisplayName("reads run history once for every service, not once per service")
        void scansHistoryOnce() throws Exception {
            when(projects.all()).thenReturn(List.of(Fixtures.project(), Fixtures.project(),
                    Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(executions.findRecent(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/home")).andExpect(status().isOk());

            verify(executions, times(1)).findRecent(anyInt());
        }
    }

    private TestExecution runningRun() {
        return new TestExecution(ExecutionId.of("e3"), ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.RUNNING, Fixtures.NOW, Fixtures.NOW, null, null, null, null, null,
                null, "");
    }

    private TestExecution completedRun() {
        var results = Fixtures.results(281, 0.0008);
        var evaluation = new com.acltabontabon.vortex.core.threshold.ThresholdEvaluator()
                .evaluate(Fixtures.thresholds(), results);
        var summary = new com.acltabontabon.vortex.core.analysis.DeterministicSummary(
                "Can it hold 20 requests/sec?", evaluation.overall(),
                "It sustained the workload and met every objective.", results, evaluation,
                null, null, List.of());

        return new TestExecution(ExecutionId.of("e1"), ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "");
    }

    private TestExecution unevaluatedRun() {
        var results = new com.acltabontabon.vortex.core.metrics.MeasuredResults(
                new com.acltabontabon.vortex.core.metrics.TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(60)),
                com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(20),
                com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(20), 200, 0,
                com.acltabontabon.vortex.core.metrics.LatencyPercentiles.builder().atMillis(95, 120).build(),
                java.util.Map.of(), com.acltabontabon.vortex.core.metrics.MetricSeries.empty(), List.of());

        var evaluation = new com.acltabontabon.vortex.core.threshold.ThresholdEvaluator()
                .evaluate(Fixtures.thresholds(), results);
        var summary = new com.acltabontabon.vortex.core.analysis.DeterministicSummary(
                "Can it hold 20 requests/sec?", evaluation.overall(), "Undetermined.", results,
                evaluation, null, null, List.of());

        return new TestExecution(ExecutionId.of("e2"), ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(60), results, summary, null, null, null, "");
    }
}
