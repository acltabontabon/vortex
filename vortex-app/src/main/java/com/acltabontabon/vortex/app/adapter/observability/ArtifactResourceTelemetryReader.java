package com.acltabontabon.vortex.app.adapter.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TelemetryCompleteness;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceSample;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceTelemetryReader;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.persistence.JsonDocuments;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code resource-telemetry.jsonl} back for a completed (or crashed, or cancelled) execution.
 *
 * <p>The read-side counterpart to {@link ArtifactResourceSampleSink}: this is the one place that
 * knows the closing line's shape is what turns "the file exists" into "the file is complete" — a
 * missing or dishonest terminator, or a line this cannot parse, degrades the result rather than
 * failing the read outright, on the same "resource telemetry never fails what it is trying to
 * explain" principle the write side follows. Uses {@link JsonDocuments#mapper()}, the same shared
 * mapper the write side does, rather than a Spring-managed {@code ObjectMapper} bean — this
 * application never registers one.
 */
public final class ArtifactResourceTelemetryReader implements ResourceTelemetryReader {

    private static final Logger log = LoggerFactory.getLogger(ArtifactResourceTelemetryReader.class);

    private final ArtifactStore artifacts;
    private final ObjectMapper objectMapper;

    public ArtifactResourceTelemetryReader(ArtifactStore artifacts) {
        this.artifacts = artifacts;
        this.objectMapper = JsonDocuments.mapper();
    }

    @Override
    public Result read(ExecutionId executionId) {
        Optional<InputStream> stream =
                artifacts.open(executionId, ArtifactResourceSampleSink.ARTIFACT_NAME);
        if (stream.isEmpty()) {
            return new Result(TelemetryCompleteness.unavailable(), List.of());
        }

        List<ResourceSample> samples = new ArrayList<>();
        JsonNode terminator = null;
        Instant firstAt = null;
        Instant lastAt = null;

        try (InputStream in = stream.get();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException e) {
                    // A truncated or malformed trailing line is the expected shape of a process
                    // killed mid-write, not corruption — skip it rather than failing the whole read.
                    log.debug("Skipped an unreadable resource telemetry line for execution {}: {}",
                            executionId, e.getMessage());
                    continue;
                }
                if (node.has("complete")) {
                    terminator = node;
                    continue;
                }
                ResourceSample sample = sampleFrom(node);
                if (sample == null) {
                    continue;
                }
                samples.add(sample);
                if (firstAt == null) {
                    firstAt = sample.at();
                }
                lastAt = sample.at();
            }
        } catch (IOException e) {
            log.warn("Resource telemetry for execution {} could not be read: {}", executionId,
                    e.getMessage());
            return new Result(TelemetryCompleteness.unavailable(), List.of());
        }

        return new Result(completenessOf(terminator, firstAt, lastAt), samples);
    }

    private TelemetryCompleteness completenessOf(JsonNode terminator, Instant firstAt, Instant lastAt) {
        if (terminator == null) {
            // No closing record: the process ended before one could be written, whether by a crash
            // or by something else stopping this session without going through close(). Either way
            // this artifact describes less than the whole run, and must say so rather than reading
            // as though the series simply ended where the run did.
            return new TelemetryCompleteness(TelemetryCompleteness.Status.PARTIAL,
                    windowOf(firstAt, lastAt),
                    "the run ended before telemetry could be closed cleanly");
        }
        boolean complete = terminator.path("complete").asBoolean(false);
        if (complete) {
            return new TelemetryCompleteness(TelemetryCompleteness.Status.COMPLETE,
                    windowOf(firstAt, lastAt), "");
        }
        return new TelemetryCompleteness(TelemetryCompleteness.Status.PARTIAL,
                windowOf(firstAt, lastAt), terminator.path("reason").asText(""));
    }

    private TimeWindow windowOf(Instant firstAt, Instant lastAt) {
        return firstAt == null || lastAt == null ? null : new TimeWindow(firstAt, lastAt);
    }

    private ResourceSample sampleFrom(JsonNode node) {
        try {
            Instant at = Instant.parse(node.path("at").asText());
            String providerId = node.path("providerId").asText();
            String signalId = node.path("signalId").asText();
            ResourceKind kind = ResourceKind.valueOf(node.path("kind").asText());
            ResourceScope scope = ResourceScope.valueOf(node.path("scope").asText());
            double value = node.path("value").asDouble();
            MetricUnit unit = MetricUnit.valueOf(node.path("unit").asText());
            Integer stageIndex = node.has("stageIndex") ? node.path("stageIndex").asInt() : null;
            return new ResourceSample(at, providerId, signalId, kind, scope, value, unit, stageIndex);
        } catch (RuntimeException e) {
            // A malformed sample line is the same "skip, do not fail the read" story as a malformed
            // terminator.
            return null;
        }
    }
}
