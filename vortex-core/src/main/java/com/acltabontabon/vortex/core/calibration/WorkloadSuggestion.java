package com.acltabontabon.vortex.core.calibration;

import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A proposed workload derived arithmetically from observed production traffic.
 *
 * <p>Always an arrival-rate workload. Production telemetry reports throughput, and turning an
 * observed request rate into a virtual-user count would require assuming a latency — which is the
 * thing the test is meant to find out. Anyone who genuinely needs a closed workload has a client
 * population in mind and should say what it is.
 *
 * @param type        the evaluation this workload is for
 * @param name        suggested workload name
 * @param description what this workload represents, in plain words
 * @param rate        suggested steady rate, for constant-rate workloads
 * @param stages      suggested ramp targets, for stress-shaped workloads
 * @param duration    suggested duration
 * @param source      where the figure came from, carrying the arithmetic that produced it
 */
public record WorkloadSuggestion(
        TestType type,
        String name,
        String description,
        RequestsPerSecond rate,
        List<RequestsPerSecond> stages,
        Duration duration,
        WorkloadSource source) {

    public WorkloadSuggestion {
        Objects.requireNonNull(type, "type");
        stages = stages == null ? List.of() : List.copyOf(stages);
        name = name == null ? type.name().toLowerCase(java.util.Locale.ROOT) : name;
        description = description == null ? "" : description.trim();
        source = source == null ? WorkloadSource.manual() : source;
    }

    /**
     * How this number was arrived at.
     *
     * <p>Read from the source rather than held beside it, so that accepting a suggestion cannot
     * produce a workload whose provenance says less than the proposal the user reviewed. Previously
     * this text was written into the workload's description, where the first edit destroyed it.
     */
    public String derivation() {
        return source.derivation();
    }

    public boolean isRamp() {
        return !stages.isEmpty();
    }
}
