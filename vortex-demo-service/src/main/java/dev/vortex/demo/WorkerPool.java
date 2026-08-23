package dev.vortex.demo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * A bounded pool of workers, standing in for whatever a real service is actually limited by —
 * a connection pool, a thread pool, a rate-limited dependency.
 *
 * <p>Every request acquires a permit, spends a short simulated interval doing downstream work, and
 * releases it. Below capacity this is invisible: a permit is always free and latency is just the
 * downstream time. Above capacity, requests queue for a permit, and queueing delay grows far faster
 * than the arrival rate does — which is why a service that looks comfortable at 100 requests per
 * second can be in serious trouble at 150.
 *
 * <p>The pool publishes utilisation and acquisition wait through Micrometer, exactly as a real
 * connection pool would. That is the only channel through which Vortex learns anything about it:
 * there is no back door, and Vortex's analysis has to work from the same evidence an engineer would
 * have when investigating an unfamiliar service.
 */
@Component
public class WorkerPool {

    private final CheckoutProperties properties;
    private final Semaphore permits;
    private final AtomicInteger inUse = new AtomicInteger();
    private final Timer acquireTimer;

    public WorkerPool(CheckoutProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.permits = new Semaphore(properties.workers(), true);

        this.acquireTimer = Timer.builder("checkout.pool.acquire")
                .description("Time spent waiting for a worker to become available")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        registry.gauge("checkout.pool.size", properties.workers());
        registry.gauge("checkout.pool.active", inUse, AtomicInteger::get);
        registry.gauge("checkout.pool.utilization", inUse,
                active -> (double) active.get() / properties.workers());
        registry.gauge("checkout.pool.pending", permits, Semaphore::getQueueLength);
    }

    /**
     * Runs work with a worker held, or throws when none becomes available in time.
     *
     * @throws CapacityExceededException when the wait exceeds the configured timeout, which the web
     *                                   layer turns into a 503 — the same thing a saturated real
     *                                   service does
     */
    public <T> T withWorker(WorkUnit<T> work) {
        long startedWaiting = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapacityExceededException("Interrupted while waiting for a worker");
        }
        acquireTimer.record(System.nanoTime() - startedWaiting, TimeUnit.NANOSECONDS);

        if (!acquired) {
            throw new CapacityExceededException(
                    "No worker became available within " + properties.acquireTimeout().toMillis() + " ms");
        }

        inUse.incrementAndGet();
        try {
            simulateDownstreamCall();
            return work.execute();
        } finally {
            inUse.decrementAndGet();
            permits.release();
        }
    }

    /**
     * Sleeps for the configured downstream latency plus a little jitter.
     *
     * <p>The jitter matters more than it looks: without it every request takes exactly the same time
     * and the latency percentiles become a step function, which is nothing like a real service and
     * would make the demonstration misleading.
     */
    private void simulateDownstreamCall() {
        long base = properties.downstreamLatency().toMillis();
        long jitter = properties.latencyJitter().toMillis();
        long millis = jitter <= 0 ? base : base + ThreadLocalRandom.current().nextLong(jitter + 1);
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int activeWorkers() {
        return inUse.get();
    }

    public int poolSize() {
        return properties.workers();
    }

    @FunctionalInterface
    public interface WorkUnit<T> {

        T execute();
    }

    /** Thrown when the pool is saturated. Surfaces as HTTP 503. */
    public static class CapacityExceededException extends RuntimeException {

        public CapacityExceededException(String message) {
            super(message);
        }
    }
}
