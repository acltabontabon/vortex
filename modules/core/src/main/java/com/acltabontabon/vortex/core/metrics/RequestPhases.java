package com.acltabontabon.vortex.core.metrics;

import java.util.Optional;

/**
 * Where a request's time actually went.
 *
 * <p>One p95 of 800 ms has at least two very different explanations, and the aggregate cannot tell
 * them apart: a service that takes 780 ms to answer, and a service that answers in 40 ms behind a
 * connection that took 740 ms to establish. The first is a service problem, the second is very often
 * a test-environment problem — and quoting the second as service latency is how a capacity figure
 * ends up describing a network.
 *
 * <p>{@code waiting} is time to first byte, which is the half that isolates the server's own think
 * time from everything around it. It is the phase most conclusions should rest on.
 *
 * <h2>This one is deliberately HTTP-shaped</h2>
 * Connecting and TLS handshaking are what HTTP genuinely does, and flattening them into
 * transport-neutral names would produce a vocabulary no second transport would ever populate and
 * this one no longer describes. Protocol independence is a property of the <em>reasoning</em> model —
 * validity, resources, limits, capacity, provenance — not of every measurement an adapter reports.
 * Nothing in {@code core.analysis} reasons over these; they are carried, cited and rendered.
 *
 * <p>Absent phases stay absent. A run whose engine reported no breakdown has
 * {@link #isEmpty()} true, which is not the same statement as every phase having taken no time.
 */
public record RequestPhases(
        LatencyPercentiles blocked,
        LatencyPercentiles connecting,
        LatencyPercentiles tlsHandshaking,
        LatencyPercentiles sending,
        LatencyPercentiles waiting,
        LatencyPercentiles receiving) {

    public RequestPhases {
        blocked = orEmpty(blocked);
        connecting = orEmpty(connecting);
        tlsHandshaking = orEmpty(tlsHandshaking);
        sending = orEmpty(sending);
        waiting = orEmpty(waiting);
        receiving = orEmpty(receiving);
    }

    /** No phase breakdown was reported. */
    public static RequestPhases empty() {
        return new RequestPhases(null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return blocked.isEmpty() && connecting.isEmpty() && tlsHandshaking.isEmpty()
                && sending.isEmpty() && waiting.isEmpty() && receiving.isEmpty();
    }

    /**
     * Time to first byte, when it was measured.
     *
     * <p>Named separately because it is the phase a latency conclusion should prefer, and a caller
     * reaching for it should not have to know it is called {@code waiting} in the engine's
     * vocabulary.
     */
    public Optional<LatencyPercentiles> serverThinkTimeIfPresent() {
        return waiting.isEmpty() ? Optional.empty() : Optional.of(waiting);
    }

    private static LatencyPercentiles orEmpty(LatencyPercentiles value) {
        return value == null ? LatencyPercentiles.empty() : value;
    }
}
