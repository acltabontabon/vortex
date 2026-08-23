package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.data.DatasetException;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetScope;
import dev.vortex.core.shared.ProjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("storing datasets")
class FilesystemDatasetStoreTest {

    private static final String CSV = """
            customerId,mobile
            C001,09171234567
            C002,09181234567
            """;

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path serviceDirectory;

    private FilesystemDatasetStore store;
    private DatasetHome home;

    @BeforeEach
    void setUp() {
        store = new FilesystemDatasetStore(new VortexWorkspace(workspaceRoot), new ObjectMapper());
        home = DatasetHome.of(ProjectId.of("project-1"), serviceDirectory.toString());
    }

    private void storeLocal(String name, String content) {
        store.store(home, DatasetScope.LOCAL, name, DatasetFormat.CSV,
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("where a dataset lands")
    class Placement {

        @Test
        @DisplayName("an upload lands on this machine, and never in the service's repository")
        void uploadsAreLocalByDefault() {
            // The safe default. A file dragged into a browser must not become a commit in somebody's
            // repository, because test data that turns out to be real customer data is not a mistake
            // anyone should be able to make that way.
            storeLocal("customers", CSV);

            assertThat(workspaceRoot.resolve("datasets/project-1/customers.csv")).exists();
            assertThat(serviceDirectory.resolve(".vortex/datasets/customers.csv")).doesNotExist();
        }

        @Test
        @DisplayName("making a dataset portable writes it beside the service's own configuration")
        void promotionWritesIntoTheService() {
            storeLocal("customers", CSV);

            var promoted = store.promote(home, DatasetRef.local("customers"));

            assertThat(promoted.scope()).isEqualTo(DatasetScope.PORTABLE);
            assertThat(serviceDirectory.resolve(".vortex/datasets/customers.csv")).exists();
        }

        @Test
        @DisplayName("the interface can say which file promotion would write, before it happens")
        void promotionTargetIsKnowableInAdvance() {
            storeLocal("customers", CSV);

            assertThat(store.promotionTarget(home, DatasetRef.local("customers")))
                    .isEqualTo(serviceDirectory.resolve(".vortex/datasets/customers.csv").toString());
        }

        @Test
        @DisplayName("a service with no directory of its own cannot hold a portable dataset")
        void promotionNeedsAWorkspace() {
            var homeless = DatasetHome.of(ProjectId.of("project-1"), "");
            store.store(homeless, DatasetScope.LOCAL, "customers", DatasetFormat.CSV,
                    CSV.getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> store.promote(homeless, DatasetRef.local("customers")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("nowhere to commit");
        }
    }

    @Nested
    @DisplayName("scope is part of the reference")
    class Scope {

        @Test
        @DisplayName("a local dataset is never answered by a portable one of the same name")
        void scopesDoNotFallThroughToEachOther() throws Exception {
            // The reason a DatasetValue carries its scope. Resolving between two datasets called
            // "customers" by an undocumented precedence rule would mean a run using data nobody
            // chose, and reporting it as though they had.
            Files.createDirectories(serviceDirectory.resolve(".vortex/datasets"));
            Files.writeString(serviceDirectory.resolve(".vortex/datasets/customers.csv"),
                    "customerId\nPORTABLE-1\n");

            assertThat(store.find(home, DatasetRef.local("customers"))).isEmpty();
            assertThat(store.find(home, DatasetRef.portable("customers"))).isPresent();
            assertThat(store.read(home, DatasetRef.portable("customers")).rows().getFirst())
                    .containsEntry("customerId", "PORTABLE-1");
        }

        @Test
        @DisplayName("both scopes are listed, so nothing a service uses is invisible")
        void listingCoversBothScopes() {
            storeLocal("customers", CSV);
            store.store(home, DatasetScope.PORTABLE, "accounts", DatasetFormat.CSV,
                    "accountId\nA1\n".getBytes(StandardCharsets.UTF_8));

            assertThat(store.list(home)).extracting(d -> d.name() + ":" + d.scope())
                    .containsExactlyInAnyOrder("accounts:PORTABLE", "customers:LOCAL");
        }
    }

    @Nested
    @DisplayName("derived facts")
    class Derived {

        @Test
        @DisplayName("fields and record count are read from the file, never from configuration")
        void metadataIsRead() {
            var stored = store.store(home, DatasetScope.LOCAL, "customers", DatasetFormat.CSV,
                    CSV.getBytes(StandardCharsets.UTF_8));

            assertThat(stored.fields()).containsExactly("customerId", "mobile");
            assertThat(stored.recordCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("editing the file changes what Vortex reports, with nothing to keep in sync")
        void metadataFollowsTheFile() throws Exception {
            storeLocal("customers", CSV);
            Files.writeString(workspaceRoot.resolve("datasets/project-1/customers.csv"),
                    "customerId,mobile\nC001,090\nC002,091\nC003,092\n");

            var found = store.find(home, DatasetRef.local("customers")).orElseThrow();

            assertThat(found.recordCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("the content hash changes when the data does, so two runs can be told apart")
        void contentHashTracksTheData() {
            String before = store.store(home, DatasetScope.LOCAL, "customers", DatasetFormat.CSV,
                    CSV.getBytes(StandardCharsets.UTF_8)).contentHash();
            String after = store.store(home, DatasetScope.LOCAL, "customers", DatasetFormat.CSV,
                    (CSV + "C003,09191234567\n").getBytes(StandardCharsets.UTF_8)).contentHash();

            assertThat(before).isNotBlank().isNotEqualTo(after);
        }
    }

    @Nested
    @DisplayName("refusals and replacement")
    class Lifecycle {

        @Test
        @DisplayName("a file that cannot be parsed is not stored, so nothing broken is left behind")
        void malformedContentIsNeverWritten() {
            assertThatThrownBy(() -> store.store(home, DatasetScope.LOCAL, "customers",
                    DatasetFormat.JSON, "not json".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(DatasetException.class);

            assertThat(store.find(home, DatasetRef.local("customers"))).isEmpty();
        }

        @Test
        @DisplayName("replacing a dataset with another format leaves only the new one")
        void replacementDoesNotLeaveTwoFiles() {
            storeLocal("customers", CSV);
            store.store(home, DatasetScope.LOCAL, "customers", DatasetFormat.JSON,
                    "[{\"customerId\": \"C001\"}]".getBytes(StandardCharsets.UTF_8));

            // Two files would mean a later read finding whichever the lookup happened to try first.
            assertThat(workspaceRoot.resolve("datasets/project-1/customers.csv")).doesNotExist();
            assertThat(workspaceRoot.resolve("datasets/project-1/customers.json")).exists();
        }

        @Test
        @DisplayName("a missing dataset says where it was expected and what to do about it")
        void absenceIsExplained() {
            assertThatThrownBy(() -> store.read(home, DatasetRef.portable("customers")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("in this service's own directory");

            assertThatThrownBy(() -> store.read(home, DatasetRef.local("customers")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("on this machine");
        }

        @Test
        @DisplayName("a name that would escape the dataset directory is refused")
        void namesCannotTraverse() {
            // DatasetRef already restricts the character set; this is the second check, because the
            // cost is one comparison and the failure mode is somebody's private key.
            assertThatThrownBy(() -> DatasetRef.local("../../.ssh/id_rsa"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("letters, digits, hyphens and underscores");
        }

        @Test
        @DisplayName("deleting removes the file and nothing else")
        void deleteRemovesOneDataset() {
            storeLocal("customers", CSV);
            storeLocal("accounts", "accountId\nA1\n");

            store.delete(home, DatasetRef.local("customers"));

            assertThat(store.list(home)).extracting(d -> d.name()).containsExactly("accounts");
        }
    }

    @Nested
    @DisplayName("writing is atomic")
    class AtomicWrite {

        @Test
        @DisplayName("no temporary file survives a successful write")
        void noTemporaryFileSurvives() throws Exception {
            storeLocal("customers", CSV);

            Path directory = workspaceRoot.resolve("datasets/project-1");
            try (var entries = Files.list(directory)) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.endsWith(".tmp"));
            }
        }

        @Test
        @DisplayName("a write that cannot create its temporary file leaves the previous dataset untouched")
        void aFailedWriteLeavesThePreviousDatasetUntouched() {
            storeLocal("customers", CSV);
            Path directory = workspaceRoot.resolve("datasets/project-1");

            assertThat(directory.toFile().setWritable(false)).isTrue();
            try {
                assertThatThrownBy(() -> storeLocal("customers", CSV + "C003,09191234567\n"))
                        .isInstanceOf(java.io.UncheckedIOException.class);
            } finally {
                assertThat(directory.toFile().setWritable(true)).isTrue();
            }

            assertThat(store.read(home, DatasetRef.local("customers")).recordCount()).isEqualTo(2);
        }
    }
}
