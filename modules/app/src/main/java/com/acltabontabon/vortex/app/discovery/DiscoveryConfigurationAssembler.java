package com.acltabontabon.vortex.app.discovery;

import com.acltabontabon.vortex.core.discovery.EnvironmentProposal;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns an approved subset of a {@code DiscoveryProposal} into {@code ProjectConfiguration}
 * mutations, using the exact construction {@code ConfigurationApiController} already uses for a
 * manually entered environment or Local Lab setting.
 *
 * <p>Apply is not a second write path — it is the same one, pre-filled. Shared by both the
 * onboarding ({@code ServicesApiController.create}) and existing-service ({@code
 * DiscoveryApiController.apply}) flows so this construction exists once.
 */
public final class DiscoveryConfigurationAssembler {

    /** Adds or replaces (by name) the environment a Discovery proposal describes. */
    public ProjectConfiguration withEnvironment(ProjectConfiguration configuration,
            EnvironmentProposal proposal) {
        Environment environment = new Environment(EnvironmentId.generate(), proposal.name(),
                proposal.type(), proposal.target(), EnvironmentCapabilities.localIsolated(),
                proposal.dependencyMode(), Map.of());
        List<Environment> updated = new ArrayList<>(configuration.environments().stream()
                .filter(existing -> !existing.name().equalsIgnoreCase(proposal.name()))
                .toList());
        updated.add(environment);
        return configuration.withEnvironments(updated);
    }

    /** Records the project's own Compose file as the Local Lab file. */
    public ProjectConfiguration withLocalLab(ProjectConfiguration configuration,
            LocalLabSettings localLab) {
        return configuration.withLocalLab(localLab);
    }
}
