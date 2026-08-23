package dev.vortex.core.resource;

/**
 * What kind of resource a signal measures.
 *
 * <p>A closed set of performance-engineering categories, not a taxonomy of everything a monitoring
 * system publishes. A kind earns its place by changing what a conclusion may say. CPU and memory do.
 * {@link #RUNTIME_PAUSE} does — a stop-the-world pause explains a latency tail in a way no
 * utilisation figure can. "Number of HTTP sessions" does not, and stays an ordinary observation.
 *
 * <p>The core reasons over these and never over a metric name. Which name a provider gave a signal
 * is the adapter's business, the same way the core knows a measurement came from Prometheus without
 * knowing what PromQL is.
 *
 * <p>Runtime telemetry is a kind here rather than a tier: a JVM's heap, a Go runtime's, a .NET
 * process's and a Node process's all land in {@link #RUNTIME_MEMORY} without a line of new domain
 * code. There is no JVM package and no {@code isSpringBoot} anywhere below this.
 */
public enum ResourceKind {

    CPU("CPU"),
    MEMORY("Memory"),
    NETWORK("Network"),
    DISK("Disk"),

    /** Heap and non-heap memory managed by a language runtime. */
    RUNTIME_MEMORY("Runtime memory"),

    /** Time the runtime stopped doing work — garbage collection pauses and their kin. */
    RUNTIME_PAUSE("Runtime pause"),

    /** A bounded set of reusable things: connections, sessions, workers. */
    POOL("Pool"),

    /** Work waiting to be done. */
    QUEUE("Queue"),

    THREADS("Threads");

    private final String label;

    ResourceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
