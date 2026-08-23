package com.acltabontabon.vortex.app.adapter.observability;

import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.resource.ResourceSampleSink;
import com.acltabontabon.vortex.core.resource.ResourceSampleSinkFactory;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.persistence.JsonDocuments;

/**
 * Opens each execution's {@code resource-telemetry.jsonl} artifact for the collector to stream into.
 *
 * <p>The one place {@code ArtifactStore}, execution ids and JSON serialization meet for resource
 * telemetry — {@code ObservabilityTelemetryCollector} itself only ever sees a
 * {@link ResourceSampleSink}, never this. Uses {@link JsonDocuments#mapper()}, the same shared,
 * pre-configured mapper every other stored-document reader/writer in this application already uses,
 * rather than a Spring-managed {@code ObjectMapper} bean — this application never registers one.
 */
public final class ArtifactResourceSampleSinkFactory implements ResourceSampleSinkFactory {

    private final ArtifactStore artifacts;

    public ArtifactResourceSampleSinkFactory(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ResourceSampleSink open(ExecutionId executionId) {
        ArtifactStore.ArtifactWriter writer =
                artifacts.openForAppend(executionId, ArtifactResourceSampleSink.ARTIFACT_NAME);
        return new ArtifactResourceSampleSink(writer, JsonDocuments.mapper());
    }
}
