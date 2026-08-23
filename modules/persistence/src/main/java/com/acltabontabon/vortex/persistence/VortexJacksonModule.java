package com.acltabontabon.vortex.persistence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.acltabontabon.vortex.core.data.BodyFieldPath;
import com.acltabontabon.vortex.core.data.DatasetValue;
import com.acltabontabon.vortex.core.data.EnvironmentValue;
import com.acltabontabon.vortex.core.data.FixedValue;
import com.acltabontabon.vortex.core.data.GeneratedValue;
import com.acltabontabon.vortex.core.data.RequestValue;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.LoadShape;
import java.io.IOException;

/**
 * Teaches Jackson how to store the domain model, without the domain model knowing about Jackson.
 *
 * <p>{@code vortex-core} has no serialisation annotations and no Jackson dependency — Maven enforces
 * it. Everything Jackson needs to know therefore lives here, in the module that actually does the
 * storing, which also means the persisted shape can change without touching the domain.
 *
 * <p>Two things need explicit help:
 *
 * <ul>
 *   <li><strong>Sealed hierarchies.</strong> A {@code Threshold} is either a latency objective or an
 *       error-rate one, and a stored document has to say which. Type information is attached through
 *       mix-ins rather than annotations on the types themselves.</li>
 *   <li><strong>Typed map keys.</strong> Latency percentiles are keyed by {@code Percentile} and
 *       per-operation metrics by {@code OperationId}. Both serialise through their
 *       {@code toString()}; reading them back needs a key deserialiser.</li>
 * </ul>
 *
 * <p>{@code LoadLevel} needs the same treatment as the sealed threshold and workload hierarchies,
 * and for a sharper reason: a stored {@code 50} that has forgotten whether it counted requests per
 * second or virtual users is not a recoverable number. The discriminator makes that impossible.
 */
public final class VortexJacksonModule extends SimpleModule {

    public VortexJacksonModule() {
        super("vortex");

        setMixInAnnotation(Threshold.class, ThresholdMixin.class);
        setMixInAnnotation(LoadShape.class, WorkloadMixin.class);
        setMixInAnnotation(LoadLevel.class, LoadLevelMixin.class);
        setMixInAnnotation(RequestValue.class, RequestValueMixin.class);
        setMixInAnnotation(ExecutionTarget.class, ExecutionTargetMixin.class);

        addKeyDeserializer(Percentile.class, new PercentileKeyDeserializer());
        addKeyDeserializer(OperationId.class, new OperationIdKeyDeserializer());
        addKeyDeserializer(BodyFieldPath.class, new BodyFieldPathKeyDeserializer());

        addKeySerializer(Percentile.class, new PercentileKeySerializer());
        addKeySerializer(BodyFieldPath.class, new BodyFieldPathKeySerializer());
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = LatencyThreshold.class, name = "latency"),
            @JsonSubTypes.Type(value = ErrorRateThreshold.class, name = "errorRate")
    })
    private abstract static class ThresholdMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "shape")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ConstantArrivalRateShape.class, name = "constantArrivalRate"),
            @JsonSubTypes.Type(value = RampingArrivalRateShape.class, name = "rampingArrivalRate"),
            @JsonSubTypes.Type(value = ConstantConcurrencyShape.class, name = "constantConcurrency"),
            @JsonSubTypes.Type(value = RampingConcurrencyShape.class, name = "rampingConcurrency")
    })
    private abstract static class WorkloadMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "unit")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RequestsPerSecond.class, name = "requestsPerSecond"),
            @JsonSubTypes.Type(value = Concurrency.class, name = "concurrency")
    })
    private abstract static class LoadLevelMixin {
    }

    /**
     * Where a request value came from, kept explicit in the stored plan.
     *
     * <p>The same reason {@code LoadLevel} carries its unit. A stored {@code "CREDIT_CARD"} that has
     * forgotten whether somebody typed it or a dataset supplied it cannot be read back into the plan
     * that produced a run — and a report six months from now has to be able to say which.
     *
     * <p>An {@code EnvironmentValue} stores its reference and never its resolved value, which is a
     * property of the record rather than of this mapping: the value only ever exists in the engine
     * subprocess's environment, so there is nothing here to leak.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "source")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixedValue.class, name = "fixed"),
            @JsonSubTypes.Type(value = GeneratedValue.class, name = "generated"),
            @JsonSubTypes.Type(value = DatasetValue.class, name = "dataset"),
            @JsonSubTypes.Type(value = EnvironmentValue.class, name = "environment")
    })
    private abstract static class RequestValueMixin {
    }

    /**
     * A declared target is either an external endpoint, a Docker image, or a Compose attachment —
     * {@code EffectiveTestPlan.executionTarget} needs the discriminator the same way {@code Threshold}
     * does. Only {@code ExternalEndpointTarget} is ever actually constructed today; the other two
     * variants are wired in now so a stored plan never needs a schema change to start using them.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ExternalEndpointTarget.class, name = "externalEndpoint"),
            @JsonSubTypes.Type(value = DockerImageTarget.class, name = "dockerImage"),
            @JsonSubTypes.Type(value = DockerComposeTarget.class, name = "dockerCompose")
    })
    private abstract static class ExecutionTargetMixin {
    }

    /** Writes a body field path as its dotted form, so a stored plan reads as somebody wrote it. */
    private static final class BodyFieldPathKeySerializer
            extends com.fasterxml.jackson.databind.JsonSerializer<BodyFieldPath> {

        @Override
        public void serialize(BodyFieldPath value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeFieldName(value.asText());
        }
    }

    private static final class BodyFieldPathKeyDeserializer extends KeyDeserializer {

        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            return BodyFieldPath.parse(key);
        }
    }

    /** Writes a percentile as its canonical label, so stored documents read as {@code "p95"}. */
    private static final class PercentileKeySerializer
            extends com.fasterxml.jackson.databind.JsonSerializer<Percentile> {

        @Override
        public void serialize(Percentile value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeFieldName(value.label());
        }
    }

    private static final class PercentileKeyDeserializer extends KeyDeserializer {

        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            String text = key.startsWith("p") || key.startsWith("P") ? key.substring(1) : key;
            return Percentile.of(Double.parseDouble(text));
        }
    }

    private static final class OperationIdKeyDeserializer extends KeyDeserializer {

        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            return OperationId.of(key);
        }
    }
}
