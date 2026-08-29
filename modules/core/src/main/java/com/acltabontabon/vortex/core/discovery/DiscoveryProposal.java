package com.acltabontabon.vortex.core.discovery;

import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.project.OpenApiSource;
import java.util.List;
import java.util.Optional;

/**
 * What Project Discovery found in a project, and what it proposes doing with it.
 *
 * <p>Nothing here is applied by producing it. A proposal is inert until a person selects what to
 * keep and an explicit apply step writes it into the project's own configuration — see
 * {@code docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc}.
 *
 * @param proposedServiceName        a service name Discovery suggests, blank when none was found
 * @param proposedServiceDescription a description Discovery suggests, blank when none was found
 * @param proposedOpenApiSource      an OpenAPI description Discovery could parse, absent otherwise
 * @param proposedEnvironment        an execution target Discovery could resolve unambiguously,
 *                                   absent when none or more than one candidate was found
 * @param proposedLocalLab           the project's own Compose file, when exactly one file accounts
 *                                   for every dependency Discovery found
 * @param findings                   every piece of evidence Discovery gathered, whether or not it
 *                                   fed a proposed field above
 * @param conflicts                  fields where the project's already-saved configuration
 *                                   disagrees with what was discovered
 * @param partialFailures            files Discovery could not finish reading, in plain language —
 *                                   a scan with these is still usable, never all-or-nothing
 */
public record DiscoveryProposal(
        String proposedServiceName,
        String proposedServiceDescription,
        OpenApiSource proposedOpenApiSource,
        EnvironmentProposal proposedEnvironment,
        LocalLabSettings proposedLocalLab,
        List<Finding> findings,
        List<DiscoveryConflict> conflicts,
        List<String> partialFailures) {

    public DiscoveryProposal {
        proposedServiceName = proposedServiceName == null ? "" : proposedServiceName.trim();
        proposedServiceDescription =
                proposedServiceDescription == null ? "" : proposedServiceDescription.trim();
        findings = findings == null ? List.of() : List.copyOf(findings);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        partialFailures = partialFailures == null ? List.of() : List.copyOf(partialFailures);
    }

    public Optional<OpenApiSource> proposedOpenApiSourceIfPresent() {
        return Optional.ofNullable(proposedOpenApiSource);
    }

    public Optional<EnvironmentProposal> proposedEnvironmentIfPresent() {
        return Optional.ofNullable(proposedEnvironment);
    }

    public Optional<LocalLabSettings> proposedLocalLabIfPresent() {
        return Optional.ofNullable(proposedLocalLab);
    }

    /** Whether this scan produced anything a person could actually apply. */
    public boolean hasActionableProposal() {
        return proposedOpenApiSource != null || proposedEnvironment != null || proposedLocalLab != null;
    }
}
