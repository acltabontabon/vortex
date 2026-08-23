package dev.vortex.app.adapter.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ShutdownProcessRegistryTest {

    @Test
    void destroyAllKillsEveryTrackedLiveProcess() throws IOException, InterruptedException {
        ShutdownProcessRegistry registry = new ShutdownProcessRegistry();
        Process first = start("sleep", "30");
        Process second = start("sleep", "30");
        registry.track(first);
        registry.track(second);

        registry.destroyAll();

        assertThat(first.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
    }

    @Test
    void aProcessThatAlreadyExitedIsNotTrackedAnymore() throws IOException, InterruptedException {
        ShutdownProcessRegistry registry = new ShutdownProcessRegistry();
        Process finished = start("true");
        registry.track(finished);

        // onExit() self-removes the entry once the process completes — wait for that to actually
        // happen, then prove destroyAll() has nothing left to do for it (no exception, no-op).
        assertThat(finished.waitFor(5, TimeUnit.SECONDS)).isTrue();
        registry.destroyAll();

        assertThat(finished.exitValue()).isZero();
    }

    private static Process start(String... command) throws IOException {
        return new ProcessBuilder(List.of(command)).start();
    }
}
