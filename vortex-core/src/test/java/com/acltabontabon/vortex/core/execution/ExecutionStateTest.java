package com.acltabontabon.vortex.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExecutionStateTest {

    @Test
    @DisplayName("the happy path runs from creation to a verdict without an analysis step")
    void happyPath() {
        ExecutionState state = ExecutionState.CREATED;
        for (ExecutionState next : List.of(
                ExecutionState.VALIDATING, ExecutionState.READY, ExecutionState.STARTING,
                ExecutionState.RUNNING, ExecutionState.COLLECTING, ExecutionState.EVALUATING,
                ExecutionState.COMPLETED)) {
            assertThat(state.canTransitionTo(next)).as("%s -> %s", state, next).isTrue();
            state = next;
        }
        assertThat(state).isEqualTo(ExecutionState.COMPLETED);
    }

    @Test
    @DisplayName("there is no ANALYZING state: AI interpretation is a separate resource")
    void noAnalysisStateExists() {
        assertThat(EnumSet.allOf(ExecutionState.class))
                .noneMatch(state -> state.name().contains("ANALY"));
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionState.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalStatesAcceptNoFurtherTransitions(ExecutionState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedNext()).isEmpty();

        for (ExecutionState next : ExecutionState.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    void skippingAheadIsRejected() {
        assertThatThrownBy(() -> ExecutionState.CREATED.requireTransitionTo(ExecutionState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot move from CREATED to RUNNING");
    }

    @Test
    void movingBackwardsIsRejected() {
        assertThatThrownBy(() -> ExecutionState.COMPLETED.requireTransitionTo(ExecutionState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED is a final state");
    }

    @Test
    void everyNonTerminalStateCanReachFailure() {
        for (ExecutionState state : ExecutionState.values()) {
            if (!state.isTerminal()) {
                assertThat(state.canTransitionTo(ExecutionState.FAILED))
                        .as("%s should be able to fail", state)
                        .isTrue();
            }
        }
    }

    @Test
    void anExecutionRecordsWhenTrafficStartedAndWhenItFinished() {
        var execution = TestExecution.create(ExecutionId.of("e1"), Fixtures.plan(), Fixtures.NOW);

        var finished = execution
                .transitionTo(ExecutionState.VALIDATING, Fixtures.NOW)
                .transitionTo(ExecutionState.READY, Fixtures.NOW)
                .transitionTo(ExecutionState.STARTING, Fixtures.NOW)
                .transitionTo(ExecutionState.RUNNING, Fixtures.NOW.plusSeconds(2))
                .transitionTo(ExecutionState.COLLECTING, Fixtures.NOW.plusSeconds(602))
                .transitionTo(ExecutionState.EVALUATING, Fixtures.NOW.plusSeconds(603))
                .transitionTo(ExecutionState.COMPLETED, Fixtures.NOW.plusSeconds(604));

        assertThat(finished.startedAt()).isEqualTo(Fixtures.NOW.plusSeconds(2));
        assertThat(finished.duration()).hasValueSatisfying(
                duration -> assertThat(duration.toSeconds()).isEqualTo(602));
    }

    @Test
    @DisplayName("only the states where an engine process can be running are marked active")
    void activeStates() {
        assertThat(EnumSet.allOf(ExecutionState.class).stream().filter(ExecutionState::isActive).toList())
                .containsExactlyInAnyOrder(ExecutionState.STARTING, ExecutionState.RUNNING,
                        ExecutionState.COLLECTING);
    }
}
