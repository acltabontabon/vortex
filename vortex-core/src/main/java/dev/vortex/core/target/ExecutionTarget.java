package dev.vortex.core.target;

/**
 * A sealed, purely declarative description of intent — what the user configured (an endpoint
 * address, or an image+port+resources, or a compose file+service+port).
 *
 * <p>Carries no runtime facts and, critically, no common {@code TargetUrl} — only {@link
 * ExternalEndpointTarget} has a real pre-run address, because only it has one in truth. See {@link
 * ResolvedTarget} for the runtime fact a target produces once prepared.
 */
public sealed interface ExecutionTarget
        permits ExternalEndpointTarget, DockerImageTarget, DockerComposeTarget {
    TargetOwnership ownership();

    String summary();
}
