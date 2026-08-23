package dev.vortex.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * checkout-service — a small, deliberately imperfect service for demonstrating Vortex.
 *
 * <p>This exists so that a reviewer can run a genuine performance test within minutes of cloning
 * the repository, without needing a service of their own. It is a real Spring Boot application
 * serving real HTTP traffic; nothing about the resulting measurements is simulated.
 *
 * <h2>The bottleneck is deliberate, and documented</h2>
 * The service holds a bounded pool of workers and each request must acquire one before doing its
 * (simulated) downstream work. That is an ordinary design — it is what a connection pool, a thread
 * pool or a rate-limited dependency does — and it produces ordinary queueing behaviour: latency
 * stays flat while the pool can keep up, then climbs steeply once arrivals outpace service capacity.
 *
 * <p>Two properties of this arrangement matter for the demonstration:
 *
 * <ul>
 *   <li><strong>Vortex knows nothing about it.</strong> There is no special case anywhere in Vortex
 *       for this service. It observes latency, errors and the pool metrics this service publishes
 *       through its ordinary metrics endpoint, and draws conclusions from that evidence alone.</li>
 *   <li><strong>Nothing is scripted.</strong> There is no rule that says "fail above N requests per
 *       second". The degradation emerges from contention, which means the exact point at which it
 *       appears moves with the machine, the JIT and whatever else is running — exactly as it would
 *       for a real service.</li>
 * </ul>
 *
 * @see WorkerPool
 */
@SpringBootApplication
@EnableConfigurationProperties(CheckoutProperties.class)
public class CheckoutServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
    }
}
