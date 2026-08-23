package com.acltabontabon.vortex.core.evidence;

/**
 * The shape of the machine that produced the numbers.
 *
 * <p>A capacity figure without it is not reproducible six months later. "410 requests/sec" measured
 * on an eight-core laptop and on a thirty-two-core build agent are different facts, and an
 * {@code evidence.json} copied to another machine has to be able to say which one it was — otherwise
 * the reader's only options are to trust it or to discard it.
 *
 * <p>This describes the host Vortex ran on, which is the load generator. The service under test may
 * be somewhere else entirely; what it ran on is recorded through its own telemetry, where any
 * exists.
 *
 * @param availableProcessors cores visible to this process, which is the cgroup quota under a
 *                            container rather than the physical machine's count
 */
public record HostShape(String operatingSystem, String osVersion, String architecture,
                        int availableProcessors, long totalMemoryBytes) {

    public HostShape {
        operatingSystem = operatingSystem == null ? "" : operatingSystem;
        osVersion = osVersion == null ? "" : osVersion;
        architecture = architecture == null ? "" : architecture;
        if (availableProcessors < 0 || totalMemoryBytes < 0) {
            throw new IllegalArgumentException("a host's shape cannot be negative");
        }
    }

    /**
     * A run whose host was not recorded.
     *
     * <p>Reported as unknown rather than guessed. A reader six months later needs to be able to tell
     * "this ran on a machine nobody wrote down" from "this ran on a machine with no cores".
     */
    public static HostShape unknown() {
        return new HostShape("", "", "", 0, 0);
    }

    public boolean isKnown() {
        return !operatingSystem.isBlank() || availableProcessors > 0;
    }

    /** One line for a report footer: {@code "Mac OS X 15.6 (aarch64), 10 cores, 32 GB"}. */
    public String describe() {
        if (!isKnown()) {
            return "not recorded";
        }
        StringBuilder described = new StringBuilder(operatingSystem);
        if (!osVersion.isBlank()) {
            described.append(' ').append(osVersion);
        }
        if (!architecture.isBlank()) {
            described.append(" (").append(architecture).append(')');
        }
        if (availableProcessors > 0) {
            described.append(", ").append(availableProcessors).append(" core")
                    .append(availableProcessors == 1 ? "" : "s");
        }
        if (totalMemoryBytes > 0) {
            described.append(", ").append(Math.round(totalMemoryBytes / 1_073_741_824d)).append(" GB");
        }
        return described.toString();
    }
}
