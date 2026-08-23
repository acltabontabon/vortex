package com.acltabontabon.vortex.core.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoadGeneratorResourceBudgetTest {

    @Nested
    @DisplayName("automatic")
    class Automatic {

        @Test
        @DisplayName("names an intent, never a number")
        void carriesNoEnvelope() {
            var budget = LoadGeneratorResourceBudget.automatic();

            assertThat(budget.mode()).isEqualTo(LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC);
            assertThat(budget.envelope().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("cannot be constructed carrying a concrete envelope")
        void rejectsAnEnvelope() {
            var envelope = new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500),
                    MemoryAllocation.ofMebibytes(256));

            assertThatThrownBy(() -> new LoadGeneratorResourceBudget(
                    LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC, envelope))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("intent");
        }
    }

    @Nested
    @DisplayName("custom")
    class Custom {

        @Test
        @DisplayName("carries the configured envelope")
        void carriesTheEnvelope() {
            var budget = LoadGeneratorResourceBudget.custom(CpuAllocation.ofMillicores(2000),
                    MemoryAllocation.ofMebibytes(2048));

            assertThat(budget.mode()).isEqualTo(LoadGeneratorResourceBudget.BudgetMode.CUSTOM);
            assertThat(budget.envelope().cpuIfPresent()).contains(CpuAllocation.ofMillicores(2000));
            assertThat(budget.envelope().memoryIfPresent()).contains(MemoryAllocation.ofMebibytes(2048));
        }

        @Test
        @DisplayName("cannot configure cpu alone")
        void rejectsCpuAlone() {
            var envelope = new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500), null);

            assertThatThrownBy(() -> new LoadGeneratorResourceBudget(
                    LoadGeneratorResourceBudget.BudgetMode.CUSTOM, envelope))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both cpu and memory");
        }

        @Test
        @DisplayName("cannot configure memory alone")
        void rejectsMemoryAlone() {
            var envelope = new ResourceEnvelopeRequest(null, MemoryAllocation.ofMebibytes(256));

            assertThatThrownBy(() -> new LoadGeneratorResourceBudget(
                    LoadGeneratorResourceBudget.BudgetMode.CUSTOM, envelope))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both cpu and memory");
        }

        @Test
        @DisplayName("cannot be constructed with neither")
        void rejectsNeither() {
            assertThatThrownBy(() -> new LoadGeneratorResourceBudget(
                    LoadGeneratorResourceBudget.BudgetMode.CUSTOM, ResourceEnvelopeRequest.none()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both cpu and memory");
        }
    }
}
