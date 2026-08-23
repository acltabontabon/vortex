package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.capacity.HeadroomCalculator;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.Repositories.CapacityObservationRepository;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A service's capacity evidence, read per test rather than only as one service-wide figure.
 *
 * <p>Two tests in the same service each establish their own capacity — a capacity-style test and a
 * breakpoint-style test both produce a {@code CapacityObservation}, and a page showing both must not
 * hand one test's reading to the other just because it happened to run more recently.
 */
class CapacityServiceTest {

    private static final ProjectId PROJECT = ProjectId.of("checkout");

    @Test
    @DisplayName("each workload keeps its own newest observation, not the service's newest overall")
    void latestPerWorkloadKeepsOneEntryPerTest() {
        Instant t0 = Fixtures.NOW.minusSeconds(300);
        Instant t1 = Fixtures.NOW.minusSeconds(200);
        Instant t2 = Fixtures.NOW.minusSeconds(100);

        var capacityCheckOld = observation("capacity-check", 100, t0);
        var breakpointCheck = observation("breakpoint-check", 250, t1);
        var capacityCheckNew = observation("capacity-check", 120, t2);

        var service = new CapacityService(
                repositoryOf(capacityCheckOld, breakpointCheck, capacityCheckNew),
                new HeadroomCalculator(), Clock.fixed(Fixtures.NOW));

        var byWorkload = service.latestPerWorkload(PROJECT);

        assertThat(byWorkload).hasSize(2);
        // capacity-check's own newest reading, never the older one and never breakpoint-check's.
        assertThat(byWorkload.get("capacity-check")).isEqualTo(capacityCheckNew);
        assertThat(byWorkload.get("breakpoint-check")).isEqualTo(breakpointCheck);
    }

    @Test
    @DisplayName("a service with no recorded observations yields an empty map, not a missing key")
    void noObservationsYieldsEmptyMap() {
        var service = new CapacityService(repositoryOf(), new HeadroomCalculator(),
                Clock.fixed(Fixtures.NOW));

        assertThat(service.latestPerWorkload(PROJECT)).isEmpty();
    }

    private static CapacityObservation observation(String workloadName, int compliantRate,
            Instant observedAt) {
        return new CapacityObservation(PROJECT, ExecutionId.generate(), "2.17.0",
                RequestsPerSecond.of(compliantRate), WorkloadModel.OPEN, "local",
                TestClassification.ISOLATED, DependencyMode.MOCKED, List.of("getOrder 100%"),
                workloadName, List.of("p95 < 500 ms"), Duration.ofMinutes(1),
                Fixtures.plan().fingerprint(), observedAt);
    }

    /** Mirrors the real JDBC repository's contract: newest observation first. */
    private static CapacityObservationRepository repositoryOf(CapacityObservation... observations) {
        List<CapacityObservation> sink = new ArrayList<>(List.of(observations));

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
