package dev.vortex.core.environment;

/**
 * What class of question a test run is capable of answering.
 *
 * <p>This distinction is the single most important honesty mechanism in Vortex. A local run against
 * mocked dependencies can tell you a great deal about your application — its concurrency, its
 * connection pools, its serialisation costs, its regressions. It cannot tell you what your service
 * will do in production, because the network, the managed services, the real database sizes and the
 * platform limits are all absent.
 *
 * <p>Every report states its classification prominently, so that "1000 requests/sec against
 * simulated dependencies" can never be presented as "production capacity is 1000 requests/sec".
 */
public enum TestClassification {

    ISOLATED("Isolated performance test",
            "How does this application behave when its external dependencies are controlled or simulated?",
            "Good evidence for application bottlenecks, internal concurrency, connection pools, "
                    + "serialisation costs and regressions between versions.",
            "This run does not establish production capacity. Dependencies were simulated or "
                    + "controlled, so network effects, real dependency latency and platform limits "
                    + "are not represented."),

    INTEGRATED("Integrated performance test",
            "How does the deployed service behave together with production-like dependencies and infrastructure?",
            "Good evidence for realistic capacity, dependency latency, platform limits, autoscaling "
                    + "behaviour and real integration constraints.",
            "Capacity evidence from this run applies to the environment and dependency "
                    + "configuration recorded with it, and to no other.");

    private final String label;
    private final String question;
    private final String goodFor;
    private final String caveat;

    TestClassification(String label, String question, String goodFor, String caveat) {
        this.label = label;
        this.question = question;
        this.goodFor = goodFor;
        this.caveat = caveat;
    }

    public String label() {
        return label;
    }

    /** The question this class of test can legitimately answer. */
    public String question() {
        return question;
    }

    public String goodFor() {
        return goodFor;
    }

    /** The limitation that must accompany any capacity claim from this class of test. */
    public String caveat() {
        return caveat;
    }
}
