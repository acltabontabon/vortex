package com.acltabontabon.vortex.core.target;

/**
 * What a {@link com.acltabontabon.vortex.core.port.TargetExecutor} can actually do for the target it supports.
 *
 * <p>Read by callers that want to know what's possible before assuming it — whether preflight can
 * offer a non-mutating availability check, whether the live run view has resource readings to show,
 * whether a port was picked automatically rather than configured. An executor with none of these is
 * still a valid executor: {@link ExternalEndpointTarget}'s reports {@link
 * java.util.Set#of()} because there is nothing about an externally managed endpoint Vortex controls.
 */
public enum TargetCapability {
    MANAGED_LIFECYCLE,
    RESOURCE_ENFORCEMENT,
    RESOURCE_OBSERVATION,
    AUTOMATIC_PORT_MAPPING,
    READINESS_CHECK
}
