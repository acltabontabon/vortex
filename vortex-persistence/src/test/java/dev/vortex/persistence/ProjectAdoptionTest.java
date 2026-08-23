package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.application.ProjectService;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.Clock;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.persistence.config.YamlConfigurationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Adopting a directory that already holds a committed {@code vortex.yaml}.
 *
 * <p>This is what makes {@code vortex run <workload> <path>} possible, and therefore what makes the
 * portable configuration worth committing: a machine that has never run Vortex before can execute
 * the test the repository describes.
 *
 * <p>Run against real SQLite and the real YAML store, because the risk here is precisely in the
 * seams — the ordinary project-creation path writes a fresh empty configuration out to the
 * workspace, which against an existing repository would destroy the very file being adopted.
 */
class ProjectAdoptionTest {

    private ProjectService projects;
    private YamlConfigurationStore configurationStore;

    @BeforeEach
    void setUp(@TempDir Path workspaceDirectory) {
        VortexWorkspace workspace = new VortexWorkspace(workspaceDirectory).ensureExists();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(workspace.jdbcUrl());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
                .migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        configurationStore = new YamlConfigurationStore();

        projects = new ProjectService(
                new JdbcProjectRepository(jdbc),
                new JdbcProjectRepository.Configurations(jdbc, configurationStore),
                new JdbcProjectRepository.Catalogs(jdbc),
                new JdbcExecutionRepository(jdbc, new NamedParameterJdbcTemplate(dataSource)),
                configurationStore,
                Clock.systemUtc());
    }

    /** Writes a committed configuration into a directory, as a repository would hold it. */
    private Path aRepositoryContaining(Path directory, ProjectConfiguration configuration) {
        configurationStore.save(directory.toString(), configuration);
        return directory;
    }

    @Test
    @DisplayName("a directory holding a configuration becomes a project")
    void adoptsADirectory(@TempDir Path repository) {
        aRepositoryContaining(repository, Fixtures.configuration());

        ProjectService.AdoptionResult result = projects.adopt(repository.toString());

        assertThat(result.adopted()).isTrue();
        assertThat(projects.all()).hasSize(1);
        assertThat(projects.configuration(result.project().id()).workloads())
                .extracting(workload -> workload.name())
                .containsExactlyElementsOf(
                        Fixtures.configuration().workloads().stream().map(s -> s.name()).toList());
    }

    @Test
    @DisplayName("the committed file is not rewritten — it is the source of truth being read")
    void leavesTheFileExactlyAsItWasFound(@TempDir Path repository) throws Exception {
        aRepositoryContaining(repository, Fixtures.configuration());
        Path file = repository.resolve(".vortex").resolve("vortex.yaml");
        byte[] before = Files.readAllBytes(file);

        projects.adopt(repository.toString());

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("adopting twice updates the same project rather than accumulating duplicates")
    void isIdempotent(@TempDir Path repository) {
        aRepositoryContaining(repository, Fixtures.configuration());

        var first = projects.adopt(repository.toString());
        var second = projects.adopt(repository.toString());

        assertThat(second.project().id()).isEqualTo(first.project().id());
        assertThat(projects.all()).hasSize(1);
    }

    @Test
    @DisplayName("a relative path and an absolute path are the same directory")
    void normalisesThePath(@TempDir Path repository) {
        aRepositoryContaining(repository, Fixtures.configuration());

        var absolute = projects.adopt(repository.toString());
        var viaParent = projects.adopt(
                repository.resolve("..").resolve(repository.getFileName()).toString());

        assertThat(viaParent.project().id()).isEqualTo(absolute.project().id());
        assertThat(projects.all()).hasSize(1);
    }

    @Test
    @DisplayName("a directory with no configuration adopts nothing and says where it looked")
    void reportsAMissingConfiguration(@TempDir Path empty) {
        ProjectService.AdoptionResult result = projects.adopt(empty.toString());

        assertThat(result.adopted()).isFalse();
        assertThat(result.project()).isNull();
        assertThat(result.source().sourcePath()).contains("vortex.yaml");
        assertThat(projects.all()).isEmpty();
    }

    @Test
    @DisplayName("an invalid configuration adopts nothing, so a pipeline never runs a broken plan")
    void reportsAnInvalidConfiguration(@TempDir Path repository) throws Exception {
        Path directory = Files.createDirectories(repository.resolve(".vortex"));
        Files.writeString(directory.resolve("vortex.yaml"), """
                version: 1
                workloads:
                  peak:
                    type: PEAK
                    arrivalRate: -5
                    duration: 10m
                """);

        ProjectService.AdoptionResult result = projects.adopt(repository.toString());

        assertThat(result.adopted()).isFalse();
        assertThat(result.source().problems()).isNotEmpty();
        assertThat(projects.all()).isEmpty();
    }
}
