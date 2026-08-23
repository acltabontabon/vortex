package dev.vortex.app.service;

import dev.vortex.core.application.CapacityService;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.evidence.EvidenceContext;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.Repositories.ProjectConfigurationRepository;
import dev.vortex.core.project.ProjectConfiguration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the project-level facts a report needs, before the report is assembled.
 *
 * <h2>Why this is a separate object</h2>
 * The alternative was giving {@code RunEvidenceService} a repository and letting it look these up
 * mid-assembly. That would make report generation depend on the state of the database at the moment
 * it ran, which is exactly the property a report about a completed run should not have — and it would
 * turn a deterministic transformation into a service with side conditions.
 *
 * <p>So the lookup happens here, at the application boundary, and assembly stays a pure function of
 * what it is given.
 *
 * <h2>Why it never returns nothing</h2>
 * {@link EvidenceContext} requires a headroom <em>result</em>, which is either a figure or a stated
 * reason there isn't one. That is deliberate: headroom was calculated correctly for months and
 * displayed nowhere, because it was an optional parameter every caller left null. A context that
 * cannot be silently omitted is what stops a fifth caller repeating it.
 */
@Component
public class EvidenceContextFactory {

    private static final Logger log = LoggerFactory.getLogger(EvidenceContextFactory.class);

    private final ProjectConfigurationRepository configurations;
    private final CapacityService capacity;

    public EvidenceContextFactory(ProjectConfigurationRepository configurations,
            CapacityService capacity) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    /**
     * The context for reporting on one run.
     *
     * <p>Headroom is measured against the capacity this project has most recently established, and
     * the production traffic currently recorded for it. Both move independently of the run being
     * reported on, which is why headroom is computed at read time rather than stored on the
     * observation: a service whose production traffic doubled has less headroom than it did
     * yesterday, without any run having happened.
     */
    public EvidenceContext forExecution(TestExecution execution) {
        ProductionObservation production = production(execution).orElse(null);
        CapacityObservation latest = latestCapacity(execution).orElse(null);
        return new EvidenceContext(capacity.headroom(latest, production), production);
    }

    private Optional<ProductionObservation> production(TestExecution execution) {
        try {
            return configurations.findByProject(execution.projectId())
                    .flatMap(ProjectConfiguration::productionObservationIfPresent);
        } catch (RuntimeException e) {
            // A report is still worth producing without it; the headroom result will say why there
            // is no figure, which is more useful than failing the whole export.
            log.debug("Could not read production traffic for {}: {}",
                    execution.projectId().value(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CapacityObservation> latestCapacity(TestExecution execution) {
        try {
            return capacity.latest(execution.projectId());
        } catch (RuntimeException e) {
            log.debug("Could not read capacity history for {}: {}",
                    execution.projectId().value(), e.getMessage());
            return Optional.empty();
        }
    }
}
