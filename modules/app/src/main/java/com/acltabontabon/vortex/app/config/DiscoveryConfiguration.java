package com.acltabontabon.vortex.app.config;

import com.acltabontabon.vortex.app.discovery.DiscoveryConfigurationAssembler;
import com.acltabontabon.vortex.app.discovery.DockerComposeYamlDetector;
import com.acltabontabon.vortex.app.discovery.ProjectSnapshotBuilder;
import com.acltabontabon.vortex.app.discovery.SpringConfigYamlDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Project Discovery's adapters — the detectors that need Jackson's YAML support, which
 * {@code vortex-core} may not depend on (ADR-013). The core-legal detectors ({@code
 * MavenPomDetector}, {@code DockerfileDetector}, {@code EnvTemplateDetector}) and the {@code
 * ProjectDiscoveryService} that aggregates every detector are wired in {@link CoreConfiguration}
 * instead, alongside the rest of the domain.
 */
@Configuration(proxyBeanMethods = false)
public class DiscoveryConfiguration {

    @Bean
    SpringConfigYamlDetector springConfigYamlDetector() {
        return new SpringConfigYamlDetector();
    }

    @Bean
    DockerComposeYamlDetector dockerComposeYamlDetector() {
        return new DockerComposeYamlDetector();
    }

    @Bean
    ProjectSnapshotBuilder projectSnapshotBuilder() {
        return new ProjectSnapshotBuilder();
    }

    @Bean
    DiscoveryConfigurationAssembler discoveryConfigurationAssembler() {
        return new DiscoveryConfigurationAssembler();
    }
}
