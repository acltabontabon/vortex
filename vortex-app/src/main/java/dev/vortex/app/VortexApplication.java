package dev.vortex.app;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The Vortex executable.
 *
 * <p>One binary, two front ends. Run it with no command and it starts the web interface; run it with
 * one and it behaves as a command-line tool and exits with a meaningful code.
 *
 * <pre>
 * vortex                          start the workbench in your browser
 * vortex doctor                   check that the tools Vortex needs are present
 * vortex validate                 check a project's configuration
 * vortex run peak --headless      execute a workload and exit with its verdict
 * </pre>
 *
 * <p>Both routes call the same application services. That is not an accident of structure but the
 * point of it: a performance definition that can only be run by clicking through a UI cannot be run
 * by a pipeline, and a tool whose CI mode diverges from its interactive mode will eventually
 * disagree with itself about whether a build passed.
 *
 * <p>The web interface binds to the loopback address. Vortex runs work against services on the
 * user's behalf and has no authentication, so it should not be reachable from the network without a
 * deliberate decision — see {@code docs/02-architecture/security.adoc}.
 */
@SpringBootApplication
@EnableConfigurationProperties(VortexProperties.class)
public class VortexApplication {

    public static void main(String[] args) {
        Arguments arguments = Arguments.split(args);

        if (arguments.isCommand()) {
            System.exit(runCommandLine(arguments));
        }

        new SpringApplicationBuilder(VortexApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
    }

    /**
     * Separates Spring configuration from the command and its arguments.
     *
     * <p>They can appear in any order, because people write
     * {@code vortex --vortex.ai.model=x doctor} as readily as the other way round. Getting this
     * wrong is not a small mistake: an early version keyed off the first argument alone, so a
     * leading option silently started the web server instead of running the command — and then
     * failed with "port already in use", which explains nothing.
     *
     * <p>Spring options always carry a value ({@code --key=value}). A bare {@code --flag} such as
     * {@code --headless} therefore belongs to the command.
     *
     * @param options Spring configuration, passed to the application context
     * @param command the command and its own arguments
     */
    record Arguments(List<String> options, List<String> command) {

        static Arguments split(String[] args) {
            List<String> options = new ArrayList<>();
            List<String> command = new ArrayList<>();

            for (String arg : args) {
                if (arg.startsWith("--") && arg.contains("=")) {
                    options.add(arg);
                } else {
                    command.add(arg);
                }
            }
            return new Arguments(List.copyOf(options), List.copyOf(command));
        }

        /** Whether this invocation names a command rather than only configuring the web interface. */
        boolean isCommand() {
            return !command.isEmpty() && !command.getFirst().startsWith("--");
        }
    }

    private static int runCommandLine(Arguments arguments) {
        List<String> springArguments = new ArrayList<>(arguments.options());
        // The command line prints a program's output, not diagnostics. Progress and results are
        // written deliberately; startup logging is not, and a pipeline reading stdout should not
        // have to filter it.
        springArguments.add("--logging.level.root=WARN");
        springArguments.add("--logging.level.dev.vortex=WARN");

        var context = new SpringApplicationBuilder(VortexApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(springArguments.toArray(String[]::new));
        try {
            return context.getBean(dev.vortex.app.cli.VortexCommandRunner.class)
                    .run(arguments.command().toArray(String[]::new));
        } finally {
            SpringApplication.exit(context);
        }
    }
}
