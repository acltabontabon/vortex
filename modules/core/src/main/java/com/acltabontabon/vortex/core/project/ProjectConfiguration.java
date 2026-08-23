package com.acltabontabon.vortex.core.project;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.catalog.OperationBinding;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The portable performance definition for a project: everything needed to reproduce a test,
 * expressed as user intent.
 *
 * <p>This is the object that round-trips to {@code vortex.yaml}. It belongs in version control, next
 * to the service it describes, and it must be sufficient on its own — a test that can only be
 * reproduced by clicking through a particular installation of Vortex is not reproducible.
 *
 * <p>Note what is <em>not</em> here: allocated per-operation rates, k6 scenario keys, resolved runner
 * selection, resolved secrets, safety decisions. Those are the product of resolving this
 * configuration against an environment and a policy at execution time, and they live in
 * {@code com.acltabontabon.vortex.core.plan.EffectiveTestPlan}.
 *
 * <p>Also not here: the operations themselves. Those are discovered from an API description and live
 * in a {@code ServiceCatalog}, which is re-derived on every import. What is here is what a person
 * decided about them — {@link OperationBinding} — so re-importing a specification never discards
 * somebody's test data or their approval of a mutating operation.
 *
 * @param version               configuration schema version
 * @param serviceName           the system under test, as its owners call it
 * @param serviceDescription    what the service does
 * @param serviceVersion        the release of the service under test — a build number, tag or
 *                              commit. Optional, and overridden per run by the command line, which
 *                              is how a pipeline stamps the commit it is actually testing. Absent
 *                              stays absent: "unknown" is not a version
 * @param operationBindings     per-operation request data, expectations and review decisions
 * @param environments          available targets
 * @param workloads             the workloads this project knows how to apply
 * @param thresholds            project-wide objectives, inherited by every workload
 * @param productionObservation observed production traffic, when known
 * @param observationSource     which monitoring system can be asked for that traffic, when one is
 *                              configured. Separate from the observation itself: the source is how
 *                              to ask, the observation is what came back, and either can exist
 *                              without the other
 * @param localLab              the repository's own Compose file, when this service has dependencies
 *                              worth starting locally. Absent for a service that needs none — a
 *                              local lab is a convenience, not a requirement
 */
public record ProjectConfiguration(
        int version,
        String serviceName,
        String serviceDescription,
        String serviceVersion,
        List<OperationBinding> operationBindings,
        List<Environment> environments,
        List<Workload> workloads,
        ThresholdSet thresholds,
        ProductionObservation productionObservation,
        ObservationSource observationSource,
        LocalLabSettings localLab) {

    /**
     * The configuration schema version this build of Vortex writes.
     *
     * <p>The numbering restarts here. Two earlier vocabularies exist — one describing journeys and a
     * project-wide traffic mix, one calling a workload a {@code scenario} — and neither is migrated.
     * Both are detected on load and refused with the required edit spelled out, because a file whose
     * words disagree with the interface is worse than a file that will not load at all. See
     * {@code docs/adr/adr-030-workloads-not-scenarios.adoc}.
     */
    public static final int CURRENT_VERSION = 1;

    public ProjectConfiguration {
        operationBindings = operationBindings == null ? List.of() : List.copyOf(operationBindings);
        environments = environments == null ? List.of() : List.copyOf(environments);
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
        thresholds = thresholds == null ? ThresholdSet.empty() : thresholds;
        serviceName = serviceName == null ? "" : serviceName.trim();
        serviceDescription = serviceDescription == null ? "" : serviceDescription.trim();
        serviceVersion = serviceVersion == null ? "" : serviceVersion.trim();
        if (version <= 0) {
            version = CURRENT_VERSION;
        }
        long distinctWorkloads = workloads.stream().map(Workload::name).distinct().count();
        if (distinctWorkloads != workloads.size()) {
            throw new IllegalArgumentException("workload names must be unique");
        }
        long distinctEnvironments = environments.stream().map(Environment::name).distinct().count();
        if (distinctEnvironments != environments.size()) {
            throw new IllegalArgumentException("environment names must be unique");
        }
        long distinctBindings = operationBindings.stream()
                .map(OperationBinding::operationId).distinct().count();
        if (distinctBindings != operationBindings.size()) {
            throw new IllegalArgumentException("an operation may have at most one binding");
        }
    }

    public static ProjectConfiguration empty() {
        return new ProjectConfiguration(CURRENT_VERSION, "", "", "", List.of(), List.of(), List.of(),
                ThresholdSet.empty(), null, null, null);
    }

    public Optional<ProductionObservation> productionObservationIfPresent() {
        return Optional.ofNullable(productionObservation);
    }

    public Optional<ObservationSource> observationSourceIfPresent() {
        return Optional.ofNullable(observationSource);
    }

    public Optional<LocalLabSettings> localLabIfPresent() {
        return Optional.ofNullable(localLab);
    }

    public Optional<Workload> workloadByName(String name) {
        return workloads.stream()
                .filter(s -> s.name().equalsIgnoreCase(Objects.requireNonNullElse(name, "")))
                .findFirst();
    }

    public Optional<Environment> environment(EnvironmentId id) {
        return environments.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public Optional<Environment> environmentByName(String name) {
        return environments.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The recorded binding for an operation, if a person has made any decisions about it. */
    public Optional<OperationBinding> binding(OperationId operationId) {
        return operationBindings.stream()
                .filter(b -> b.operationId().equals(operationId)).findFirst();
    }

    /** The binding for an operation, or an empty one — so callers never branch on absence. */
    public OperationBinding bindingOrDefault(OperationId operationId) {
        return binding(operationId).orElseGet(() -> OperationBinding.of(operationId));
    }

    /** Every operation referenced by any workload, in first-appearance order. */
    public List<OperationId> referencedOperations() {
        List<OperationId> referenced = new java.util.ArrayList<>();
        for (Workload workload : workloads) {
            for (OperationId operationId : workload.operations().operationIds()) {
                if (!referenced.contains(operationId)) {
                    referenced.add(operationId);
                }
            }
        }
        return List.copyOf(referenced);
    }

    public ProjectConfiguration withBinding(OperationBinding binding) {
        List<OperationBinding> updated = new java.util.ArrayList<>(operationBindings.stream()
                .filter(b -> !b.operationId().equals(binding.operationId()))
                .toList());
        if (!binding.isEmpty()) {
            updated.add(binding);
        }
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                updated, environments,
                workloads, thresholds, productionObservation, observationSource, localLab);
    }

    public ProjectConfiguration withEnvironments(List<Environment> newEnvironments) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, newEnvironments, workloads, thresholds, productionObservation, observationSource, localLab);
    }

    /**
     * Removes one environment, matching by name.
     *
     * <p>Safe with respect to history by construction: {@code RunIdentity} stores the environment's
     * name as a plain string snapshot, not a live reference, and {@code RunEvidence} never holds an
     * {@code Environment}/{@code EnvironmentId} at all — deleting the configuration entry cannot
     * change what an already-recorded run reports.
     *
     * <p>Removing a name that is not present is not an error. The caller wanted it gone.
     */
    public ProjectConfiguration withoutEnvironment(String name) {
        String wanted = Objects.requireNonNullElse(name, "");
        return withEnvironments(environments.stream()
                .filter(existing -> !existing.name().equalsIgnoreCase(wanted))
                .toList());
    }

    public ProjectConfiguration withWorkloads(List<Workload> newWorkloads) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, environments, newWorkloads, thresholds, productionObservation, observationSource, localLab);
    }

    /** Adds or replaces one workload, matching by name. */
    public ProjectConfiguration withWorkload(Workload workload) {
        List<Workload> updated = new java.util.ArrayList<>(workloads.stream()
                .filter(existing -> !existing.name().equalsIgnoreCase(workload.name()))
                .toList());
        updated.add(workload);
        return withWorkloads(updated);
    }

    /**
     * Removes one workload, matching by name.
     *
     * <p>Safe with respect to history by construction, and worth saying why: a run stores the
     * resolved plan it executed, so deleting the definition a run was produced from cannot change
     * what that run reports. The reusable definition and the evidence are separate objects, and only
     * one of them is editable.
     *
     * <p>Removing a name that is not present is not an error. The caller wanted it gone.
     */
    public ProjectConfiguration withoutWorkload(String name) {
        String wanted = Objects.requireNonNullElse(name, "");
        return withWorkloads(workloads.stream()
                .filter(existing -> !existing.name().equalsIgnoreCase(wanted))
                .toList());
    }

    public ProjectConfiguration withThresholds(ThresholdSet newThresholds) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, environments, workloads, newThresholds, productionObservation, observationSource, localLab);
    }

    public ProjectConfiguration withProductionObservation(ProductionObservation observation) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, environments, workloads, thresholds, observation, observationSource,
                localLab);
    }

    /**
     * Records where production traffic can be fetched from.
     *
     * <p>Setting a source does not fetch anything, and fetching does not save anything. Both steps
     * stay explicit, because a calibration that rewrites a committed file on its own is a
     * calibration nobody reviewed.
     */
    public ProjectConfiguration withObservationSource(ObservationSource newSource) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, environments, workloads, thresholds, productionObservation,
                newSource, localLab);
    }

    /**
     * Records which Compose file describes this service's local dependencies.
     *
     * <p>Recording it starts nothing. Bringing dependencies up stays an explicit act, because a tool
     * that launches containers as a side effect of saving a setting is a tool nobody can predict.
     */
    public ProjectConfiguration withLocalLab(LocalLabSettings newLocalLab) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, serviceVersion,
                operationBindings, environments, workloads, thresholds, productionObservation,
                observationSource, newLocalLab);
    }

    public ProjectConfiguration withService(String newName, String newDescription) {
        return new ProjectConfiguration(version, newName, newDescription, serviceVersion,
                operationBindings, environments, workloads, thresholds, productionObservation, observationSource, localLab);
    }

    /**
     * Records which release of the service is under test.
     *
     * <p>Optional, and absent stays absent: "unknown" is not a version, and a run that cannot name
     * its build should say so rather than claim a placeholder.
     */
    public ProjectConfiguration withServiceVersion(String newServiceVersion) {
        return new ProjectConfiguration(version, serviceName, serviceDescription, newServiceVersion,
                operationBindings, environments, workloads, thresholds, productionObservation, observationSource, localLab);
    }

    public Optional<String> serviceVersionIfPresent() {
        return serviceVersion.isBlank() ? Optional.empty() : Optional.of(serviceVersion);
    }

    /**
     * Whether this configuration can produce a runnable test.
     *
     * <p>Used to drive the project readiness indicators, which exist to tell a newcomer at a glance
     * what is still missing — not to keep score.
     */
    public ProjectReadiness readiness(boolean catalogImported, boolean hasExecuted) {
        return new ProjectReadiness(
                catalogImported,
                !environments.isEmpty(),
                !workloads.isEmpty(),
                workloads.stream().anyMatch(s -> s.type() == TestType.AVERAGE_LOAD),
                !thresholds.isEmpty(),
                productionObservation != null,
                hasExecuted);
    }
}
