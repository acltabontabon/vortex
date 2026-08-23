package dev.vortex.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.analysis.AnalysisState;
import dev.vortex.core.shared.AnalysisId;
import dev.vortex.core.shared.ExecutionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analysis lifecycle exists separately from the execution lifecycle so that a slow, absent or
 * mistaken language model can never turn a successful measurement into a failed test.
 */
class AnalysisLifecycleTest {

    private static final ExecutionId EXECUTION = ExecutionId.of("e1");

    @Test
    void anAnalysisRunsThroughItsOwnStates() {
        Analysis analysis = Analysis.pending(AnalysisId.of("a1"), EXECUTION);

        assertThat(analysis.state()).isEqualTo(AnalysisState.PENDING);
        assertThat(analysis.transitionTo(AnalysisState.RUNNING)
                .transitionTo(AnalysisState.COMPLETED).state())
                .isEqualTo(AnalysisState.COMPLETED);
    }

    @Test
    void aFailedAnalysisRecordsWhyItFailed() {
        Analysis failed = Analysis.failed(AnalysisId.of("a1"), EXECUTION,
                "Ollama was not reachable.");

        assertThat(failed.state()).isEqualTo(AnalysisState.FAILED);
        assertThat(failed.isUsable()).isFalse();
        assertThat(failed.failureMessage()).contains("not reachable");
    }

    @Test
    @DisplayName("a completed analysis cannot be reopened; re-analysis creates a new record")
    void completedAnalysesAreImmutable() {
        Analysis completed = Analysis.pending(AnalysisId.of("a1"), EXECUTION)
                .transitionTo(AnalysisState.RUNNING)
                .transitionTo(AnalysisState.COMPLETED);

        assertThatThrownBy(() -> completed.transitionTo(AnalysisState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot move from COMPLETED to RUNNING");
    }

    @Test
    void anAnalysisWithoutAConclusionIsNotUsable() {
        Analysis empty = Analysis.pending(AnalysisId.of("a1"), EXECUTION)
                .transitionTo(AnalysisState.RUNNING)
                .transitionTo(AnalysisState.COMPLETED);

        assertThat(empty.isUsable()).isFalse();
    }
}
