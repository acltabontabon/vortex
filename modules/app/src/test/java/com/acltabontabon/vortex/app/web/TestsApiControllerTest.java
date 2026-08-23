package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.calibration.WorkloadDrift;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
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

    @BeforeEach
    void aConfiguredService() {
        when(projects.find(any())).thenReturn(Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));
        when(projects.readiness(any())).thenReturn(Fixtures.configuration().readiness(true, true));
        when(executions.findByProject(any(), anyInt())).thenReturn(List.of());
        when(executions.countByProject(any())).thenReturn(0L);
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
    }
}
