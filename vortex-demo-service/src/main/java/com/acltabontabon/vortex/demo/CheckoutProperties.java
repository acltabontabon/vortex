package com.acltabontabon.vortex.demo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the demonstration service.
 *
 * <p>Adjusting these moves where the service starts to struggle, which is useful when demonstrating
 * Vortex on a machine faster or slower than the defaults assume. The relationship is
 * straightforward: a pool of {@code workers} each taking {@code downstreamLatency} can serve
 * roughly {@code workers / downstreamLatency} requests per second before requests begin to queue.
 *
 * @param workers            how many requests can be processed concurrently
 * @param downstreamLatency  simulated time spent calling a downstream system
 * @param latencyJitter      random variation added to the simulated latency, so percentiles behave
 *                           like a real service rather than a step function
 * @param acquireTimeout     how long a request waits for a worker before giving up with 503
 */
@ConfigurationProperties(prefix = "checkout")
public record CheckoutProperties(
        int workers,
        Duration downstreamLatency,
        Duration latencyJitter,
        Duration acquireTimeout) {

    public CheckoutProperties {
        if (workers <= 0) {
            workers = 6;
        }
        downstreamLatency = downstreamLatency == null ? Duration.ofMillis(35) : downstreamLatency;
        latencyJitter = latencyJitter == null ? Duration.ofMillis(10) : latencyJitter;
        acquireTimeout = acquireTimeout == null ? Duration.ofSeconds(5) : acquireTimeout;
    }

    /**
     * The rate at which arrivals begin to outpace service capacity, in requests per second.
     *
     * <p>Exposed for the service's own {@code /about} endpoint, so that someone running the demo can
     * see what to expect. Vortex never reads this — it infers everything from measurements.
     */
    public double theoreticalCapacityPerSecond() {
        double serviceTimeSeconds = downstreamLatency.toMillis() / 1000.0;
        return serviceTimeSeconds <= 0 ? Double.MAX_VALUE : workers / serviceTimeSeconds;
    }
}
