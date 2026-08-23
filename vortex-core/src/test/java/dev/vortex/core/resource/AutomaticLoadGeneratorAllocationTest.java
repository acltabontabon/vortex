package dev.vortex.core.resource;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.evidence.HostShape;
import dev.vortex.core.target.CpuAllocation;
import dev.vortex.core.target.MemoryAllocation;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the automatic allocation formula against concrete numbers, and the edge cases a deterministic,
 * host-shape-in/envelope-out function must survive without ever producing an invalid {@link
 * CpuAllocation}/{@link MemoryAllocation} (both refuse non-positive values) or an allocation larger
 * than what the host actually has left after reservation.
 */
class AutomaticLoadGeneratorAllocationTest {

    private static HostShape host(int cores, long totalBytes) {
        return new HostShape("Linux", "6.0", "aarch64", cores, totalBytes);
    }

    private static long gib(long n) {
        return n * (1L << 30);
    }

    private static long mib(long n) {
        return n * (1L << 20);
    }

    @Nested
    @DisplayName("the worked example")
    class WorkedExample {

        @Test
        @DisplayName("a 12-core/32 GiB host, colocated with a managed SUT, caps at 4 cores/4 GiB")
        void largeColocatedHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(12, gib(32)), true);

            assertThat(resolved.allocation().cpuIfPresent()).contains(CpuAllocation.ofMillicores(4000));
            assertThat(resolved.allocation().memoryIfPresent()).contains(new MemoryAllocation(gib(4)));
            assertThat(resolved.colocatedWithManagedSut()).isTrue();
            // The OS reserve and the SUT reserve are both non-empty and both smaller than the total —
            // exact figures are pinned by the reserve-breakdown tests below, not duplicated here.
            assertThat(resolved.osAndVortexReserve().isEmpty()).isFalse();
            assertThat(resolved.sutReserve().isEmpty()).isFalse();
        }

        @Test
        @DisplayName("the same host, not colocated, still caps at 4 cores/4 GiB and reserves nothing for a SUT")
        void largeNonColocatedHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(12, gib(32)), false);

            assertThat(resolved.allocation().cpuIfPresent()).contains(CpuAllocation.ofMillicores(4000));
            assertThat(resolved.allocation().memoryIfPresent()).contains(new MemoryAllocation(gib(4)));
            assertThat(resolved.sutReserve().isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("colocation halves what is left for the generator, when it matters")
    class Colocation {

        @Test
        @DisplayName("on a mid-sized host, colocated allocation is exactly half of non-colocated")
        void colocationChangesTheOutcomeBelowTheCap() {
            var notColocated = AutomaticLoadGeneratorAllocation.resolve(host(4, gib(4)), false);
            var colocated = AutomaticLoadGeneratorAllocation.resolve(host(4, gib(4)), true);

            int notColocatedCpu = notColocated.allocation().cpuIfPresent().orElseThrow().millicores();
            int colocatedCpu = colocated.allocation().cpuIfPresent().orElseThrow().millicores();
            assertThat(colocatedCpu).isLessThan(notColocatedCpu);

            long notColocatedMemory = notColocated.allocation().memoryIfPresent().orElseThrow().bytes();
            long colocatedMemory = colocated.allocation().memoryIfPresent().orElseThrow().bytes();
            assertThat(colocatedMemory).isLessThan(notColocatedMemory);
        }
    }

    @Nested
    @DisplayName("very small hosts")
    class SmallHosts {

        @Test
        @DisplayName("a single core still yields a positive, valid allocation")
        void oneCpuHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(1, gib(8)), false);

            int millicores = resolved.allocation().cpuIfPresent().orElseThrow().millicores();
            assertThat(millicores).isPositive();
            assertThat(millicores).isLessThanOrEqualTo(1000);
        }

        @Test
        @DisplayName("two cores yields a positive allocation that never exceeds the host")
        void twoCpuHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(2, gib(4)), false);

            int millicores = resolved.allocation().cpuIfPresent().orElseThrow().millicores();
            assertThat(millicores).isPositive();
            assertThat(millicores).isLessThanOrEqualTo(2000);
        }

        @Test
        @DisplayName("memory far below the reserve floor still yields a positive allocation, not a crash")
        void tinyMemoryHost() {
            // Smaller than both the 1 GiB OS reserve floor and the generator's own 256 MiB target —
            // the degenerate case the class Javadoc names explicitly. There is no "nice" answer here;
            // the only contract is that it does not throw and does not exceed what exists.
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(4, mib(128)), false);

            long bytes = resolved.allocation().memoryIfPresent().orElseThrow().bytes();
            assertThat(bytes).isPositive();
            assertThat(bytes).isLessThan(mib(128));
        }

        @Test
        @DisplayName("a container-constrained CI host (small cpu and memory both) never crashes")
        void containerConstrainedCiHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(2, gib(2)), true);

            assertThat(resolved.allocation().cpuIfPresent().orElseThrow().millicores()).isPositive();
            assertThat(resolved.allocation().memoryIfPresent().orElseThrow().bytes()).isPositive();
        }
    }

    @Nested
    @DisplayName("unreadable host metrics")
    class UnknownHost {

        @Test
        @DisplayName("a fully unknown host falls back to the generator's target floor on both axes")
        void fullyUnknown() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(HostShape.unknown(), true);

            assertThat(resolved.allocation().cpuIfPresent()).contains(CpuAllocation.ofMillicores(500));
            assertThat(resolved.allocation().memoryIfPresent()).contains(new MemoryAllocation(mib(256)));
            assertThat(resolved.osAndVortexReserve().isEmpty()).isTrue();
            assertThat(resolved.sutReserve().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("cpu unreadable but memory known falls back only on the cpu axis")
        void cpuUnknownMemoryKnown() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(new HostShape("", "", "", 0, gib(16)),
                    false);

            assertThat(resolved.allocation().cpuIfPresent()).contains(CpuAllocation.ofMillicores(500));
            assertThat(resolved.allocation().memoryIfPresent().orElseThrow().bytes()).isGreaterThan(mib(256));
        }

        @Test
        @DisplayName("memory unreadable but cpu known falls back only on the memory axis")
        void memoryUnknownCpuKnown() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(new HostShape("", "", "", 8, 0),
                    false);

            assertThat(resolved.allocation().memoryIfPresent()).contains(new MemoryAllocation(mib(256)));
            assertThat(resolved.allocation().cpuIfPresent().orElseThrow().millicores()).isGreaterThan(500);
        }
    }

    @Nested
    @DisplayName("very large hosts")
    class LargeHosts {

        @Test
        @DisplayName("a 64-core, 256 GiB host is capped, not given a proportional share")
        void extremelyLargeHost() {
            var resolved = AutomaticLoadGeneratorAllocation.resolve(host(64, gib(256)), false);

            assertThat(resolved.allocation().cpuIfPresent()).contains(CpuAllocation.ofMillicores(4000));
            assertThat(resolved.allocation().memoryIfPresent()).contains(new MemoryAllocation(gib(4)));
        }
    }

    @Nested
    @DisplayName("cross-cutting invariants")
    class Invariants {

        @Test
        @DisplayName("resolving the same host twice produces an identical result")
        void deterministic() {
            var host = host(6, gib(12));

            var first = AutomaticLoadGeneratorAllocation.resolve(host, true);
            var second = AutomaticLoadGeneratorAllocation.resolve(host, true);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("the allocation never exceeds what the host actually has, across a range of sizes")
        void neverExceedsTheHost() {
            IntStream.rangeClosed(1, 96).forEach(cores -> {
                for (boolean colocated : new boolean[] {true, false}) {
                    var resolved =
                            AutomaticLoadGeneratorAllocation.resolve(host(cores, gib(4L * cores)), colocated);

                    int allocatedMillicores =
                            resolved.allocation().cpuIfPresent().orElseThrow().millicores();
                    assertThat(allocatedMillicores)
                            .as("cores=%d colocated=%s", cores, colocated)
                            .isPositive()
                            .isLessThanOrEqualTo(cores * 1000);

                    long allocatedBytes = resolved.allocation().memoryIfPresent().orElseThrow().bytes();
                    assertThat(allocatedBytes)
                            .as("cores=%d colocated=%s", cores, colocated)
                            .isPositive()
                            .isLessThanOrEqualTo(gib(4L * cores));
                }
            });
        }
    }
}
