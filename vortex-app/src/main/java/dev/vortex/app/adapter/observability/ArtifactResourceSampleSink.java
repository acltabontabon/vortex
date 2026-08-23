package dev.vortex.app.adapter.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.resource.ResourceSample;
import dev.vortex.core.resource.ResourceSampleSink;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams raw resource samples straight to an execution's own artifact as they arrive.
 *
 * <p>One JSON line per sample, append-only, flushed after every write — the collector this feeds
 * never buffers a run's full history in memory (see {@code ObservabilityTelemetryCollector.Readings}),
 * so an 8-hour soak costs the same heap as a 5-minute run. The closing line this writes on
 * {@link #close} is how a later reader tells a completely-recorded run from one whose telemetry
 * stopped early: its absence, or a reported write failure inside it, is what marks the artifact
 * partial rather than complete.
 */
final class ArtifactResourceSampleSink implements ResourceSampleSink {

    static final String ARTIFACT_NAME = "resource-telemetry.jsonl";

    private static final Logger log = LoggerFactory.getLogger(ArtifactResourceSampleSink.class);

    private final ArtifactStore.ArtifactWriter writer;
    private final ObjectMapper objectMapper;
    private final AtomicInteger sampleCount = new AtomicInteger();
    private volatile Instant firstAt;
    private volatile Instant lastAt;
    private volatile boolean writeFailed;
    private volatile boolean closed;

    ArtifactResourceSampleSink(ArtifactStore.ArtifactWriter writer, ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void accept(ResourceSample sample) {
        if (closed || writeFailed) {
            return;
        }
        try {
            writer.writeLine(objectMapper.writeValueAsString(lineFor(sample)));
            sampleCount.incrementAndGet();
            if (firstAt == null) {
                firstAt = sample.at();
            }
            lastAt = sample.at();
        } catch (RuntimeException | JsonProcessingException e) {
            // A sink that cannot persist a sample is not a reason to fail the performance test it is
            // trying to explain. Stop trying for the rest of this session — once persistence has
            // failed, retrying every five seconds only interleaves more gaps for no better odds — and
            // let close() record why the artifact ends where it does.
            writeFailed = true;
            log.warn("Resource telemetry could not be written for this run; the artifact will be "
                    + "marked partial: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close(String earlyStopReason) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            String reason = writeFailed ? "the artifact could not be written to" : earlyStopReason;
            writer.writeLine(objectMapper.writeValueAsString(terminator(reason)));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Resource telemetry's closing record could not be written: {}", e.getMessage());
        } finally {
            try {
                writer.close();
            } catch (RuntimeException e) {
                log.warn("The resource telemetry artifact could not be closed cleanly: {}",
                        e.getMessage());
            }
        }
    }

    private Map<String, Object> lineFor(ResourceSample sample) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", sample.at().toString());
        line.put("providerId", sample.providerId());
        line.put("signalId", sample.signalId());
        line.put("kind", sample.kind().name());
        line.put("scope", sample.scope().name());
        line.put("value", sample.value());
        line.put("unit", sample.unit().name());
        if (sample.stageIndex() != null) {
            line.put("stageIndex", sample.stageIndex());
        }
        return line;
    }

    private Map<String, Object> terminator(String reason) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("complete", reason == null);
        if (reason != null) {
            line.put("reason", reason);
        }
        line.put("samples", sampleCount.get());
        if (firstAt != null) {
            line.put("firstAt", firstAt.toString());
            line.put("lastAt", lastAt.toString());
        }
        return line;
    }
}
