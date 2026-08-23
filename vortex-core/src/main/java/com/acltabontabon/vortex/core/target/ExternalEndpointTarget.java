package com.acltabontabon.vortex.core.target;

import com.acltabontabon.vortex.core.environment.TargetUrl;
import java.util.Objects;

/** The only variant with a genuine pre-run address. */
public record ExternalEndpointTarget(TargetUrl endpoint) implements ExecutionTarget {

    public ExternalEndpointTarget {
        Objects.requireNonNull(endpoint, "endpoint");
    }

    public TargetOwnership ownership() {
        return TargetOwnership.EXTERNAL;
    }

    public String summary() {
        return endpoint.value();
    }
}
