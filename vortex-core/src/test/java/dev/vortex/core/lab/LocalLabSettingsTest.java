package dev.vortex.core.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalLabSettingsTest {

    @Test
    @DisplayName("normalizes a repo-relative compose path before storing it")
    void normalizesBeforeStoring() {
        assertThat(new LocalLabSettings("./infra/../compose.yaml").composeFile())
                .isEqualTo("compose.yaml");
        assertThat(new LocalLabSettings("./compose.yaml").composeFile()).isEqualTo("compose.yaml");
        assertThat(new LocalLabSettings("  infra/compose.yaml  ").composeFile())
                .isEqualTo("infra/compose.yaml");
    }

    @Test
    @DisplayName("rejects an absolute compose path, because vortex.yaml travels between machines")
    void rejectsAbsolutePaths() {
        assertThatThrownBy(() -> new LocalLabSettings("/Users/someone/checkout/compose.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative")
                .hasMessageContaining("travels between machines");
    }

    @Test
    @DisplayName("rejects a compose path outside the service repository")
    void rejectsClimbingOut() {
        assertThatThrownBy(() -> new LocalLabSettings("../compose.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("rejects a normalized compose path that escapes the service repository")
    void rejectsEscapeThatOnlyAppearsAfterNormalising() {
        assertThatThrownBy(() -> new LocalLabSettings("infra/../../outside.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("needs a compose file at all")
    void rejectsBlank() {
        assertThatThrownBy(() -> new LocalLabSettings("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compose.yaml");
    }

    @Test
    @DisplayName("resolves against the service's workspace")
    void resolvesAgainstWorkspace() {
        Path workspace = Path.of("target", "checkout").toAbsolutePath().normalize();
        LocalLabSettings settings = new LocalLabSettings("infra/compose.yaml");

        Path resolved = settings.resolveAgainst(workspace.toString());

        assertThat(resolved).isAbsolute()
                .isEqualTo(workspace.resolve("infra").resolve("compose.yaml"));
    }

    @Test
    @DisplayName("says so when the service has no repository to resolve against")
    void refusesToResolveWithoutAWorkspace() {
        LocalLabSettings settings = new LocalLabSettings("compose.yaml");

        assertThatThrownBy(() -> settings.resolveAgainst(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no repository on this machine");
    }
}
