package dev.vortex.core.plan;

import java.util.Objects;
import java.util.Optional;

/**
 * The versions of everything that took part in an execution.
 *
 * <p>Recorded because a performance result is only reproducible if you know what produced it. A k6
 * upgrade, a JDK upgrade or a different container image can all move numbers without anything in
 * the test configuration changing.
 *
 * <p>Deliberately <em>not</em> part of the plan fingerprint: these describe how the test was
 * carried out, not what was asked for.
 *
 * @param vortexVersion  the Vortex build that ran the test
 * @param engineVersion  the load generator version, e.g. {@code k6 v1.3.0}
 * @param runtimeVersion the Java runtime Vortex itself was running on
 * @param dockerImage    the container image and digest, when the Docker runner was used
 */
public record ToolVersions(
        String vortexVersion,
        String engineVersion,
        String runtimeVersion,
        String dockerImage) {

    public ToolVersions {
        vortexVersion = Objects.requireNonNullElse(vortexVersion, "unknown");
        engineVersion = Objects.requireNonNullElse(engineVersion, "unknown");
        runtimeVersion = Objects.requireNonNullElse(runtimeVersion, "unknown");
        dockerImage = Objects.requireNonNullElse(dockerImage, "");
    }

    public static ToolVersions unknown() {
        return new ToolVersions("unknown", "unknown", "unknown", "");
    }

    public Optional<String> dockerImageIfPresent() {
        return dockerImage.isBlank() ? Optional.empty() : Optional.of(dockerImage);
    }
}
