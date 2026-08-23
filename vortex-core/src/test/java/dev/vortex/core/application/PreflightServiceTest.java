package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.fixtures.FakePerformanceEngine;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.TargetExecutor;
import dev.vortex.core.safety.ExecutionPolicy;
import dev.vortex.core.target.ContainerPort;
import dev.vortex.core.target.DockerImageTarget;
import dev.vortex.core.target.ExecutionTarget;
import dev.vortex.core.target.ExternalEndpointTargetExecutor;
import dev.vortex.core.target.ImageReference;
import dev.vortex.core.target.ResourceEnvelopeRequest;
import dev.vortex.core.target.TargetCapability;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link PreflightService#check}'s target check, specifically — the split between the
 * {@code targetProbe}-based HTTP check ({@link dev.vortex.core.target.ExternalEndpointTarget}, wired
 * before this class existed) and the {@link TargetExecutor#checkAvailability} path added for
 * non-endpoint targets (Docker/Compose).
 *
 * <p>The rest of {@code check()}'s steps (engine, operations, thresholds, secrets, request data,
 * script) are exercised by other tests closer to what they validate; this class stays narrowly about
 * the target step and the regression guarantee that adding the new path changes nothing observable
 * for the endpoint case that already existed.
 */
class PreflightServiceTest {

    private static final PreflightService.TargetProbe NEVER_PROBED = url -> {
        throw new AssertionError("targetProbe.probe(" + url + ") should not be called for this plan");
    };

    private PreflightService serviceWith(PreflightService.TargetProbe targetProbe,
            List<TargetExecutor> targetExecutors) {
        return new PreflightService(new FakePerformanceEngine(), ExecutionPolicy.withDefaults(),
                name -> false, targetProbe, targetExecutors);
    }

    // ---- non-endpoint target: TargetExecutor.checkAvailability is folded into the report --------

    @Test
    void dockerImageTargetPlanFoldsCheckAvailabilityIntoTheReport() {
        DockerImageTarget dockerTarget = new DockerImageTarget(new ImageReference("payment-service:1.4.2"),
                new ContainerPort(8080), ResourceEnvelopeRequest.none(), null);
        EffectiveTestPlan plan = dockerPlan(dockerTarget);

        List<PreflightCheck> configuredChecks = List.of(
                PreflightCheck.pass("Docker available", "Docker is reachable on this machine."),
                PreflightCheck.pass("Image available", "payment-service:1.4.2"));
        FakeTargetExecutor executor = new FakeTargetExecutor(dockerTarget, configuredChecks);

        PreflightService service = serviceWith(NEVER_PROBED, List.of(executor));
        PreflightReport report = service.check(plan);

        assertThat(report.checks()).containsAll(configuredChecks);
        assertThat(executor.checkAvailabilityCalls.get()).isEqualTo(1);
    }

    @Test
    void dockerImageTargetPlanWithNoRegisteredExecutorFailsLoudly() {
        DockerImageTarget dockerTarget = new DockerImageTarget(new ImageReference("payment-service:1.4.2"),
                new ContainerPort(8080), ResourceEnvelopeRequest.none(), null);
        EffectiveTestPlan plan = dockerPlan(dockerTarget);

        PreflightService service = serviceWith(NEVER_PROBED, List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.check(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no TargetExecutor registered");
    }

    // ---- external-endpoint target: completely unchanged regression guard -------------------------

    @Test
    void externalEndpointTargetPlanNeverInvokesTargetExecutorCheckAvailability() {
        // Fixtures.plan() is an ExternalEndpointTarget plan with a configured/effective TargetUrl —
        // the same plan shape PreflightService's HTTP-probe check has always run against.
        EffectiveTestPlan plan = Fixtures.plan();
        CountingTargetExecutor executor =
                new CountingTargetExecutor(new ExternalEndpointTargetExecutor());

        PreflightService service =
                serviceWith(url -> Optional.empty(), List.of(executor));
        PreflightReport report = service.check(plan);

        // Byte-for-byte identical to before this step: the HTTP probe ran and produced exactly the
        // one "Target reachable" check it always did, and the new TargetExecutor path was never
        // reached at all for this plan.
        PreflightCheck targetCheck = report.checks().stream()
                .filter(check -> check.name().equals("Target reachable"))
                .findFirst().orElseThrow();
        assertThat(targetCheck.status()).isEqualTo(PreflightCheck.Status.PASS);
        assertThat(executor.checkAvailabilityCalls.get()).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** {@code Fixtures.plan()} with its {@code ExternalEndpointTarget} replaced by the given
     *  non-endpoint target, and no configured/effective pre-run URL — exactly what {@code
     *  PlanResolver} produces for a Docker/Compose environment today. */
    private EffectiveTestPlan dockerPlan(ExecutionTarget target) {
        EffectiveTestPlan base = Fixtures.plan();
        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(), base.workloadDescription(),
                base.testType(), base.workloadModel(), base.peakLevel(), base.stages(),
                base.operations(), base.datasets(), base.workloadSource(), base.thresholds(),
                base.environmentName(), base.environmentType(), target, null, null, "",
                base.dependencyMode(), base.classification(), base.headers(), base.k6Options(),
                base.runner(), base.scriptSource(), base.safetyDecisions(), base.fingerprint(),
                base.validityPolicy(), base.workspacePath())
                .withComputedFingerprint();
    }

    /** A {@link TargetExecutor} whose {@link #checkAvailability} a test configures up front and whose
     *  {@link #prepare} is never expected to be called by anything this class exercises. */
    private static final class FakeTargetExecutor implements TargetExecutor {

        private final ExecutionTarget supported;
        private final List<PreflightCheck> checks;
        private final AtomicInteger checkAvailabilityCalls = new AtomicInteger();

        FakeTargetExecutor(ExecutionTarget supported, List<PreflightCheck> checks) {
            this.supported = supported;
            this.checks = checks;
        }

        @Override
        public boolean supports(ExecutionTarget target) {
            return target.equals(supported);
        }

        @Override
        public Set<TargetCapability> capabilities() {
            return Set.of();
        }

        @Override
        public dev.vortex.core.target.PreparedTarget prepare(
                dev.vortex.core.target.TargetPreparationRequest request) {
            throw new AssertionError("prepare() should not be called by a preflight check");
        }

        @Override
        public List<PreflightCheck> checkAvailability(ExecutionTarget target, String workspacePath) {
            checkAvailabilityCalls.incrementAndGet();
            return checks;
        }
    }

    /** Delegates every call to a real {@link TargetExecutor} — {@link ExternalEndpointTargetExecutor}
     *  is {@code final}, so this wraps rather than subclasses it — while counting how many times
     *  {@link #checkAvailability} specifically was called, for the regression guard above. */
    private static final class CountingTargetExecutor implements TargetExecutor {

        private final TargetExecutor delegate;
        private final AtomicInteger checkAvailabilityCalls = new AtomicInteger();

        CountingTargetExecutor(TargetExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(ExecutionTarget target) {
            return delegate.supports(target);
        }

        @Override
        public Set<TargetCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public dev.vortex.core.target.PreparedTarget prepare(
                dev.vortex.core.target.TargetPreparationRequest request) {
            return delegate.prepare(request);
        }

        @Override
        public List<PreflightCheck> checkAvailability(ExecutionTarget target, String workspacePath) {
            checkAvailabilityCalls.incrementAndGet();
            return delegate.checkAvailability(target, workspacePath);
        }
    }
}
