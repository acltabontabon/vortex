package dev.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.app.service.TestRunner;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.PlanResolutionException;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.calibration.WorkloadDrift;
import dev.vortex.core.capacity.BoundaryEdge;
import dev.vortex.core.capacity.BoundaryStatus;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.ConstraintCandidate;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.analysis.EvidenceStrength;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.StageWindowBasis;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.workload.RateAllocator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The service workspace's data, in the shape the workbench consumes.
 *
 * <p>What is under test here is the wire contract and nothing beneath it: that a test row carries
 * everything needed to decide whether to run it, that a blocked test says why in the domain's own
 * words rather than merely going quiet, and that a refusal to compute travels as a reason rather
 * than as a missing field. The decisions themselves belong to {@code vortex-core} and are tested
 * there.
 */
@WebMvcTest(controllers = ServiceApiController.class)
@Import({Display.class, WorkloadView.class, WorkloadDiagramRenderer.class,
        LoadAxisRenderer.class, RateAllocator.class,
        WorkspaceAssembler.class, ServiceApiControllerTest.RealDrift.class})
class ServiceApiControllerTest {

    /** The real drift check rather than a mock — its three-way answer is part of the contract. */
    @TestConfiguration
    static class RealDrift {
        @Bean
        WorkloadDrift workloadDrift() {
            return new WorkloadDrift(new CalibrationPolicy());
        }
    }

    private static final String SERVICE = "/api/services/checkout";

    /** A capacity observation for the "capacity" test — shared by Overview and Evidence below. */
    private static CapacityObservation observation(String workloadName, BoundaryStatus status,
            BoundaryEdge failing, List<ConstraintCandidate> candidates) {
        return new CapacityObservation(ProjectId.of("checkout"), ExecutionId.of("exec-1"),
                "2.17.0", RequestsPerSecond.of(400), WorkloadModel.OPEN, "staging",
                TestClassification.INTEGRATED, DependencyMode.REAL, List.of("getOrder 100%"),
                workloadName, List.of("p95 < 500 ms"), Duration.ofMinutes(20),
                Fixtures.plan().fingerprint(), Fixtures.NOW, failing, status,
                EvidenceStrength.MEDIUM, candidates);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private CapacityService capacity;

    @MockitoBean
    private ExecutionRepository executions;

    @MockitoBean
    private TestRunner testRunner;

    @BeforeEach
    void nothingMeasured() {
        when(projects.find(any())).thenReturn(Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));
        when(capacity.latest(any())).thenReturn(Optional.empty());
        when(capacity.latestPerWorkload(any())).thenReturn(Map.of());
        when(capacity.headroom(nullable(CapacityObservation.class), nullable(
                dev.vortex.core.capacity.ProductionObservation.class)))
                .thenAnswer(call -> new HeadroomCalculator().calculate(null, false, null, null));
        when(executions.findByProject(any(), anyInt())).thenReturn(List.of());
        when(executions.countByProject(any())).thenReturn(0L);
        when(testRunner.resolve(any(), anyString(), anyString(), nullable(String.class), any()))
                .thenReturn(Fixtures.plan());
    }

    @Nested
    @DisplayName("the workspace header")
    class Header {

        @Test
        @DisplayName("names the service, its target and the classification that target implies")
        void identity() throws Exception {
            mockMvc.perform(get(SERVICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("checkout-service"))
                    .andExpect(jsonPath("$.target.environmentName").value("local"))
                    .andExpect(jsonPath("$.target.baseUrl").value("http://localhost:8080"))
                    .andExpect(jsonPath("$.target.classification").value("ISOLATED"))
                    .andExpect(jsonPath("$.release").value("2.17.0"))
                    .andExpect(jsonPath("$.testCount").value(3));
        }

        @Test
        @DisplayName("reports readiness as a count and a list, never as a score")
        void readinessIsCounted() throws Exception {
            mockMvc.perform(get(SERVICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.readiness.canRun").value(true))
                    .andExpect(jsonPath("$.readiness.blockerCount").value(0))
                    .andExpect(jsonPath("$.readiness.items.length()").value(7));
        }

        @Test
        @DisplayName("a service with no environment cannot run, and says which items block it")
        void blockedWithoutAnEnvironment() throws Exception {
            when(projects.configuration(any())).thenReturn(ProjectConfiguration.empty());
            when(projects.catalog(any())).thenReturn(Optional.empty());

            mockMvc.perform(get(SERVICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.target").doesNotExist())
                    .andExpect(jsonPath("$.readiness.canRun").value(false))
                    .andExpect(jsonPath("$.readiness.blockerCount").value(2));
        }

        @Test
        @DisplayName("an unknown service is a 404, not an empty workspace")
        void unknownService() throws Exception {
            when(projects.find(any())).thenReturn(Optional.empty());

            mockMvc.perform(get(SERVICE)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the test inventory")
    class Tests {

        @Test
        @DisplayName("lists every configured test with what it answers and what it offers")
        void listsTests() throws Exception {
            mockMvc.perform(get(SERVICE + "/tests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests.length()").value(3))
                    .andExpect(jsonPath("$.tests[0].name").value("average-load"))
                    .andExpect(jsonPath("$.tests[0].testTypeLabel").value("Average load"))
                    .andExpect(jsonPath("$.tests[0].question").value(
                            "Does the service meet its objectives under the traffic it normally "
                                    + "receives?"))
                    .andExpect(jsonPath("$.tests[0].levelDisplay").value("20 requests/sec"))
                    .andExpect(jsonPath("$.tests[0].durationDisplay").value("10m"))
                    .andExpect(jsonPath("$.tests[0].runnable").value(true))
                    .andExpect(jsonPath("$.tests[0].problems").isEmpty());
        }

        @Test
        @DisplayName("states a level's unit rather than a bare number")
        void levelsCarryTheirUnit() throws Exception {
            mockMvc.perform(get(SERVICE + "/tests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests[?(@.name == 'batch-workers')].levelDisplay")
                            .value("50 concurrent users"))
                    .andExpect(jsonPath("$.tests[?(@.name == 'batch-workers')].levelUnit")
                            .value("VUs"));
        }

        @Test
        @DisplayName("carries where a test's numbers came from, so a run can be read at its worth")
        void provenanceTravels() throws Exception {
            mockMvc.perform(get(SERVICE + "/tests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests[0].source.kind").value("MANUAL"))
                    .andExpect(jsonPath("$.tests[0].source.productionInformed").value(false));
        }

        @Test
        @DisplayName("a test that cannot run says why, in the domain's words, and is not hidden")
        void blockedTestExplainsItself() throws Exception {
            when(testRunner.resolve(any(), eq("capacity"), anyString(), nullable(String.class),
                    any()))
                    .thenThrow(new PlanResolutionException("cannot resolve",
                            List.of("POST /orders has not been reviewed.")));

            mockMvc.perform(get(SERVICE + "/tests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests[?(@.name == 'capacity')].runnable").value(false))
                    .andExpect(jsonPath("$.tests[?(@.name == 'capacity')].problems[0]")
                            .value("POST /orders has not been reviewed."));
        }

        @Test
        @DisplayName("offers the six questions with the teaching text the domain carries")
        void testTypesCarryTheirQuestion() throws Exception {
            mockMvc.perform(get(SERVICE + "/tests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.testTypes.length()").value(6))
                    .andExpect(jsonPath("$.testTypes[0].name").value("SMOKE"))
                    .andExpect(jsonPath("$.testTypes[0].question").isNotEmpty())
                    .andExpect(jsonPath("$.testTypes[0].guidance").isNotEmpty())
                    .andExpect(jsonPath("$.testTypes[?(@.name == 'AVERAGE_LOAD')]"
                            + ".configuredTestCount").value(2));
        }
    }

    @Nested
    @DisplayName("overview")
    class Overview {

        @Test
        @DisplayName("opens with the objectives and the tests, and invents no figures it lacks")
        void factsAndTests() throws Exception {
            mockMvc.perform(get(SERVICE + "/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objectives.length()").value(3))
                    .andExpect(jsonPath("$.tests.length()").value(3))
                    .andExpect(jsonPath("$.production").doesNotExist())
                    .andExpect(jsonPath("$.capacity").doesNotExist())
                    .andExpect(jsonPath("$.latestRun").doesNotExist())
                    .andExpect(jsonPath("$.range.renderable").value(false));
        }

        @Test
        @DisplayName("suggests a first run only where the domain says one has never happened")
        void suggestsSmokeOnlyWhenNothingHasRun() throws Exception {
            mockMvc.perform(get(SERVICE + "/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.suggestSmokeTest").value(true));
        }

        @Test
        @DisplayName("a service that cannot run yet is not told to run a smoke test")
        void noSuggestionWhileBlocked() throws Exception {
            when(projects.configuration(any())).thenReturn(ProjectConfiguration.empty());
            when(projects.catalog(any())).thenReturn(Optional.empty());

            mockMvc.perform(get(SERVICE + "/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.suggestSmokeTest").value(false));
        }

        @Test
        @DisplayName("an unknown service is a 404")
        void unknownService() throws Exception {
            when(projects.find(eq(ProjectId.of("checkout")))).thenReturn(Optional.empty());

            mockMvc.perform(get(SERVICE + "/overview")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a test carries its own capacity evidence, not a different test's")
        void eachTestCarriesItsOwnCapacity() throws Exception {
            var own = observation("capacity", BoundaryStatus.FAR_EDGE_NOT_REACHED, null, List.of());
            when(capacity.latestPerWorkload(any())).thenReturn(Map.of("capacity", own));

            mockMvc.perform(get(SERVICE + "/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests[?(@.name == 'capacity')].capacity.compliantLevel")
                            .value("400 requests/sec"))
                    .andExpect(jsonPath("$.tests[?(@.name == 'capacity')].range.renderable")
                            .value(true))
                    // A sibling test (index 0, "average-load") with no observation of its own must
                    // not inherit this one's — a definite index path, since a filter-array
                    // projection reports an absent nested property as null rather than "no match".
                    .andExpect(jsonPath("$.tests[0].capacity").doesNotExist());
        }

        @Test
        @DisplayName("a test with no observation of its own has no capacity, not a borrowed one")
        void noObservationMeansNoCapacity() throws Exception {
            mockMvc.perform(get(SERVICE + "/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tests[0].capacity").doesNotExist())
                    .andExpect(jsonPath("$.tests[0].range.renderable").value(false));
        }
    }

    @Nested
    @DisplayName("evidence")
    class Evidence {

        private CapacityObservation observation(BoundaryStatus status, BoundaryEdge failing,
                List<ConstraintCandidate> candidates) {
            return ServiceApiControllerTest.observation("capacity", status, failing, candidates);
        }

        private void showing(CapacityObservation observation, HeadroomCalculator.Result headroom) {
            SequencedMap<String, List<CapacityObservation>> history = new LinkedHashMap<>();
            history.put("2.17.0", List.of(observation));
            when(capacity.historyByVersion(any())).thenReturn(history);
            when(capacity.latest(any())).thenReturn(Optional.of(observation));
            when(capacity.headroom(any(CapacityObservation.class), any())).thenReturn(headroom);
        }

        @Test
        @DisplayName("headroom is either a figure or a stated reason, never silence")
        void headroomIsNeverSilent() throws Exception {
            showing(observation(BoundaryStatus.UNSTABLE, null, List.of()),
                    new HeadroomCalculator().calculate(null, false, null, null));

            mockMvc.perform(get(SERVICE + "/evidence"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.capacity.headroom").doesNotExist())
                    .andExpect(jsonPath("$.capacity.headroomRefusal").isNotEmpty());
        }

        @Test
        @DisplayName("a constraint candidate is framed as correlation, never as a cause")
        void candidatesAreCorrelatedNotCausal() throws Exception {
            var candidate = new ConstraintCandidate("metric:pool.connections.utilization",
                    "Connection pool utilisation", "98%", EvidenceStrength.MEDIUM,
                    StageWindowBasis.DERIVED_FROM_PLAN,
                    "Observed at 98% while the workload held 450 requests/sec.");
            var failing = new BoundaryEdge(RequestsPerSecond.of(450), null, ErrorRate.ZERO,
                    List.of("threshold:latency.p95"), List.of());

            showing(observation(BoundaryStatus.ESTABLISHED, failing, List.of(candidate)),
                    new HeadroomCalculator().calculate(null, false, null, null));

            mockMvc.perform(get(SERVICE + "/evidence"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.capacity.constraintCandidates[0].describe")
                            .value(org.hamcrest.Matchers.containsString(
                                    "not that this resource produced the degradation")));
        }

        @Test
        @DisplayName("the conditions travel with the figure, in the domain's own words")
        void conditionsTravelWithTheFigure() throws Exception {
            showing(observation(BoundaryStatus.FAR_EDGE_NOT_REACHED, null, List.of()),
                    new HeadroomCalculator().calculate(null, false, null, null));

            mockMvc.perform(get(SERVICE + "/evidence"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.capacity.conditions",
                            org.hamcrest.Matchers.hasItem(
                                    org.hamcrest.Matchers.containsString("Dependencies"))));
        }
    }
}
