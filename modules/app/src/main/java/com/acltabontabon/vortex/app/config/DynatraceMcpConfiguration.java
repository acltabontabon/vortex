package com.acltabontabon.vortex.app.config;

import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.core.port.ProductionObservationSource;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpAvailability;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpClientFactory;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpObservationSource;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Dynatrace MCP adapter, exactly the way {@code AiConfiguration} wires the local AI
 * assistant: beans are always registered, never conditional on a property being set, because an
 * unconfigured {@link DynatraceMcpSettings} ({@code enabled=false}, blank endpoint) is a valid, inert
 * value — the same "starts, reports unavailable, never fails to boot" contract {@code AiSettings}
 * already gives an unconfigured model.
 *
 * <p>{@link CoreConfiguration#calibrationService} collects every {@link ProductionObservationSource}
 * bean into a list automatically; registering this one here is the whole of what makes
 * {@code observation.transport: mcp} resolvable, with no change to that wiring.
 */
@Configuration(proxyBeanMethods = false)
public class DynatraceMcpConfiguration {

    @Bean
    DynatraceMcpSettings dynatraceMcpSettings(VortexProperties properties) {
        VortexProperties.DynatraceMcp config = properties.dynatraceMcp();
        return new DynatraceMcpSettings(config.enabled(), config.endpoint(), config.headers(),
                config.defaultWindow(), config.queryTimeout());
    }

    @Bean
    DynatraceMcpClientFactory dynatraceMcpClientFactory(DynatraceMcpSettings settings) {
        return new DynatraceMcpClientFactory(settings);
    }

    @Bean
    DynatraceMcpAvailability dynatraceMcpAvailability(DynatraceMcpSettings settings,
            DynatraceMcpClientFactory clients) {
        return new DynatraceMcpAvailability(settings, clients);
    }

    @Bean
    ProductionObservationSource dynatraceMcpObservationSource(DynatraceMcpClientFactory clients,
            DynatraceMcpSettings settings) {
        return new DynatraceMcpObservationSource(clients, settings);
    }
}
