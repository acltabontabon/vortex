package com.acltabontabon.vortex.core.workload;

/**
 * The kind of performance question a workload is asking.
 *
 * <p>These are the established load-testing patterns, named as the field already names them rather
 * than translated into Vortex vocabulary. Organisations use some of these words slightly
 * differently, so Vortex leads with the question each one answers instead of with a definition: "can
 * this service handle its expected peak?" is a question a service owner can act on, while arguing
 * about whether that is a load test or a stress test is not.
 *
 * <h2>A test type is intent, not a schedule</h2>
 * A test type says nothing about how traffic is produced. It does not select an executor, a
 * {@code WorkloadModel}, or a constant-versus-ramping shape, and Vortex rejects no combination of
 * the two. A soak may be a closed workload if the real caller is a bounded population; a spike may
 * be a two-stage arrival-rate ramp or a step change in virtual users; a breakpoint search may ramp
 * either quantity. The workload is chosen from the traffic being reproduced, and this enum records
 * what the run is for.
 *
 * <p>Deliberately absent: <em>baseline</em>, which is a comparison concept rather than a workload —
 * a baseline is the run you measure against, and Vortex records that as a
 * {@code CapacityObservation} and a plan fingerprint. Also absent: <em>volume</em>, which describes
 * the size of the data the service holds rather than the traffic arriving at it, and belongs with
 * the environment the run was executed against.
 */
public enum TestType {

    SMOKE("Smoke",
            "Is the workload itself valid and can Vortex reach the service?",
            "A very small amount of traffic for a very short time. Run this first: it catches "
                    + "configuration and connectivity mistakes before you generate meaningful load."),

    AVERAGE_LOAD("Average load",
            "Does the service meet its objectives under the traffic it normally receives?",
            "Reproduces representative production traffic — composition as well as volume. This is "
                    + "the run future releases are compared against, and the one most worth grounding "
                    + "in real observed numbers."),

    STRESS("Stress",
            "How does the service behave at or above its expected peak?",
            "Traffic heavier than normal, held long enough to see whether objectives still hold. "
                    + "Covers both 'can we survive the busiest hour of the year?' and 'what happens "
                    + "when we push past it?'. Use a breakpoint test instead when you want to find "
                    + "the limit rather than test a particular level."),

    SPIKE("Spike",
            "How does the service react to a sudden jump in demand?",
            "Load rises abruptly rather than gradually, exposing cold caches, autoscaling lag and "
                    + "connection-pool warm-up behaviour that a smooth ramp never reveals."),

    SOAK("Soak",
            "Does performance degrade during sustained load?",
            "Moderate load held for a long time, surfacing memory leaks, pool exhaustion, queue "
                    + "accumulation and slow resource drift. The failure this finds is usually "
                    + "invisible in the first ten minutes."),

    BREAKPOINT("Breakpoint",
            "At what level does the service stop meeting its objectives?",
            "Load increases in stages until an objective is violated or the system saturates. This "
                    + "is how tested capacity is established, and the evidence is only as good as the "
                    + "number of stages: two stages bracket the limit loosely, eight bracket it "
                    + "tightly.");

    private final String label;
    private final String question;
    private final String guidance;

    TestType(String label, String question, String guidance) {
        this.label = label;
        this.question = question;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    /**
     * The type as it reads mid-sentence: {@code "an average-load test"}, {@code "a stress test"}.
     *
     * <p>Here rather than at each call site because several of them build the same phrase, and
     * assembling it from {@link #label()} produces "a average load test" — which is the kind of
     * detail that quietly undermines a qualification an engineer is meant to take seriously.
     */
    public String asPhrase() {
        String words = label.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        String article = "aeiou".indexOf(words.charAt(0)) >= 0 ? "an " : "a ";
        return article + words + " test";
    }

    /** The question this kind of test answers. Shown to the user before and after the run. */
    public String question() {
        return question;
    }

    /** Beginner-facing explanation of when to reach for this kind of test. */
    public String guidance() {
        return guidance;
    }

    /**
     * Whether this kind of test is expected to push the service beyond its objectives by design.
     *
     * <p>A statement of intent, consumed by execution safety policy so that a run which is
     * <em>meant</em> to hurt is confirmed rather than merely permitted. It says nothing about which
     * executor will be used.
     */
    public boolean isSaturating() {
        return this == STRESS || this == SPIKE || this == BREAKPOINT;
    }
}
