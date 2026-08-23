package com.acltabontabon.vortex.app.config;

import com.zaxxer.hikari.HikariDataSource;
import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.port.DatasetStore;
import com.acltabontabon.vortex.core.port.ConfigurationStore;
import com.acltabontabon.vortex.core.port.Repositories;
import com.acltabontabon.vortex.persistence.FilesystemArtifactStore;
import com.acltabontabon.vortex.persistence.FilesystemDatasetStore;
import com.acltabontabon.vortex.persistence.JdbcAnalysisRepository;
import com.acltabontabon.vortex.persistence.JdbcExecutionRepository;
import com.acltabontabon.vortex.persistence.JdbcProjectRepository;
import com.acltabontabon.vortex.persistence.JsonDocuments;
import com.acltabontabon.vortex.persistence.VortexWorkspace;
import com.acltabontabon.vortex.persistence.config.YamlConfigurationStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Local storage: an embedded database in the user's home directory, and artifacts on disk beside it.
 *
 * <p>No server to install, no container to start, no connection string to configure. Local-first is
 * a product commitment rather than a limitation, and it is worth very little if getting started
 * requires provisioning a database.
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    VortexWorkspace vortexWorkspace(VortexProperties properties) {
        String configured = properties.workspace().directory();
        VortexWorkspace workspace = configured == null || configured.isBlank()
                ? VortexWorkspace.defaultLocation()
                : new VortexWorkspace(expandHome(configured));
        return workspace.ensureExists();
    }

    private Path expandHome(String path) {
        if (path.startsWith("~")) {
            return Paths.get(System.getProperty("user.home"), path.substring(1));
        }
        return Paths.get(path);
    }

    /**
     * The SQLite connection pool.
     *
     * <p>SQLite in write-ahead-logging mode supports many concurrent readers alongside a single
     * writer, which suits Vortex exactly: the interface reads history and progress while one run
     * records its results. The pool is kept small because the workload genuinely is small — a
     * handful of statements per second at most, even mid-test — and because a large pool of
     * connections to a single local file buys nothing but memory.
     *
     * <p>A generous busy timeout is set on the connection string so that a brief overlap between a
     * reader and the writer waits rather than surfacing as an error.
     */
    @Bean
    @Primary
    DataSource vortexDataSource(VortexWorkspace workspace) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(workspace.jdbcUrl());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setPoolName("vortex-sqlite");
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        dataSource.setAutoCommit(true);
        return dataSource;
    }

    /**
     * Runs schema migrations at startup.
     *
     * <p>Explicit rather than automatic schema generation. The schema is a designed artefact that
     * outlives any one release, and a tool that silently reshapes a user's local database on upgrade
     * is a tool that eventually loses their history.
     */
    @Bean
    Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource, Flyway flyway) {
        // Depending on Flyway here rather than through @DependsOn keeps the ordering visible:
        // no repository may run a query before the schema exists.
        return JdbcClient.create(dataSource);
    }

    /**
     * Used only where {@link JdbcClient} itself does not reach: batched statements. {@code
     * JdbcClient}'s own documentation points here for that case rather than duplicating the
     * lower-level API surface.
     */
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource, Flyway flyway) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ConfigurationStore configurationStore() {
        return new YamlConfigurationStore();
    }

    @Bean
    DatasetStore datasetStore(VortexWorkspace workspace) {
        return new FilesystemDatasetStore(workspace, JsonDocuments.mapper());
    }

    @Bean
    ArtifactStore artifactStore(VortexWorkspace workspace) {
        return new FilesystemArtifactStore(workspace);
    }

    @Bean
    Repositories.ProjectRepository projectRepository(JdbcClient jdbc) {
        return new JdbcProjectRepository(jdbc);
    }

    @Bean
    Repositories.ProjectConfigurationRepository projectConfigurationRepository(JdbcClient jdbc,
            ConfigurationStore configurationStore) {
        return new JdbcProjectRepository.Configurations(jdbc, configurationStore);
    }

    @Bean
    Repositories.ServiceCatalogRepository serviceCatalogRepository(JdbcClient jdbc) {
        return new JdbcProjectRepository.Catalogs(jdbc);
    }

    @Bean
    JdbcExecutionRepository executionRepository(JdbcClient jdbc,
            NamedParameterJdbcTemplate batchJdbc) {
        return new JdbcExecutionRepository(jdbc, batchJdbc);
    }

    @Bean
    Repositories.AnalysisRepository analysisRepository(JdbcClient jdbc) {
        return new JdbcAnalysisRepository(jdbc);
    }

    @Bean
    Repositories.CapacityObservationRepository capacityObservationRepository(JdbcClient jdbc) {
        return new JdbcAnalysisRepository.Capacity(jdbc);
    }
}
