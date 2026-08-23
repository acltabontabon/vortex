package dev.vortex.core.fixtures;

import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link PerformanceEngine} whose behaviour a test configures up front, published from the test jar
 * alongside {@link InMemoryExecutions} so tests of the execution lifecycle share one fake rather than
 * each growing a private stub that implements the port slightly differently.
 */
public final class FakePerformanceEngine implements PerformanceEngine {

    private ValidationResult validation = ValidationResult.ok();
    private Supplier<EngineOutcome> outcome = () -> {
        throw new IllegalStateException(
                "FakePerformanceEngine.execute() was called without a configured outcome");
    };
    private ToolVersions versions = ToolVersions.unknown();
    private Optional<TargetRewrite> rewrite = Optional.empty();

    /** The plan most recently handed to {@link #execute}, so a test can assert on what
     *  {@code ExecutionService} actually built and passed down — separately from
     *  {@code execution.plan()}, which must never be this value. */
    private EffectiveTestPlan lastExecutedPlan;

    public FakePerformanceEngine validating(ValidationResult result) {
        this.validation = result;
        return this;
    }

    public FakePerformanceEngine returning(EngineOutcome result) {
        this.outcome = () -> result;
        return this;
    }

    public FakePerformanceEngine throwing(RuntimeException failure) {
        this.outcome = () -> {
            throw failure;
        };
        return this;
    }

    /** Makes this engine report a rewrite requirement, the way {@code K6PerformanceEngine} does when
     *  it runs k6 inside a container against a loopback target — for tests proving {@code
     *  ExecutionService} composes it with target resolution rather than choosing one or the other. */
    public FakePerformanceEngine rewriting(TargetRewrite hint) {
        this.rewrite = Optional.of(hint);
        return this;
    }

    @Override
    public EngineAvailability availability() {
        return EngineAvailability.ready("fake");
    }

    @Override
    public ValidationResult validate(EffectiveTestPlan plan) {
        return validation;
    }

    /** The load generator resources most recently handed to {@link #execute}. */
    private ResourceEnvelopeRequest lastExecutedLoadGeneratorResources;

    @Override
    public EngineOutcome execute(ExecutionId executionId, EffectiveTestPlan plan,
            ResourceEnvelopeRequest loadGeneratorResources, Consumer<ExecutionProgress> progressSink,
            Cancellation cancellation) {
        lastExecutedPlan = plan;
        lastExecutedLoadGeneratorResources = loadGeneratorResources;
        return outcome.get();
    }

    public ResourceEnvelopeRequest lastExecutedLoadGeneratorResources() {
        return lastExecutedLoadGeneratorResources;
    }

    @Override
    public ToolVersions toolVersions() {
        return versions;
    }

    @Override
    public Optional<TargetRewrite> targetRewriteFor(EffectiveTestPlan plan) {
        return rewrite;
    }

    public EffectiveTestPlan lastExecutedPlan() {
        return lastExecutedPlan;
    }
}
