package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.evidence.HostShape;

/**
 * What machine Vortex is running on.
 *
 * <p>A port rather than a static read, for the same reason {@link Clock} is one: {@code vortex-core}
 * does not call {@code System.getProperty} or {@code Runtime.getRuntime()}, and a provenance record
 * that varies with the machine running the test suite is not testable.
 */
@FunctionalInterface
public interface HostInformation {

    HostShape describeHost();

    /** For a context that has no host to describe, or has chosen not to record one. */
    static HostInformation unknown() {
        return HostShape::unknown;
    }
}
