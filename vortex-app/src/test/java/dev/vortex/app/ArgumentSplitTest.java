package dev.vortex.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How Vortex decides whether it was asked to start the web interface or run a command.
 *
 * <p>An early version keyed off the first argument alone, so
 * {@code vortex --vortex.workspace.directory=/tmp doctor} silently started the web server and then
 * failed with "port already in use" — an error message that explains nothing about what the user
 * actually did wrong.
 */
class ArgumentSplitTest {

    @Test
    @DisplayName("a leading Spring option does not turn a command into a web start-up")
    void optionsMayPrecedeTheCommand() {
        var arguments = VortexApplication.Arguments.split(
                new String[] {"--vortex.ai.model=qwen3:8b", "doctor"});

        assertThat(arguments.isCommand()).isTrue();
        assertThat(arguments.command()).containsExactly("doctor");
        assertThat(arguments.options()).containsExactly("--vortex.ai.model=qwen3:8b");
    }

    @Test
    void optionsMayFollowTheCommand() {
        var arguments = VortexApplication.Arguments.split(
                new String[] {"validate", "--vortex.workspace.directory=/tmp"});

        assertThat(arguments.isCommand()).isTrue();
        assertThat(arguments.command()).containsExactly("validate");
        assertThat(arguments.options()).containsExactly("--vortex.workspace.directory=/tmp");
    }

    @Test
    @DisplayName("a bare flag belongs to the command, since Spring options always carry a value")
    void bareFlagsBelongToTheCommand() {
        var arguments = VortexApplication.Arguments.split(
                new String[] {"run", "peak", "--headless"});

        assertThat(arguments.isCommand()).isTrue();
        assertThat(arguments.command()).containsExactly("run", "peak", "--headless");
        assertThat(arguments.options()).isEmpty();
    }

    @Test
    void noCommandMeansTheWebInterface() {
        assertThat(VortexApplication.Arguments.split(new String[] {"--server.port=9000"}).isCommand())
                .isFalse();
        assertThat(VortexApplication.Arguments.split(new String[] {}).isCommand()).isFalse();
    }
}
