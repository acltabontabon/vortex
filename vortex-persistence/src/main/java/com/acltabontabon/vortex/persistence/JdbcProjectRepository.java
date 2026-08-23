package com.acltabontabon.vortex.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.port.ConfigurationStore;
import com.acltabontabon.vortex.core.port.Repositories.ProjectConfigurationRepository;
import com.acltabontabon.vortex.core.port.Repositories.ProjectRepository;
import com.acltabontabon.vortex.core.port.Repositories.ServiceCatalogRepository;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Project storage, backed by SQLite.
 *
 * <p>Hand-written SQL through {@code JdbcClient} rather than an ORM. For a schema this size an
 * object-relational mapper would add a large dependency, a lifecycle to reason about, and
 * reflection that has to be taught to a native image — in exchange for generating queries that are
 * shorter to write by hand. The queries are also the documentation of what is actually stored.
 */
public final class JdbcProjectRepository implements ProjectRepository {

    private final JdbcClient jdbc;

    public JdbcProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Project save(Project project) {
        jdbc.sql("""
                INSERT INTO projects (id, name, description, workspace_path, service_version,
                                      created_at, updated_at)
                VALUES (:id, :name, :description, :workspacePath, :serviceVersion, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    name = excluded.name,
                    description = excluded.description,
                    workspace_path = excluded.workspace_path,
                    service_version = excluded.service_version,
                    updated_at = excluded.updated_at
                """)
                .param("id", project.id().value())
                .param("name", project.name())
                .param("description", project.description())
                .param("workspacePath", project.workspacePath())
                .param("serviceVersion", project.serviceVersion())
                .param("createdAt", project.createdAt().toString())
                .param("updatedAt", project.updatedAt().toString())
                .update();
        return project;
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        return jdbc.sql("SELECT * FROM projects WHERE id = :id")
                .param("id", id.value())
                .query(this::mapProject)
                .optional();
    }

    @Override
    public Optional<Project> findByName(String name) {
        return jdbc.sql("SELECT * FROM projects WHERE name = :name COLLATE NOCASE")
                .param("name", name)
                .query(this::mapProject)
                .optional();
    }

    @Override
    public List<Project> findAll() {
        return jdbc.sql("SELECT * FROM projects ORDER BY updated_at DESC")
                .query(this::mapProject)
                .list();
    }

    @Override
    public void delete(ProjectId id) {
        jdbc.sql("DELETE FROM projects WHERE id = :id").param("id", id.value()).update();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM projects").query(Long.class).single();
    }

    private Project mapProject(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Project(
                ProjectId.of(rs.getString("id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("workspace_path"),
                rs.getString("service_version"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    /**
     * The project's performance definition, mirrored from {@code vortex.yaml}.
     *
     * <p>Stored as the same YAML text the file contains, rather than shredded into columns. The file
     * is the authority; keeping one serialised form means the database index and the committed file
     * cannot quietly disagree about what an operation weight is.
     */
    public static final class Configurations implements ProjectConfigurationRepository {

        private final JdbcClient jdbc;
        private final ConfigurationStore configurationStore;

        public Configurations(JdbcClient jdbc, ConfigurationStore configurationStore) {
            this.jdbc = jdbc;
            this.configurationStore = configurationStore;
        }

        @Override
        public void save(ProjectId projectId, ProjectConfiguration configuration) {
            jdbc.sql("""
                    INSERT INTO project_configurations (project_id, format, content, updated_at)
                    VALUES (:projectId, 'yaml', :content, :updatedAt)
                    ON CONFLICT (project_id) DO UPDATE SET
                        content = excluded.content,
                        updated_at = excluded.updated_at
                    """)
                    .param("projectId", projectId.value())
                    .param("content", configurationStore.render(configuration))
                    .param("updatedAt", Instant.now().toString())
                    .update();
        }

        @Override
        public Optional<ProjectConfiguration> findByProject(ProjectId projectId) {
            return jdbc.sql("SELECT content FROM project_configurations WHERE project_id = :projectId")
                    .param("projectId", projectId.value())
                    .query(String.class)
                    .optional()
                    .flatMap(content -> configurationStore.parse(content, "stored configuration").value());
        }
    }

    /** Operations discovered from an API description, stored as a JSON document. */
    public static final class Catalogs implements ServiceCatalogRepository {

        private final JdbcClient jdbc;
        private final ObjectMapper json = JsonDocuments.mapper();

        public Catalogs(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void save(ProjectId projectId, ServiceCatalog catalog) {
            jdbc.sql("""
                    INSERT INTO service_catalogs (project_id, import_source, source_ref, title,
                                                  version, content, imported_at)
                    VALUES (:projectId, :source, :sourceRef, :title, :version, :content, :importedAt)
                    ON CONFLICT (project_id) DO UPDATE SET
                        import_source = excluded.import_source,
                        source_ref = excluded.source_ref,
                        title = excluded.title,
                        version = excluded.version,
                        content = excluded.content,
                        imported_at = excluded.imported_at
                    """)
                    .param("projectId", projectId.value())
                    .param("source", catalog.source().name())
                    .param("sourceRef", catalog.sourceRef())
                    .param("title", catalog.title())
                    .param("version", catalog.version())
                    .param("content", write(catalog))
                    .param("importedAt", catalog.importedAt().toString())
                    .update();
        }

        @Override
        public Optional<ServiceCatalog> findByProject(ProjectId projectId) {
            return jdbc.sql("SELECT content FROM service_catalogs WHERE project_id = :projectId")
                    .param("projectId", projectId.value())
                    .query(String.class)
                    .optional()
                    .map(this::read);
        }

        private String write(ServiceCatalog catalog) {
            try {
                return json.writeValueAsString(catalog);
            } catch (Exception e) {
                throw new IllegalStateException("Could not store the service catalog", e);
            }
        }

        private ServiceCatalog read(String content) {
            try {
                return json.readValue(content, ServiceCatalog.class);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "The stored service catalog could not be read. Re-import the API description "
                                + "to rebuild it.", e);
            }
        }
    }
}
