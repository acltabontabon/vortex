package dev.vortex.core.workload;

/**
 * Whether the workload controls the rate at which requests arrive, or the number of clients issuing
 * them.
 *
 * <p>This is the oldest distinction in load modelling and Vortex names it in the terms the field
 * already uses, rather than inventing its own. The choice matters because the two models fail
 * differently when the service slows down: an open workload keeps offering the same load and the
 * queue grows, while a closed workload quietly reduces its own offered load, because each virtual
 * user waits for its previous request before issuing the next.
 *
 * <p>Neither is more correct. The right one is whichever matches the real caller.
 */
public enum WorkloadModel {

    OPEN("Arrival rate",
            "How does the service behave when this much traffic arrives, whatever it does about it?",
            "Requests are issued on a schedule, independently of how the service responds. Use this "
                    + "when the workload is externally driven and will not slow down just because "
                    + "your service does — public traffic, upstream systems, event streams, "
                    + "scheduled batches. This is the usual choice for capacity work, because the "
                    + "offered load stays fixed at exactly the moment you most want to hold it "
                    + "steady."),

    CLOSED("Concurrency",
            "How does the service behave with this many clients working through it as fast as it lets them?",
            "A fixed population of virtual users each issue their next request only once the "
                    + "previous one returns. Use this when the real caller really is a bounded "
                    + "population — a connection pool, a fixed worker fleet, a known number of "
                    + "terminals. Throughput becomes an outcome rather than an input, so it falls "
                    + "as latency rises.");

    private final String label;
    private final String question;
    private final String guidance;

    WorkloadModel(String label, String question, String guidance) {
        this.label = label;
        this.question = question;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    /** The question this model is able to answer, phrased for a service owner. */
    public String question() {
        return question;
    }

    /** Beginner-facing explanation of when to reach for this model. */
    public String guidance() {
        return guidance;
    }

    public boolean isOpen() {
        return this == OPEN;
    }

    /** What this model controls, for labelling a figure: {@code requests/sec} or {@code VUs}. */
    public String controlledUnit() {
        return this == OPEN ? "requests/sec" : "VUs";
    }
}
