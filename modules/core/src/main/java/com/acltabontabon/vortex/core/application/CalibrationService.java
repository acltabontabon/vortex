package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.capacity.ObservationResolution;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.ProductionObservationSource;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservationRequest;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservedOperation;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieval;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Fetches observed production traffic from whichever monitoring system a project is pointed at.
 *
 * <p>Deliberately thin. It decides <em>which</em> source answers, over what window and at what
 * resolution, and translates the service catalog into terms an adapter can ask about. It does no
 * arithmetic on the result: turning an observation into proposed workloads is
 * {@link com.acltabontabon.vortex.core.calibration.CalibrationPolicy}'s job, and it does that identically whether
 * the observation was fetched or typed.
 *
 * <p>It also writes nothing. Retrieval and adoption are separate steps because a calibration that
 * quietly rewrote a committed {@code vortex.yaml} would be a change nobody reviewed, and the whole
 * point of the file being in version control is that somebody can.
 */
public final class CalibrationService {

    private final List<ProductionObservationSource> sources;
    private final Clock clock;

    public CalibrationService(List<ProductionObservationSource> sources, Clock clock) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Asks the configured source what this service receives in production.
     *
     * @param windowOverride a window to use instead of the configured one, or null
     */
    public Retrieval fetch(ProjectConfiguration configuration, ServiceCatalog catalog,
            Duration windowOverride) {

        Objects.requireNonNull(configuration, "configuration");

        ObservationSource source = configuration.observationSourceIfPresent().orElse(null);
        if (source == null) {
            return new NotRetrieved(
                    "Cannot fetch production traffic",
                    "no observation source is configured for this service.",
                    "Add an 'observation:' section to vortex.yaml naming your Prometheus or "
                            + "Dynatrace endpoint. See docs/04-reference/configuration.adoc.");
        }

        ProductionObservationSource adapter = sources.stream()
                .filter(candidate -> candidate.supports(source))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return new NotRetrieved(
                    "Cannot fetch production traffic",
                    "no adapter in this build answers for " + source.kind().label() + ".",
                    "Vortex supports Prometheus and Dynatrace. Set observation.source to one of "
                            + "those.");
        }

        Duration window = windowOverride != null ? windowOverride : source.window();
        if (window.isZero() || window.isNegative()) {
            return new NotRetrieved(
                    "Cannot fetch production traffic",
                    "an observation window of " + window + " covers no traffic.",
                    "Give a period the service was actually receiving requests over, e.g. 30d.");
        }

        Instant now = clock.now();
        TimeWindow absolute = new TimeWindow(now.minus(window), now);
        Duration resolution = ObservationResolution.forWindow(window);

        List<ObservedOperation> operations = catalog == null ? List.of() : catalog.operations()
                .stream()
                .map(CalibrationService::describe)
                .toList();

        if (operations.isEmpty()) {
            // Without a catalog the rates could still be fetched, but the composition could not be
            // attributed to anything — and a volume with no composition is the workload Vortex
            // refuses to guess at. Better to say so now than to return half an observation.
            return new NotRetrieved(
                    "Cannot fetch production traffic",
                    "no operations have been imported for this service, so observed traffic could "
                            + "not be attributed to anything.",
                    "Import an API description first, then calibrate.");
        }

        return adapter.retrieve(new ObservationRequest(source, absolute, resolution, operations));
    }

    /**
     * Tests that a configured source can actually be reached and answers about this service.
     *
     * <p>Takes the source directly rather than reading it from the project, so the interface can
     * test a form somebody is still filling in — testing only what has already been saved would make
     * the button useless for the case it exists to serve.
     */
    public Retrieval verify(ObservationSource source, Duration windowOverride) {
        if (source == null) {
            return new NotRetrieved(
                    "Cannot test a source",
                    "no observation source was given.",
                    "Choose a system and give its endpoint first.");
        }

        ProductionObservationSource adapter = sources.stream()
                .filter(candidate -> candidate.supports(source))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return new NotRetrieved(
                    "Cannot test a source",
                    "no adapter in this build answers for " + source.kind().label() + ".",
                    "Vortex supports Prometheus and Dynatrace.");
        }

        Duration window = windowOverride != null ? windowOverride : source.window();
        Instant now = clock.now();
        return adapter.verify(source, new TimeWindow(now.minus(window), now),
                ObservationResolution.forWindow(window));
    }

    private static ObservedOperation describe(Operation operation) {
        return new ObservedOperation(operation.id(), operation.method().name(), operation.path());
    }
}
