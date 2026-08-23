package dev.vortex.app.adapter;

import dev.vortex.core.evidence.HostShape;
import dev.vortex.core.port.HostInformation;
import java.lang.management.ManagementFactory;

/**
 * The host, read from the JDK.
 *
 * <p>{@code availableProcessors} is what this process can actually use — under a container it is the
 * cgroup quota rather than the physical machine's count, which is the number that matters for
 * reproducing a run. Memory comes from the same container-aware bean the generator observer reads,
 * and is reported as zero when that bean is not the extended one rather than guessed from the heap.
 */
public final class JdkHostInformation implements HostInformation {

    @Override
    public HostShape describeHost() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        long memory = bean instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getTotalMemorySize()
                : 0;

        return new HostShape(
                bean.getName(),
                bean.getVersion(),
                bean.getArch(),
                Runtime.getRuntime().availableProcessors(),
                Math.max(memory, 0));
    }
}
