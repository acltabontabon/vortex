package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.calibration.WorkloadDrift;
import com.acltabontabon.vortex.core.calibration.WorkloadSuggestion;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.recommendation.ShapeKind;
import com.acltabontabon.vortex.core.recommendation.WorkloadRecommendation;
import com.acltabontabon.vortex.core.recommendation.WorkloadRecommender;
import com.acltabontabon.vortex.core.safety.ExecutionPolicy;
import com.acltabontabon.vortex.core.safety.SafetyLimits;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A test can be created, changed, copied, removed, and previewed — the behaviour that used to be
 * split across Traffic and the workload editor.
 */
@WebMvcTest(controllers = TestsApiController.class)
@Import({Display.class, TestDefinitions.class, WorkloadView.class,
        WorkloadDiagramRenderer.class, LoadAxisRenderer.class,
        RateAllocator.class, WorkspaceAssembler.class})
@DisplayName("the life of a test")
class TestsApiControllerTest {

    private static final String SERVICE = "checkout";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private ExecutionRepository executions;
    @MockitoBean
    private WorkloadDrift drift;
    @MockitoBean
    private CalibrationPolicy calibration;
    @MockitoBean
    private com.acltabontabon.vortex.core.application.CapacityService capacityService;
    @MockitoBean
    private com.acltabontabon.vortex.app.service.TestRunner testRunner;
    @MockitoBean
    private WorkloadRecommender recommender;
    @MockitoBean
    private ExecutionPolicy executionPolicy;

    @BeforeEach
    void aConfiguredService() {
        when(projects.find(any())).thenReturn(Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));
        when(projects.readiness(any())).thenReturn(Fixtures.configuration().readiness(true, true));
        when(executions.findByProject(any(), anyInt())).thenReturn(List.of());
        when(executions.countByProject(any())).thenReturn(0L);
        when(executionPolicy.limits()).thenReturn(SafetyLimits.defaults());
    }

    private ProjectConfiguration saved() {
        ArgumentCaptor<ProjectConfiguration> captor =
                ArgumentCaptor.forClass(ProjectConfiguration.class);
        verify(projects).saveConfiguration(any(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("reading a test to edit")
    class ReadingForEdit {

        @Test
        @DisplayName("a flat test reports its one level, not staged")
        void aFlatTestIsNotReportedAsRamping() throws Exception {
            mvc.perform(get("/api/services/" + SERVICE + "/tests/average-load"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rate").value(20))
                    .andExpect(jsonPath("$.ramping").value(false))
                    .andExpect(jsonPath("$.peakRate").doesNotExist())
                    .andExpect(jsonPath("$.stages").doesNotExist());
        }

        @Test
        @DisplayName("a ramping test reports its start level and its stages, not the peak as a flat rate")
        void aRampingTestReportsItsStages() throws Exception {
            // Fixtures.breakpointShape(): startRate 20, four stages rising to 200.
            mvc.perform(get("/api/services/" + SERVICE + "/tests/capacity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rate").value(20))
                    .andExpect(jsonPath("$.ramping").value(true))
                    .andExpect(jsonPath("$.peakRate").value(200))
                    .andExpect(jsonPath("$.stages").value(4));
        }
    }

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("a new test is saved as described")
        void createSavesWhatWasDescribed() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "Production Peak",
                                      "type": "STRESS",
                                      "description": "Month-end settlement traffic.",
                                      "model": "OPEN",
                                      "rate": 120,
                                      "durationMinutes": 5,
                                      "weights": {"getAccount": 55, "getOrder": 25, "createOrder": 15, "cancelOrder": 5}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("production-peak"));

            Workload created = saved().workloadByName("production-peak").orElseThrow();
            assertThat(created.type().name()).isEqualTo("STRESS");
            assertThat(created.peakLevel().display()).isEqualTo("120");
            assertThat(created.operations().size()).isEqualTo(4);
        }

        @Test
        @DisplayName("renaming replaces rather than leaving both")
        void renamingDoesNotLeaveTheOldOneBehind() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "weekday-traffic",
                                      "originalName": "average-load",
                                      "type": "AVERAGE_LOAD",
                                      "model": "OPEN",
                                      "rate": 20,
                                      "durationMinutes": 10,
                                      "weights": {"getAccount": 70, "getOrder": 30}
                                    }
                                    """))
                    .andExpect(status().isOk());

            ProjectConfiguration after = saved();
            assertThat(after.workloadByName("weekday-traffic")).isPresent();
            assertThat(after.workloadByName("average-load")).isEmpty();
        }

        @Test
        @DisplayName("a spike's parameters round-trip through save and back, not flattened to an equal ramp")
        void aSavedSpikeRoundTripsItsShape() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "traffic-spike",
                                      "type": "SPIKE",
                                      "model": "OPEN",
                                      "shapeKind": "SPIKE",
                                      "spikeParams": {"baseline": 10, "peak": 100, "holdBeforeMinutes": 0.5, "holdAtPeakMinutes": 1},
                                      "weights": {"getAccount": 100}
                                    }
                                    """))
                    .andExpect(status().isOk());

            Workload created = saved().workloadByName("traffic-spike").orElseThrow();
            assertThat(created.shape().stages()).hasSize(4);
            assertThat(created.shape().stages().get(1).target().asDouble()).isEqualTo(100.0);
            assertThat(created.shape().stages().get(3).target().asDouble()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("an explicit, non-uniform stage list round-trips exactly, not re-derived as an even ramp")
        void anExplicitStageListRoundTripsUnchanged() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "capped-breakpoint",
                                      "type": "BREAKPOINT",
                                      "model": "OPEN",
                                      "shapeKind": "STAGED",
                                      "explicitStages": [
                                        {"level": 30, "durationSeconds": 300},
                                        {"level": 100, "durationSeconds": 900}
                                      ],
                                      "weights": {"getAccount": 100}
                                    }
                                    """))
                    .andExpect(status().isOk());

            Workload created = saved().workloadByName("capped-breakpoint").orElseThrow();
            assertThat(created.shape().stages()).hasSize(2);
            assertThat(created.shape().stages().get(0).target().asDouble()).isEqualTo(30.0);
            assertThat(created.shape().stages().get(0).duration()).isEqualTo(Duration.ofSeconds(300));
            assertThat(created.shape().stages().get(1).target().asDouble()).isEqualTo(100.0);
            assertThat(created.shape().stages().get(1).duration()).isEqualTo(Duration.ofSeconds(900));
        }

        @Test
        @DisplayName("a concurrency workload with no operation chosen is rejected, not silently accepted")
        void concurrencyNeedsOneOperation() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "batch",
                                      "type": "AVERAGE_LOAD",
                                      "model": "CLOSED",
                                      "vus": 50,
                                      "durationMinutes": 10
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("Choose the operation")));
        }
    }

    @Nested
    @DisplayName("duplicating")
    class Duplicating {

        @Test
        @DisplayName("a copy keeps the traffic but not the claim about where it came from")
        void aCopyDoesNotInheritProvenance() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/average-load/duplicate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("average-load-copy"));

            Workload copy = saved().workloadByName("average-load-copy").orElseThrow();
            Workload original = Fixtures.configuration().workloadByName("average-load").orElseThrow();

            assertThat(copy.peakLevel()).isEqualTo(original.peakLevel());
            assertThat(copy.source().isProductionInformed()).isFalse();
        }
    }

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("removes only the definition")
        void deleteRemovesOnlyTheDefinition() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/average-load/delete"))
                    .andExpect(status().isOk());

            assertThat(saved().workloadByName("average-load")).isEmpty();
        }

        @Test
        @DisplayName("something that is not there is reported, not silently ignored")
        void deletingAnAbsentTestReportsIt() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/never-existed/delete"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("No test named 'never-existed'"));
        }
    }

    @Nested
    @DisplayName("the live preview")
    class Preview {

        @Test
        @DisplayName("shows the rates the run will actually drive")
        void previewUsesTheRealAllocator() throws Exception {
            // 120 split 55/25/15/5 is 66/30/18/6 — largest remainder, not naive rounding.
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "OPEN",
                                      "rate": 120,
                                      "durationMinutes": 5,
                                      "weights": {"getAccount": 55, "getOrder": 25, "createOrder": 15, "cancelOrder": 5}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.problem").doesNotExist())
                    .andExpect(jsonPath("$.composition[?(@.operationId=='getAccount')].rateDisplay")
                            .value("66"));
        }

        @Test
        @DisplayName("an incomplete form is answered, not shouted at")
        void anEmptyMixExplainsItself() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"model": "OPEN", "rate": 120, "durationMinutes": 5}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.composition").doesNotExist())
                    .andExpect(jsonPath("$.shape").doesNotExist())
                    .andExpect(jsonPath("$.problem").value(
                            org.hamcrest.Matchers.containsString("at least one operation")));
        }

        @Test
        @DisplayName("a steady shape reports one stage at the flat rate, for the whole duration")
        void steadyShapeIsOneStage() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "OPEN",
                                      "rate": 120,
                                      "durationMinutes": 5,
                                      "weights": {"getAccount": 100}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shape.unit").value("requests/sec"))
                    .andExpect(jsonPath("$.shape.ramping").value(false))
                    .andExpect(jsonPath("$.shape.peakLevelValue").value(120.0))
                    .andExpect(jsonPath("$.shape.totalDurationMillis").value(5 * 60_000L))
                    .andExpect(jsonPath("$.shape.stages.length()").value(1))
                    .andExpect(jsonPath("$.shape.stages[0].levelValue").value(120.0))
                    .andExpect(jsonPath("$.shape.stages[0].durationMillis").value(5 * 60_000L));
        }

        @Test
        @DisplayName("a ramping shape reports every stage, ending at the peak, spanning the whole duration")
        void rampingShapeReportsEveryStage() throws Exception {
            // TestDefinitions.shape() spaces stages evenly from peakRate/stages up to peakRate —
            // 300 over 5 stages is 60/120/180/240/300, each 1 of the 5 total minutes.
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "OPEN",
                                      "peakRate": 300,
                                      "stages": 5,
                                      "durationMinutes": 5,
                                      "weights": {"getAccount": 100}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shape.ramping").value(true))
                    .andExpect(jsonPath("$.shape.peakLevelValue").value(300.0))
                    .andExpect(jsonPath("$.shape.stages.length()").value(5))
                    .andExpect(jsonPath("$.shape.stages[0].levelValue").value(60.0))
                    .andExpect(jsonPath("$.shape.stages[4].levelValue").value(300.0))
                    .andExpect(jsonPath("$.shape.stages[0].durationMillis").value(60_000L));
        }

        @Test
        @DisplayName("a spike's four parameters build a baseline-jump-hold-recovery shape")
        void spikeParamsBuildTheSpikePattern() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "OPEN",
                                      "type": "SPIKE",
                                      "shapeKind": "SPIKE",
                                      "spikeParams": {"baseline": 10, "peak": 100, "holdBeforeMinutes": 0.5, "holdAtPeakMinutes": 1},
                                      "weights": {"getAccount": 100}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shape.stages.length()").value(4))
                    .andExpect(jsonPath("$.shape.stages[0].levelValue").value(10.0))
                    .andExpect(jsonPath("$.shape.stages[1].levelValue").value(100.0))
                    .andExpect(jsonPath("$.shape.stages[2].levelValue").value(100.0))
                    .andExpect(jsonPath("$.shape.stages[3].levelValue").value(10.0))
                    .andExpect(jsonPath("$.headline")
                            .value("Jump from 10 requests/sec to 100 requests/sec and back over 2m"));
        }

        @Test
        @DisplayName("a concurrency ramp is built from Target/Stages too, not silently flattened to constant-vus")
        void concurrencyRampBuildsARampingVusShape() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "CLOSED",
                                      "shapeKind": "PROGRESSIVE_RAMP",
                                      "peakRate": 100,
                                      "stages": 4,
                                      "durationMinutes": 8,
                                      "singleOperation": "getAccount"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shape.unit").value("VUs"))
                    .andExpect(jsonPath("$.shape.ramping").value(true))
                    .andExpect(jsonPath("$.shape.stages.length()").value(4))
                    .andExpect(jsonPath("$.shape.stages[0].levelValue").value(25.0))
                    .andExpect(jsonPath("$.shape.stages[3].levelValue").value(100.0));
        }

        @Test
        @DisplayName("a concurrency shape reports its levels in VUs, never compared to a rate")
        void concurrencyShapeReportsVus() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/tests/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "model": "CLOSED",
                                      "vus": 50,
                                      "durationMinutes": 10,
                                      "singleOperation": "getAccount"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shape.unit").value("VUs"))
                    .andExpect(jsonPath("$.shape.peakLevelValue").value(50.0))
                    .andExpect(jsonPath("$.shape.stages[0].levelValue").value(50.0));
        }
    }

    @Nested
    @DisplayName("recommending a workload")
    class Recommending {

        @Test
        @DisplayName("renders the domain's recommendation, never inventing its own numbers")
        void rendersTheRecommendationVerbatim() throws Exception {
            var shape = new ConstantArrivalRateShape(RequestsPerSecond.of(10), Duration.ofSeconds(30));
            var recommendation = new WorkloadRecommendation(
                    com.acltabontabon.vortex.core.workload.TestType.SMOKE, ShapeKind.STEADY, shape,
                    "A very small, steady check.", WorkloadSource.manual(), false);
            when(recommender.recommend(any(), any(), any(), any(), any())).thenReturn(recommendation);

            mvc.perform(get("/api/services/" + SERVICE + "/tests/recommendation")
                            .param("type", "SMOKE").param("model", "OPEN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("SMOKE"))
                    .andExpect(jsonPath("$.shapeKind").value("STEADY"))
                    .andExpect(jsonPath("$.purpose").value("A very small, steady check."))
                    .andExpect(jsonPath("$.headline").value("10 requests/sec for 30 s"))
                    .andExpect(jsonPath("$.productionInformed").value(false))
                    .andExpect(jsonPath("$.availableShapeKinds[0]").value("STEADY"));
        }
    }

    @Nested
    @DisplayName("applying proposed workloads")
    class ApplyingProduction {

        @Test
        @DisplayName("refuses rather than guessing when there is no observation yet")
        void refusesWithoutAnObservation() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/production/apply"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied").value(false))
                    .andExpect(jsonPath("$.message").value(
                            "Record your observed production traffic first."));

            verify(projects, org.mockito.Mockito.never()).saveConfiguration(any(), any());
        }

        @Test
        @DisplayName("caps a breakpoint proposal at the environment's safety limit, same as a fresh recommendation would")
        void cappedBreakpointProposalMatchesTheRecommendersOwnCeiling() throws Exception {
            OperationMix mix = OperationMix.single(OperationId.of("getAccount"));
            ProductionObservation observation = new ProductionObservation(
                    RequestsPerSecond.of(80), RequestsPerSecond.of(100), RequestsPerSecond.of(100),
                    mix, "Grafana", Observation.over(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)), "");

            Environment sharedTest = new Environment(EnvironmentId.of("shared"), "shared",
                    EnvironmentType.SHARED_TEST, new ExternalEndpointTarget(TargetUrl.of("http://shared:8080")),
                    EnvironmentCapabilities.none(), DependencyMode.MOCKED, Map.of());
            ProjectConfiguration withObservation = Fixtures.configuration()
                    .withEnvironments(List.of(sharedTest))
                    .withProductionObservation(observation);
            when(projects.configuration(any())).thenReturn(withObservation);

            // CalibrationPolicy's own uncapped 3x-peak proposal — 300 requests/sec, well above
            // SHARED_TEST's default 100 requests/sec ceiling.
            WorkloadSuggestion uncapped = new WorkloadSuggestion(TestType.BREAKPOINT, "capacity",
                    "Where the service stops meeting its objectives.",
                    RequestsPerSecond.of(300),
                    List.of(RequestsPerSecond.of(75), RequestsPerSecond.of(150),
                            RequestsPerSecond.of(225), RequestsPerSecond.of(300)),
                    Duration.ofMinutes(20),
                    WorkloadSource.derived("Grafana", observation.observation(),
                            "Ramps to 3x your observed peak (300 requests/sec) in 4 stages."));
            when(calibration.propose(any())).thenReturn(List.of(uncapped));

            RampingArrivalRateShape cappedShape = new RampingArrivalRateShape(RequestsPerSecond.of(75),
                    List.of(Stage.ofRate(75, Duration.ofMinutes(5)), Stage.ofRate(100, Duration.ofMinutes(15))));
            WorkloadRecommendation cappedRecommendation = new WorkloadRecommendation(TestType.BREAKPOINT,
                    ShapeKind.STAGED, cappedShape,
                    "Load increases in stages until an objective is violated or the configured safety "
                            + "limit is reached.",
                    uncapped.source(), true);
            when(recommender.recommend(eq(TestType.BREAKPOINT), eq(WorkloadModel.OPEN), any(), any(), any()))
                    .thenReturn(cappedRecommendation);

            mvc.perform(post("/api/services/" + SERVICE + "/production/apply"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied").value(true));

            Workload created = saved().workloadByName("capacity").orElseThrow();
            assertThat(created.shape().peakLevel().asDouble()).isEqualTo(100.0);
            assertThat(created.source().derivationIfPresent().orElseThrow())
                    .contains("Capped at this environment's configured safety limit of 100 requests/sec.");
        }
    }
}
