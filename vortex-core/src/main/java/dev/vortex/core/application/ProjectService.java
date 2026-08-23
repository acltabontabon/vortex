package dev.vortex.core.application;

import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.ConfigurationStore;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.port.Repositories.ProjectConfigurationRepository;
import dev.vortex.core.port.Repositories.ProjectRepository;
import dev.vortex.core.port.Repositories.ServiceCatalogRepository;
import dev.vortex.core.project.Project;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.project.ProjectReadiness;
import dev.vortex.core.shared.ProjectId;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creating, reading and updating projects, and keeping the portable configuration in step with the
 * local index.
 *
 * <p>The rule this service enforces is that {@code vortex.yaml} is the source of truth for test
 * intent while the database is a local index of it. Configuration changes are written to the file
 * first and mirrored into the database, never the other way round — otherwise a project could
 * accumulate state that exists only inside one installation and cannot be committed, shared, or run
 * from a pipeline.
 */
public final class ProjectService {

    private final ProjectRepository projects;
    private final ProjectConfigurationRepository configurations;
    private final ServiceCatalogRepository catalogs;
    private final ExecutionRepository executions;
    private final ConfigurationStore configurationStore;
    private final ArtifactStore artifacts;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, ProjectConfigurationRepository configurations,
            ServiceCatalogRepository catalogs, ExecutionRepository executions,
            ConfigurationStore configurationStore, ArtifactStore artifacts, Clock clock) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.configurationStore = Objects.requireNonNull(configurationStore, "configurationStore");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Project create(String name, String description, String workspacePath) {
        projects.findByName(name).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "A project named '" + name + "' already exists. Choose a different name, or open "
                            + "the existing project.");
        });

        Project project = projects.save(Project.create(name, description, workspacePath, clock.now()));
        ProjectConfiguration configuration = ProjectConfiguration.empty();
        configurations.save(project.id(), configuration);

        project.workspacePathIfPresent()
                .ifPresent(path -> configurationStore.save(path, configuration));

        return project;
    }

    /**
     * Adopts a directory that already holds a {@code .vortex/vortex.yaml}.
     *
     * <p>This is what makes the portable configuration genuinely portable: a pipeline can check the
     * service out and run a test without anyone having opened the interface on that machine first.
     *
     * <p>The file is the source of truth in this direction, so nothing is written back to it —
     * adoption mirrors the committed configuration into the local index and leaves the directory
     * byte-for-byte as it was found. Adopting the same directory twice updates the project that
     * already represents it rather than accumulating near-duplicates on every CI run.
     */
    public AdoptionResult adopt(String workspacePath) {
        ConfigurationStore.LoadResult loaded = configurationStore.load(workspacePath);
        if (!loaded.isValid()) {
            return new AdoptionResult(null, loaded);
        }

        String canonical = canonicalPath(workspacePath);
        Project project = projects.findAll().stream()
                .filter(candidate -> !candidate.workspacePath().isBlank())
                .filter(candidate -> canonical.equals(canonicalPath(candidate.workspacePath())))
                .findFirst()
                .orElseGet(() -> projects.save(Project.create(
                        availableName(directoryName(canonical)), "", canonical, clock.now())));

        // Deliberately not saveConfiguration(): that mirrors the database back out to the file, and
        // here the file is what we just read. The release identity still has to reach the project
        // row, or an adopted project would show no version until someone edited it.
        configurations.save(project.id(), loaded.configuration());
        projects.save(mirrorServiceVersion(project, loaded.configuration()).touch(clock.now()));

        return new AdoptionResult(project, loaded);
    }

    /**
     * The outcome of {@link #adopt(String)}.
     *
     * <p>Carries the load result whether or not adoption succeeded, so a caller can report exactly
     * which file was read and what was wrong with it.
     *
     * @param project the adopted project, or {@code null} when the configuration could not be loaded
     * @param source  where the configuration was read from, and any problems found in it
     */
    public record AdoptionResult(Project project, ConfigurationStore.LoadResult source) {

        public boolean adopted() {
            return project != null;
        }
    }

    private static String canonicalPath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static String directoryName(String canonicalPath) {
        Path fileName = Path.of(canonicalPath).getFileName();
        return fileName == null ? "project" : fileName.toString();
    }

    /** Keeps project names unique without failing a pipeline over a naming collision. */
    private String availableName(String preferred) {
        if (projects.findByName(preferred).isEmpty()) {
            return preferred;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = preferred + " (" + suffix + ")";
            if (projects.findByName(candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    public Optional<Project> find(ProjectId id) {
        return projects.findById(id);
    }

    public List<Project> all() {
        return projects.findAll();
    }

    public ProjectConfiguration configuration(ProjectId id) {
        return configurations.findByProject(id).orElseGet(ProjectConfiguration::empty);
    }

    /**
     * Saves configuration to the portable file and mirrors it into the local index.
     *
     * <p>The file write comes first deliberately: if it fails — a read-only directory, a missing
     * path — the local index is not left claiming a state that was never persisted where it matters.
     */
    public ProjectConfiguration saveConfiguration(ProjectId id, ProjectConfiguration configuration) {
        Project project = require(id);
        project.workspacePathIfPresent()
                .ifPresent(path -> configurationStore.save(path, configuration));
        configurations.save(id, configuration);
        projects.save(mirrorServiceVersion(project, configuration).touch(clock.now()));
        return configuration;
    }

    /**
     * Copies the configured release onto the project row.
     *
     * <p>The configuration is the authority; this is a denormalised copy so the project list can
     * show a version per row without loading and parsing every project's configuration. The same
     * trade the executions table already makes for its workload and environment columns.
     */
    private Project mirrorServiceVersion(Project project, ProjectConfiguration configuration) {
        return project.serviceVersion().equals(configuration.serviceVersion())
                ? project
                : project.withDetails(project.name(), project.description(),
                        configuration.serviceVersion(), clock.now());
    }

    public Optional<ServiceCatalog> catalog(ProjectId id) {
        return catalogs.findByProject(id);
    }

    /**
     * Records that a person has reviewed an operation's request data, or withdraws that review.
     *
     * <p>Written into the project's configuration rather than the catalog, so it lands in
     * {@code vortex.yaml} and can be reviewed in a pull request like any other decision. It also
     * means re-importing a specification cannot silently re-approve — or silently revoke — an
     * approval somebody made deliberately.
     */
    public ProjectConfiguration setOperationReviewed(ProjectId id,
            dev.vortex.core.shared.OperationId operationId, boolean reviewed) {
        ProjectConfiguration configuration = configuration(id);
        var binding = configuration.bindingOrDefault(operationId).withReviewed(reviewed);
        return saveConfiguration(id, configuration.withBinding(binding));
    }

    public ProjectReadiness readiness(ProjectId id) {
        boolean catalogImported = catalogs.findByProject(id)
                .map(catalog -> !catalog.isEmpty())
                .orElse(false);
        boolean hasExecuted = executions.countByProject(id) > 0;
        return configuration(id).readiness(catalogImported, hasExecuted);
    }

    /** The YAML a user sees behind "View configuration". */
    public String renderConfiguration(ProjectId id) {
        return configurationStore.render(configuration(id));
    }

    /**
     * Deletes a service and everything recorded against it: configuration, catalog, runs, analyses
     * and capacity observations mirror out of the database via cascading foreign keys, and each
     * run's raw evidence is removed from the filesystem here, since the database has no reference
     * to clean that up for it.
     *
     * <p>The service's own {@code .vortex/vortex.yaml}, if it has a workspace path, is left exactly
     * where it is — that file lives in the service's own repository, not Vortex's, and deleting a
     * service from Vortex's index is not authorization to delete a file from someone else's repo.
     *
     * @throws IllegalArgumentException when no service exists with this id
     * @throws IllegalStateException    when a run is still in progress
     */
    public void delete(ProjectId id) {
        Project project = require(id);
        List<TestExecution> history = executions.findByProject(id, Integer.MAX_VALUE);
        boolean running = history.stream().anyMatch(execution -> !execution.isTerminal());
        if (running) {
            throw new IllegalStateException(
                    "Cannot delete '" + project.name() + "' while a run is in progress.");
        }
        history.forEach(execution -> artifacts.delete(execution.id()));
        projects.delete(id);
    }

    private Project require(ProjectId id) {
        return projects.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No project with id " + id));
    }
}
