package com.acltabontabon.vortex.core.target;

import com.acltabontabon.vortex.core.port.TargetExecutor;
import java.util.Set;

/**
 * The always-registered {@link TargetExecutor} for {@link ExternalEndpointTarget} — an externally
 * managed service Vortex only ever addresses, never creates or tears down.
 *
 * <p>Unlike every other executor this plan introduces, this one does no I/O and needs no adapter: an
 * external endpoint's "preparation" is simply reporting the address the user already configured, so
 * this lives in {@code vortex-core} rather than behind an adapter module. It is the executor every
 * run used before target types existed, and it is what the characterization tests in
 * {@code ExecutionServiceTest} prove is unchanged by the wiring around it.
 */
public final class ExternalEndpointTargetExecutor implements TargetExecutor {

    @Override
    public boolean supports(ExecutionTarget target) {
        return target instanceof ExternalEndpointTarget;
    }

    @Override
    public Set<TargetCapability> capabilities() {
        return Set.of();
    }

    @Override
    public PreparedTarget prepare(TargetPreparationRequest request) {
        ExternalEndpointTarget endpoint = (ExternalEndpointTarget) request.target();
        return PreparedTarget.external(endpoint.endpoint());
    }
}
