package dev.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.shared.OperationId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Percentages are shown to people, so they have to read like percentages.
 *
 * <p>{@code BigDecimal.stripTrailingZeros()} turns 100.0 into {@code 1E+2}. That is correct
 * arithmetic and unusable in an interface — a mix with a single operation rendered as
 * "1E+2% createOrder", which is the sort of detail that makes a tool feel unfinished.
 */
class PercentageDisplayTest {

    private static final OperationId ONLY = OperationId.of("getOrder");
    private static final OperationId OTHER = OperationId.of("createOrder");
    private static final OperationId THIRD = OperationId.of("cancelOrder");

    @Test
    @DisplayName("a single operation is 100%, never 1E+2%")
    void wholeHundredRendersPlainly() {
        assertThat(OperationMix.single(ONLY).sharePercent(ONLY)).isEqualTo("100");
    }

    @Test
    void ordinarySplitsRenderPlainly() {
        OperationMix mix = OperationMix.of(List.of(
                WeightedOperation.of(ONLY, 70),
                WeightedOperation.of(OTHER, 30)));

        assertThat(mix.sharePercent(ONLY)).isEqualTo("70");
        assertThat(mix.sharePercent(OTHER)).isEqualTo("30");
    }

    @Test
    void recurringSplitsKeepOneDecimal() {
        OperationMix thirds = OperationMix.of(List.of(
                WeightedOperation.of(ONLY, 1),
                WeightedOperation.of(OTHER, 1),
                WeightedOperation.of(THIRD, 1)));

        assertThat(thirds.sharePercent(ONLY)).isEqualTo("33.3");
    }

    @Test
    void nothingRendersInScientificNotation() {
        for (int weight : new int[] {1, 10, 100, 1000, 10000}) {
            OperationMix mix = OperationMix.of(List.of(
                    WeightedOperation.of(ONLY, weight),
                    WeightedOperation.of(OTHER, 1)));

            assertThat(mix.sharePercent(ONLY)).doesNotContain("E").doesNotContain("e");
            assertThat(mix.sharePercent(OTHER)).doesNotContain("E").doesNotContain("e");
        }
    }
}
