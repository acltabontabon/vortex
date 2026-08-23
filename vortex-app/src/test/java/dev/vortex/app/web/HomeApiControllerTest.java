package dev.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
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

    private TestExecution completedRun() {
        var results = Fixtures.results(281, 0.0008);
        var evaluation = new dev.vortex.core.threshold.ThresholdEvaluator()
                .evaluate(Fixtures.thresholds(), results);
        var summary = new dev.vortex.core.analysis.DeterministicSummary(
                "Can it hold 20 requests/sec?", evaluation.overall(),
                "It sustained the workload and met every objective.", results, evaluation,
                null, null, List.of());

        return new TestExecution(ExecutionId.of("e1"), ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "");
    }

    private TestExecution unevaluatedRun() {
        var results = new dev.vortex.core.metrics.MeasuredResults(
                new dev.vortex.core.metrics.TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(60)),
                dev.vortex.core.shared.RequestsPerSecond.of(20),
                dev.vortex.core.shared.RequestsPerSecond.of(20), 200, 0,
                dev.vortex.core.metrics.LatencyPercentiles.builder().atMillis(95, 120).build(),
                java.util.Map.of(), dev.vortex.core.metrics.MetricSeries.empty(), List.of());

        var evaluation = new dev.vortex.core.threshold.ThresholdEvaluator()
                .evaluate(Fixtures.thresholds(), results);
        var summary = new dev.vortex.core.analysis.DeterministicSummary(
                "Can it hold 20 requests/sec?", evaluation.overall(), "Undetermined.", results,
                evaluation, null, null, List.of());

        return new TestExecution(ExecutionId.of("e2"), ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(60), results, summary, null, null, null, "");
    }
}
