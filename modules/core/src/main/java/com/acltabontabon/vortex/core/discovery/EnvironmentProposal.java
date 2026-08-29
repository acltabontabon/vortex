package com.acltabontabon.vortex.core.discovery;

import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import java.util.Objects;

/**
 * A candidate {@code Environment}, proposed but not yet a real one — it carries no {@code
 * EnvironmentId} and no headers, because those are decisions a person makes on review, not
 * something a file on disk can state.
 *
 * @param name           the environment name Discovery suggests, e.g. the Compose service name
 * @param type           the environment class Discovery suggests — always {@code LOCAL_ISOLATED}
 *                        in v1, since Discovery only ever looks at a project checked out locally
 * @param target         what Discovery found: a Compose service, an image, or an endpoint
 * @param dependencyMode whether Discovery also found dependencies this target would use for real
 */
public record EnvironmentProposal(String name, EnvironmentType type, ExecutionTarget target,
        DependencyMode dependencyMode) {

    public EnvironmentProposal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(dependencyMode, "dependencyMode");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an environment proposal needs a name");
        }
        name = name.trim();
    }
}
