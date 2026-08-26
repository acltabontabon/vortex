package com.acltabontabon.vortex.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The Vortex executable — starts the web workbench.
 *
 * <p>The web interface binds to the loopback address. Vortex runs work against services on the
 * user's behalf and has no authentication, so it should not be reachable from the network without a
 * deliberate decision — see {@code docs/02-architecture/security.adoc}.
 */
@SpringBootApplication
@EnableConfigurationProperties(VortexProperties.class)
public class VortexApplication {

    private static final Logger log = LoggerFactory.getLogger(VortexApplication.class);

    public static void main(String[] args) {
        log.info("Vortex — starting local workbench…");
        new SpringApplicationBuilder(VortexApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
    }
}
