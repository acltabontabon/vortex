package com.acltabontabon.vortex.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Describes the service's own deliberate limits, for whoever is running the demonstration.
 *
 * <p>Vortex never reads this. It exists so a person can see what to expect before running a stress
 * test, and so the demonstration cannot be mistaken for a service that just happens to be slow.
 */
@RestController
public class AboutController {

    private final CheckoutProperties properties;

    public AboutController(CheckoutProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/about")
    public Map<String, Object> about() {
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("service", "checkout-service");
        about.put("purpose",
                "A sample service for demonstrating Vortex. It has a deliberate, documented "
                        + "bottleneck so that a stress test produces meaningful results.");
        about.put("bottleneck", Map.of(
                "kind", "bounded worker pool",
                "workers", properties.workers(),
                "simulatedDownstreamLatencyMs", properties.downstreamLatency().toMillis(),
                "acquireTimeoutMs", properties.acquireTimeout().toMillis()));
        about.put("expectedBehaviour", Map.of(
                "approximateServiceCapacityRequestsPerSecond",
                Math.round(properties.theoreticalCapacityPerSecond()),
                "note",
                "Latency stays flat while the pool keeps up, then climbs as requests queue. The "
                        + "exact point moves with machine speed and background load — nothing here "
                        + "is scripted to fail at a particular rate."));
        about.put("telemetry", java.util.List.of(
                "checkout.pool.utilization", "checkout.pool.active", "checkout.pool.pending",
                "checkout.pool.acquire"));
        return about;
    }
}
