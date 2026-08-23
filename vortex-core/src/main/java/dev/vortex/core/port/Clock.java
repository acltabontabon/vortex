package dev.vortex.core.port;

import java.time.Instant;

/**
 * The current time, injected so that time-dependent behaviour can be tested deterministically.
 *
 * <p>Vortex records execution timestamps in UTC. The UI renders them in the viewer's local zone;
 * the stored values never depend on where the machine happens to be.
 *
 * <p>Defined here rather than using {@code java.time.Clock} to keep the port surface small and
 * intention-revealing — the application only ever needs "what time is it now".
 */
@FunctionalInterface
public interface Clock {

    Instant now();

    static Clock systemUtc() {
        return Instant::now;
    }

    /** A clock frozen at a fixed instant, for tests. */
    static Clock fixed(Instant instant) {
        return () -> instant;
    }
}
