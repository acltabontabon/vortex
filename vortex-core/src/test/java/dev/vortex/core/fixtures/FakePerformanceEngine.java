package dev.vortex.core.fixtures;

import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.shared.ExecutionId;
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

    @Override
    public EngineAvailability availability() {
        return EngineAvailability.ready("fake");
    }

    @Override
    public ValidationResult validate(EffectiveTestPlan plan) {
        return validation;
    }

    @Override
    public EngineOutcome execute(ExecutionId executionId, EffectiveTestPlan plan,
            Consumer<ExecutionProgress> progressSink, Cancellation cancellation) {
        return outcome.get();
    }

    @Override
    public ToolVersions toolVersions() {
        return versions;
    }
}
